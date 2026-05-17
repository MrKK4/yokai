package yokai.domain.suggestions

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import yokai.domain.manga.MangaRepository

class FeedAggregator(
    private val sourceManager: SourceManager,
    private val mangaRepository: MangaRepository,
    private val preferences: PreferencesHelper,
) {
    suspend fun fetch(suggestionQueries: List<SuggestionQuery>): List<SuggestedManga> {
        return fetchPage(
            suggestionQueries = suggestionQueries,
            usedTags = emptySet(),
            seenMangaUrls = emptySet(),
            currentSortOrder = preferences.suggestionsSortOrder().get(),
            includeSourceSection = true,
            pageOffset = 1,
        ).suggestions
    }

    suspend fun fetchPage(
        suggestionQueries: List<SuggestionQuery>,
        usedTags: Set<String>,
        seenMangaUrls: Set<String>,
        currentSortOrder: SuggestionSortOrder,
        includeSourceSection: Boolean,
        pageOffset: Int = 1,
    ): SuggestionFeedPage {
        val random = Random(System.nanoTime())
        val coldStartDiscovery = suggestionQueries.isEmpty()
        val localManga = mangaRepository.getMangaList()
        val (localKeys, localTitles) = withContext(Dispatchers.Default) {
            localManga.map { it.source to it.url }.toSet() to
                localManga.map { it.title.normalizedTitle() }.toSet()
        }
        val blacklistedTags = preferences.suggestionsTagsBlacklist().get().normalizedQueries()
        val normalizedSeenMangaUrls = if (coldStartDiscovery) emptySet() else seenMangaUrls
        val querySelection = selectQueriesForPage(
            suggestionQueries = suggestionQueries,
            usedTags = usedTags.normalizedQueries(),
            blacklistedTags = blacklistedTags,
            random = random,
        )
        val sources = activeSources(random, includeAllSources = coldStartDiscovery)
        if (sources.isEmpty) {
            return SuggestionFeedPage(
                suggestions = emptyList(),
                usedTags = querySelection.selectedNormalizedTags,
                hasReachedEnd = querySelection.hasReachedEnd,
            )
        }

        val requestGate = Semaphore(MAX_CONCURRENT_SOURCE_REQUESTS)
        val sectionTasks = buildSectionTasks(
            sources = sources,
            suggestionQueries = querySelection.selectedQueries,
            currentSortOrder = currentSortOrder,
            includeSourceSection = includeSourceSection,
            pageOffset = pageOffset,
            coldStartDiscovery = coldStartDiscovery,
            random = random,
        )
        val sectionResults = coroutineScope {
            sectionTasks.map { task ->
                async {
                    fetchSection(
                        task = task,
                        localKeys = localKeys,
                        localTitles = localTitles,
                        seenMangaUrls = normalizedSeenMangaUrls,
                        blacklistedTags = blacklistedTags,
                        requestGate = requestGate,
                        random = random,
                    )
                }
            }.awaitAll()
        }

        val suggestions = withContext(Dispatchers.Default) {
            sectionResults
                .flatten()
                .filterNot { hit -> hit.manga.hasBlacklistedTag(blacklistedTags) }
                .distinctBy { it.sourceId to it.manga.url }
                .sortedByDescending { it.relevance }
                .capBySource(
                    if (coldStartDiscovery) {
                        SuggestionsConfig.COLD_START_MAX_PER_SOURCE_FETCH
                    } else {
                        MAX_PER_SOURCE_GLOBAL
                    },
                )
                .take(if (coldStartDiscovery) SuggestionsConfig.COLD_START_MAX_RESULTS else MAX_TOTAL)
                .mapIndexed { index, hit ->
                    hit.toSuggestion(displayRank = index.toLong())
                }
        }

        return SuggestionFeedPage(
            suggestions = suggestions,
            usedTags = querySelection.selectedNormalizedTags,
            hasReachedEnd = querySelection.hasReachedEnd,
        )
    }

    private suspend fun selectQueriesForPage(
        suggestionQueries: List<SuggestionQuery>,
        usedTags: Set<String>,
        blacklistedTags: Set<String>,
        random: Random,
    ): QuerySelection {
        return withContext(Dispatchers.Default) {
            val queries = suggestionQueries
            val remainingQueries = queries
                .distinctBy { it.query.normalizedQuery() }
                .filterNot { it.query.normalizedQuery() in usedTags }
                .filterNot { it.query.normalizedQuery() in blacklistedTags }
                .sortedByDescending { it.score }

            val selectedQueries = remainingQueries.selectRankedBatch(random)
            QuerySelection(
                selectedQueries = selectedQueries,
                selectedNormalizedTags = selectedQueries.map { it.query.normalizedQuery() }.toSet(),
                hasReachedEnd = selectedQueries.size >= remainingQueries.size,
            )
        }
    }

    private fun List<SuggestionQuery>.selectRankedBatch(random: Random): List<SuggestionQuery> {
        if (isEmpty()) return emptyList()
        if (size <= PAGE_TAG_COUNT) return shuffled(random)

        // Score-weighted jitter: every query gets a chance proportional to its score,
        // but a generous random jitter lets lower-ranked tags bubble up regularly.
        val maxScore = first().score.coerceAtLeast(0.01)
        return map { query ->
            query to (query.score / maxScore + random.nextDouble() * TAG_SELECTION_JITTER)
        }
            .sortedByDescending { it.second }
            .take(PAGE_TAG_COUNT)
            .map { it.first }
            .shuffled(random)
    }

    private suspend fun buildSectionTasks(
        sources: SourceSelection,
        suggestionQueries: List<SuggestionQuery>,
        currentSortOrder: SuggestionSortOrder,
        includeSourceSection: Boolean,
        pageOffset: Int,
        coldStartDiscovery: Boolean,
        random: Random,
    ): List<SectionTask> {
        return withContext(Dispatchers.Default) {
            buildList {
                if (includeSourceSection) {
                    add(sourceSectionTask(sources, currentSortOrder, pageOffset, coldStartDiscovery, random))
                }
                var lastUsedSourceIds = emptySet<Long>()
                suggestionQueries
                    .shuffled(random)
                    .forEachIndexed { index, suggestionQuery ->
                        val taskSources = sourcesForTask(
                            sources = sources.mixedSources,
                            taskIndex = index + 1,
                            usedInPreviousTask = lastUsedSourceIds,
                        )
                        lastUsedSourceIds = taskSources.map { it.id }.toSet()
                        add(
                            SectionTask(
                                kind = SectionKind.Personalized,
                                reason = suggestionQuery.reason,
                                query = suggestionQuery,
                                sortOrder = currentSortOrder,
                                sources = taskSources,
                                fallbackSources = fallbackSourcesForTask(sources.mixedSources, taskIndex = index + 1),
                                coldStart = false,
                                pageOffset = random.nextInt(PERSONALIZED_PAGE_MIN, PERSONALIZED_PAGE_MAX),
                            ),
                        )
                    }
            }
        }
    }

    private fun sourceSectionTask(
        sources: SourceSelection,
        currentSortOrder: SuggestionSortOrder,
        pageOffset: Int,
        coldStart: Boolean,
        random: Random,
    ): SectionTask {
        return when (currentSortOrder) {
            SuggestionSortOrder.Latest -> SectionTask(
                kind = SectionKind.Latest,
                reason = LATEST_REASON,
                sortOrder = currentSortOrder,
                sources = sources.latestSources.take(MAX_LATEST_SOURCES),
                fallbackSources = sources.latestSources.drop(MAX_LATEST_SOURCES),
                coldStart = coldStart,
                pageOffset = pageOffset,
            )
            SuggestionSortOrder.Popular -> SectionTask(
                kind = SectionKind.Popular,
                reason = POPULAR_REASON,
                sortOrder = currentSortOrder,
                sources = sources.mixedSources.take(MAX_SOURCES_FOR_POPULAR),
                fallbackSources = sources.mixedSources.drop(MAX_SOURCES_FOR_POPULAR),
                coldStart = coldStart,
                pageOffset = pageOffset,
            )
        }
    }

    private suspend fun fetchSection(
        task: SectionTask,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        seenMangaUrls: Set<String>,
        blacklistedTags: Set<String>,
        requestGate: Semaphore,
        random: Random,
    ): List<ScoredManga> {
        if (task.sources.isEmpty()) return emptyList()
        if (task.coldStart) {
            return fetchColdStartSourceSection(
                task = task,
                localKeys = localKeys,
                localTitles = localTitles,
                seenMangaUrls = seenMangaUrls,
                blacklistedTags = blacklistedTags,
                requestGate = requestGate,
                random = random,
            )
        }

        suspend fun fetch(sources: List<CatalogueSource>, offset: Int): List<ScoredManga> {
            if (sources.isEmpty()) return emptyList()
            val subTask = task.copy(sources = sources, pageOffset = offset)
            return when (task.kind) {
                SectionKind.Latest -> fetchLatestSection(subTask, localKeys, localTitles, seenMangaUrls, blacklistedTags, requestGate, random)
                SectionKind.Popular -> fetchPopularSection(subTask, localKeys, localTitles, seenMangaUrls, blacklistedTags, requestGate, random)
                SectionKind.Personalized -> fetchPersonalizedSection(subTask, localKeys, localTitles, seenMangaUrls, blacklistedTags, requestGate, random)
            }
        }

        val results = fetch(task.sources, task.pageOffset).toMutableList()
        val quota = 12

        // Backfill with fallback sources if primary yielded < 12
        if (results.size < quota && task.fallbackSources.isNotEmpty()) {
            for (source in task.fallbackSources) {
                results.addAll(fetch(listOf(source), task.pageOffset))
                if (results.size >= quota) break
            }
        }

        // Backfill with page 2 of primary sources if still < 12
        if (results.size < quota) {
            results.addAll(fetch(task.sources.take(3), task.pageOffset + 1))
        }

        return withContext(Dispatchers.Default) {
            results.bestByTitle()
                .sortedByDescending { it.relevance }
                .take(if (task.kind == SectionKind.Personalized) MAX_PER_AFFINITY_SECTION else MAX_SOURCE_SECTION_TOTAL)
        }
    }

    private suspend fun fetchColdStartSourceSection(
        task: SectionTask,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        seenMangaUrls: Set<String>,
        blacklistedTags: Set<String>,
        requestGate: Semaphore,
        random: Random,
    ): List<ScoredManga> {
        val sources = (task.sources + task.fallbackSources)
            .distinctBy { it.id }
            .shuffled(random)
        val hits = mutableListOf<ScoredManga>()
        for (page in 1..SuggestionsConfig.COLD_START_SOURCE_PAGE_LIMIT) {
            sources
                .withIndex()
                .chunked(SuggestionsConfig.COLD_START_SOURCE_CHUNK_SIZE)
                .forEach { chunk ->
                    val pageHits = coroutineScope {
                        chunk.map { indexedSource ->
                            async {
                                when (task.kind) {
                                    SectionKind.Latest -> fetchLatestFromSource(
                                        source = indexedSource.value,
                                        sourceIndex = indexedSource.index,
                                        page = page,
                                        localKeys = localKeys,
                                        localTitles = localTitles,
                                        seenMangaUrls = seenMangaUrls,
                                        blacklistedTags = blacklistedTags,
                                        requestGate = requestGate,
                                        random = random,
                                    )
                                    SectionKind.Popular -> fetchPopularFromSource(
                                        source = indexedSource.value,
                                        sourceIndex = indexedSource.index,
                                        page = page,
                                        localKeys = localKeys,
                                        localTitles = localTitles,
                                        seenMangaUrls = seenMangaUrls,
                                        blacklistedTags = blacklistedTags,
                                        requestGate = requestGate,
                                        random = random,
                                    )
                                    SectionKind.Personalized -> emptyList()
                                }
                            }
                        }.awaitAll().flatten()
                    }
                    hits.addAll(pageHits)
                }
        }

        return withContext(Dispatchers.Default) {
            hits.bestByTitle()
                .shuffled(random)
                .capBySource(SuggestionsConfig.COLD_START_MAX_PER_SOURCE_FETCH)
                .take(SuggestionsConfig.COLD_START_MAX_CANDIDATES)
        }
    }

    private suspend fun fetchLatestSection(
        task: SectionTask,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        seenMangaUrls: Set<String>,
        blacklistedTags: Set<String>,
        requestGate: Semaphore,
        random: Random,
    ): List<ScoredManga> {
        val page = task.pageOffset.coerceAtLeast(1)
        val hits = coroutineScope {
            task.sources
                .filter { it.supportsLatest }
                .mapIndexed { sourceIndex, source ->
                    async {
                        fetchLatestFromSource(
                            source = source,
                            sourceIndex = sourceIndex,
                            page = page,
                            localKeys = localKeys,
                            localTitles = localTitles,
                            seenMangaUrls = seenMangaUrls,
                            blacklistedTags = blacklistedTags,
                            requestGate = requestGate,
                            random = random,
                        )
                    }
                }
                .awaitAll()
                .flatten()
        }

        return withContext(Dispatchers.Default) {
            hits.bestByTitle()
                .sortedByDescending { it.relevance }
                .capBySource(MAX_PER_SOURCE_PER_SECTION)
                .shuffled(random)
        }
    }

    private suspend fun fetchPopularSection(
        task: SectionTask,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        seenMangaUrls: Set<String>,
        blacklistedTags: Set<String>,
        requestGate: Semaphore,
        random: Random,
    ): List<ScoredManga> {
        val hits = coroutineScope {
            task.sources
                .mapIndexed { sourceIndex, source ->
                    async {
                        fetchPopularFromSource(
                            source = source,
                            sourceIndex = sourceIndex,
                            localKeys = localKeys,
                            localTitles = localTitles,
                            seenMangaUrls = seenMangaUrls,
                            blacklistedTags = blacklistedTags,
                            requestGate = requestGate,
                            random = random,
                        )
                    }
                }
                .awaitAll()
                .flatten()
        }

        return withContext(Dispatchers.Default) {
            hits.bestByTitle()
                .sortedByDescending { it.relevance }
                .capBySource(MAX_PER_SOURCE_PER_SECTION)
                .shuffled(random)
        }
    }

    private suspend fun fetchPersonalizedSection(
        task: SectionTask,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        seenMangaUrls: Set<String>,
        blacklistedTags: Set<String>,
        requestGate: Semaphore,
        random: Random,
    ): List<ScoredManga> {
        val suggestionQuery = task.query ?: return emptyList()
        val page = task.pageOffset.coerceAtLeast(1)
        val hits = coroutineScope {
            task.sources
                .mapIndexed { sourceIndex, source ->
                    async {
                        fetchPersonalizedFromSource(
                            source = source,
                            sourceIndex = sourceIndex,
                            page = page,
                            suggestionQuery = suggestionQuery,
                            sortOrder = task.sortOrder,
                            localKeys = localKeys,
                            localTitles = localTitles,
                            seenMangaUrls = seenMangaUrls,
                            blacklistedTags = blacklistedTags,
                            requestGate = requestGate,
                        )
                    }
                }
                .awaitAll()
                .flatten()
        }

        return withContext(Dispatchers.Default) {
            hits.bestByTitle()
                .sortedByDescending { it.relevance }
                .capBySource(MAX_PER_SOURCE_PER_SECTION)
                .shuffled(random)
        }
    }

    private suspend fun fetchLatestFromSource(
        source: CatalogueSource,
        sourceIndex: Int,
        page: Int = 1,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        seenMangaUrls: Set<String>,
        blacklistedTags: Set<String>,
        requestGate: Semaphore,
        random: Random,
    ): List<ScoredManga> {
        return sourceResult {
            requestGate.withPermit {
                if (source.supportsLatest) source.getLatestUpdates(page) else source.getPopularManga(page)
            }
        }
            ?.mangas
            ?.asSequence()
            ?.filterAllowed(source.id, localKeys, localTitles, seenMangaUrls, blacklistedTags)
            ?.mapIndexed { index, manga ->
                ScoredManga(
                    manga = manga,
                    titleKey = manga.title.normalizedTitle(),
                    sourceId = source.id,
                    relevance = BASE_RELEVANCE - (sourceIndex * SOURCE_PENALTY) - (index * POSITION_PENALTY) + (random.nextDouble() * 0.0001),
                    reason = LATEST_REASON,
                    queryScore = 0.0,
                )
            }
            ?.toList()
            .orEmpty()
    }

    private suspend fun fetchPopularFromSource(
        source: CatalogueSource,
        sourceIndex: Int,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        seenMangaUrls: Set<String>,
        blacklistedTags: Set<String>,
        requestGate: Semaphore,
        page: Int? = null,
        random: Random = Random.Default,
    ): List<ScoredManga> {
        val requestPage = page ?: random.nextInt(POPULAR_PAGE_MIN, POPULAR_PAGE_MAX)
        return sourceResult {
            requestGate.withPermit {
                source.getPopularManga(requestPage)
            }
        }
            ?.mangas
            ?.asSequence()
            ?.filterAllowed(source.id, localKeys, localTitles, seenMangaUrls, blacklistedTags)
            ?.mapIndexed { index, manga ->
                ScoredManga(
                    manga = manga,
                    titleKey = manga.title.normalizedTitle(),
                    sourceId = source.id,
                    relevance = BASE_RELEVANCE - (sourceIndex * SOURCE_PENALTY) - (index * POSITION_PENALTY) + (random.nextDouble() * 0.0001),
                    reason = POPULAR_REASON,
                    queryScore = 0.0,
                )
            }
            ?.toList()
            .orEmpty()
    }

    suspend fun fetchExpandedSection(
        query: String,
        reason: String,
        sortOrder: SuggestionSortOrder,
        seenMangaUrls: Set<String> = emptySet(),
    ): List<SuggestedManga> {
        val random = Random(System.nanoTime())
        val localManga = mangaRepository.getMangaList()
        val localKeys = localManga.map { it.source to it.url }.toSet()
        val localTitles = localManga.map { it.title.normalizedTitle() }.toSet()
        val blacklistedTags = preferences.suggestionsTagsBlacklist().get().normalizedQueries()
        val allSources = sourceManager.getCatalogueSources()
            .shuffled(random)
            .take(MAX_ACTIVE_SOURCES)
        val requestGate = Semaphore(MAX_CONCURRENT_SOURCE_REQUESTS)
        val suggestionQuery = SuggestionQuery(query = query, reason = reason, score = 0.0)
        val page = random.nextInt(EXPANDED_PAGE_MIN, EXPANDED_PAGE_MAX)

        val hits = coroutineScope {
            allSources.mapIndexed { index, source ->
                async {
                    fetchPersonalizedFromSource(
                        source = source,
                        sourceIndex = index,
                        page = page,
                        suggestionQuery = suggestionQuery,
                        sortOrder = sortOrder,
                        localKeys = localKeys,
                        localTitles = localTitles,
                        seenMangaUrls = seenMangaUrls,
                        blacklistedTags = blacklistedTags,
                        requestGate = requestGate,
                        random = random,
                    )
                }
            }.awaitAll().flatten()
        }

        return withContext(Dispatchers.Default) {
            hits
                .groupBy { it.sourceId }
                .flatMap { (_, sourceHits) ->
                    sourceHits.sortedByDescending { it.relevance }.take(MAX_PER_SOURCE_EXPANDED)
                }
                .mapIndexed { index, hit -> hit.toSuggestion(displayRank = index.toLong()) }
        }
    }

    private suspend fun fetchPersonalizedFromSource(
        source: CatalogueSource,
        sourceIndex: Int,
        page: Int = 1,
        suggestionQuery: SuggestionQuery,
        sortOrder: SuggestionSortOrder,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        seenMangaUrls: Set<String>,
        blacklistedTags: Set<String>,
        requestGate: Semaphore,
        random: Random = Random.Default,
    ): List<ScoredManga> {
        return sourceResult {
            requestGate.withPermit {
                source.getSearchManga(page, suggestionQuery.query, source.searchFiltersFor(sortOrder))
            }
        }
            ?.mangas
            ?.asSequence()
            ?.filterAllowed(source.id, localKeys, localTitles, seenMangaUrls, blacklistedTags)
            ?.mapIndexed { index, manga ->
                ScoredManga(
                    manga = manga,
                    titleKey = manga.title.normalizedTitle(),
                    sourceId = source.id,
                    relevance = suggestionQuery.score - (sourceIndex * SOURCE_PENALTY) - (index * POSITION_PENALTY) + (random.nextDouble() * 0.0001),
                    reason = suggestionQuery.reason,
                    queryScore = suggestionQuery.score,
                )
            }
            ?.toList()
            .orEmpty()
    }

    private suspend fun activeSources(
        random: Random,
        includeAllSources: Boolean = false,
    ): SourceSelection {
        val pinnedCatalogueIds = preferences.pinnedCatalogues().get()
        val recentSourceIds = preferences.recentlyUsedSourceIds().get()
        val allSources = sourceManager.getCatalogueSources()
        val pinnedSources = allSources.filter { source -> source.id.toString() in pinnedCatalogueIds }
        val sourceCandidates = if (includeAllSources) {
            allSources
        } else {
            pinnedSources.takeIf { it.isNotEmpty() } ?: allSources
        }
        val limit = when {
            includeAllSources -> Int.MAX_VALUE
            pinnedSources.isNotEmpty() -> MAX_ACTIVE_SOURCES
            else -> FALLBACK_SOURCE_COUNT
        }

        return withContext(Dispatchers.Default) {
            // Deprioritize sources that were used in the previous refresh
            val selectedSources = sourceCandidates
                .sortedBy { if (it.id.toString() in recentSourceIds) 1 else 0 }
                .shuffled(random)
                .take(limit)
            preferences.recentlyUsedSourceIds().set(
                selectedSources.map { it.id.toString() }.toSet(),
            )
            SourceSelection(
                latestSources = selectedSources,
                mixedSources = selectedSources.shuffled(random),
            )
        }
    }

    private fun sourcesForTask(
        sources: List<CatalogueSource>,
        taskIndex: Int,
        usedInPreviousTask: Set<Long> = emptySet(),
    ): List<CatalogueSource> {
        if (sources.isEmpty()) return emptyList()
        val sourceCount = minOf(MAX_SOURCES_PER_SECTION, sources.size)
        // Prefer sources that were NOT used in the previous task to reduce repetition
        val preferred = sources.filterNot { it.id in usedInPreviousTask }
        val pool = (preferred + sources).distinctBy { it.id }
        return List(sourceCount) { offset ->
            pool[(taskIndex + offset) % pool.size]
        }
    }

    private fun fallbackSourcesForTask(sources: List<CatalogueSource>, taskIndex: Int): List<CatalogueSource> {
        val primary = sourcesForTask(sources, taskIndex).toSet()
        return sources.filterNot { it in primary }
    }

    private fun CatalogueSource.searchFiltersFor(sortOrder: SuggestionSortOrder): FilterList {
        val filters = getFilterList()
        val sortFilter = filters.filterIsInstance<Filter.Sort>().firstOrNull() ?: return filters
        val sortIndex = sortFilter.values.indexOfFirst { value -> value.matchesSortOrder(sortOrder) }
        if (sortIndex >= 0) {
            sortFilter.state = Filter.Sort.Selection(index = sortIndex, ascending = false)
        }
        return filters
    }

    private fun String.matchesSortOrder(sortOrder: SuggestionSortOrder): Boolean {
        val value = normalizedQuery()
        return when (sortOrder) {
            SuggestionSortOrder.Latest -> LATEST_SORT_KEYWORDS.any { it in value }
            SuggestionSortOrder.Popular -> POPULAR_SORT_KEYWORDS.any { it in value }
        }
    }

    private fun Sequence<SManga>.filterAllowed(
        sourceId: Long,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        seenMangaUrls: Set<String>,
        blacklistedTags: Set<String>,
    ): Sequence<SManga> {
        return filter { manga -> (sourceId to manga.url) !in localKeys }
            .filterNot { manga -> mangaKey(sourceId, manga.url) in seenMangaUrls }
            .filterNot { manga -> manga.title.normalizedTitle() in localTitles }
            .filterNot { manga -> manga.hasBlacklistedTag(blacklistedTags) }
    }

    private fun SManga.hasBlacklistedTag(blacklistedTags: Set<String>): Boolean {
        if (blacklistedTags.isEmpty()) return false
        return getGenres()
            ?.any { tag -> tag.normalizedQuery() in blacklistedTags }
            ?: false
    }

    private fun Set<String>.normalizedQueries(): Set<String> =
        map { it.normalizedQuery() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun String.normalizedTitle(): String =
        normalizedQuery()

    private fun String.normalizedQuery(): String =
        lowercase().trim().replace(WHITESPACE, " ")

    private suspend fun <T> sourceResult(block: suspend () -> T): T? {
        return try {
            var completed = false
            withTimeoutOrNull(SOURCE_REQUEST_TIMEOUT_MS) {
                block().also { completed = true }
            }.takeIf { completed }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun List<ScoredManga>.bestByTitle(): List<ScoredManga> {
        val bestHits = linkedMapOf<String, ScoredManga>()
        forEach { hit ->
            val key = hit.titleKey
            val existing = bestHits[key]
            if (existing == null || hit.isBetterThan(existing)) {
                bestHits[key] = hit
            }
        }
        return bestHits.values.toList()
    }

    private data class QuerySelection(
        val selectedQueries: List<SuggestionQuery>,
        val selectedNormalizedTags: Set<String>,
        val hasReachedEnd: Boolean,
    )

    private data class SourceSelection(
        val latestSources: List<CatalogueSource>,
        val mixedSources: List<CatalogueSource>,
    ) {
        val isEmpty: Boolean
            get() = latestSources.isEmpty() && mixedSources.isEmpty()
    }

    private data class SectionTask(
        val kind: SectionKind,
        val reason: String,
        val sortOrder: SuggestionSortOrder,
        val sources: List<CatalogueSource>,
        val fallbackSources: List<CatalogueSource>,
        val query: SuggestionQuery? = null,
        val coldStart: Boolean = false,
        val pageOffset: Int = 1,
    )

    private enum class SectionKind {
        Latest,
        Popular,
        Personalized,
    }

    private data class ScoredManga(
        val manga: SManga,
        val titleKey: String,
        val sourceId: Long,
        val relevance: Double,
        val reason: String,
        val queryScore: Double,
    ) {
        fun isBetterThan(other: ScoredManga): Boolean =
            queryScore > other.queryScore ||
                (queryScore == other.queryScore && relevance > other.relevance)

        fun toSuggestion(displayRank: Long): SuggestedManga =
            SuggestedManga(
                source = sourceId,
                url = manga.url,
                title = manga.title,
                thumbnailUrl = manga.thumbnail_url,
                reason = reason,
                relevanceScore = relevance,
                displayRank = displayRank,
            )
    }

    private fun List<ScoredManga>.capBySource(max: Int): List<ScoredManga> {
        val countBySource = mutableMapOf<Long, Int>()
        return filter { hit ->
            val count = countBySource.getOrDefault(hit.sourceId, 0)
            if (count < max) {
                countBySource[hit.sourceId] = count + 1
                true
            } else {
                false
            }
        }
    }

    private companion object {
        private const val MAX_ACTIVE_SOURCES = 10
        private const val FALLBACK_SOURCE_COUNT = 3
        private const val MAX_SOURCES_PER_SECTION = 5
        private const val MAX_SOURCES_FOR_POPULAR = 6
        private const val MAX_LATEST_SOURCES = 4
        private const val PAGE_TAG_COUNT = 4
        private const val TAG_SELECTION_JITTER = 0.25  // Lower = more quality bias, rotation handles variety
        private const val MAX_SOURCE_SECTION_TOTAL = 30
        private const val MAX_PER_AFFINITY_SECTION = 20
        private const val MAX_TOTAL = MAX_SOURCE_SECTION_TOTAL + (PAGE_TAG_COUNT * MAX_PER_AFFINITY_SECTION)
        private const val MAX_CONCURRENT_SOURCE_REQUESTS = 4
        private const val SOURCE_REQUEST_TIMEOUT_MS = 30_000L
        // Source diversity caps
        private const val MAX_PER_SOURCE_PER_SECTION = 5  // per section (latest/popular/personalized)
        private const val MAX_PER_SOURCE_GLOBAL = 8        // across the entire feed page
        private const val MAX_PER_SOURCE_EXPANDED = 5      // for expanded sections
        private const val BASE_RELEVANCE = 1_000_000.0
        private const val SOURCE_PENALTY = 0.001
        private const val POSITION_PENALTY = 0.01
        private const val LATEST_REASON = "Latest from selected sources"
        private const val POPULAR_REASON = "Popular from selected sources"
        // Page-offset ranges for each section type (random.nextInt upper bound is exclusive)
        private const val PERSONALIZED_PAGE_MIN = 1
        private const val PERSONALIZED_PAGE_MAX = 6  // pages 1–5
        private const val POPULAR_PAGE_MIN = 1
        private const val POPULAR_PAGE_MAX = 8        // pages 1–7
        private const val EXPANDED_PAGE_MIN = 1
        private const val EXPANDED_PAGE_MAX = 4       // pages 1–3
        private val LATEST_SORT_KEYWORDS = setOf("latest", "recent", "update", "updated", "uploaded", "date", "new")
        private val POPULAR_SORT_KEYWORDS = setOf("popular", "views", "view", "follow", "follows", "rating", "score", "trend", "hot")
        private val WHITESPACE = Regex("\\s+")

        private fun mangaKey(sourceId: Long, url: String): String = "$sourceId:$url"
    }
}
