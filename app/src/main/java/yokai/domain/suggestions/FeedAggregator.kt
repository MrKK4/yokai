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
        ).suggestions
    }

    suspend fun fetchPage(
        suggestionQueries: List<SuggestionQuery>,
        usedTags: Set<String>,
        seenMangaUrls: Set<String>,
        currentSortOrder: SuggestionSortOrder,
        includeSourceSection: Boolean,
    ): SuggestionFeedPage {
        val random = Random(System.nanoTime())
        val localManga = mangaRepository.getMangaList()
        val (localKeys, localTitles) = withContext(Dispatchers.Default) {
            localManga.map { it.source to it.url }.toSet() to
                localManga.map { it.title.normalizedTitle() }.toSet()
        }
        val blacklistedTags = preferences.suggestionsTagsBlacklist().get().normalizedQueries()
        val normalizedSeenMangaUrls = seenMangaUrls.toSet()
        val querySelection = selectQueriesForPage(
            suggestionQueries = suggestionQueries,
            usedTags = usedTags.normalizedQueries(),
            blacklistedTags = blacklistedTags,
            random = random,
        )
        val sources = activeSources(random)
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
                .capBySource(MAX_PER_SOURCE_GLOBAL)
                .take(MAX_TOTAL)
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
            val remainingQueries = suggestionQueries
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

        val highRank = take(HIGH_BUCKET_SIZE)
        val midRank = drop(HIGH_BUCKET_SIZE).take(MID_BUCKET_SIZE)
        val lowRank = drop(HIGH_BUCKET_SIZE + MID_BUCKET_SIZE)
        val selected = mutableListOf<SuggestionQuery>()

        selected.addFrom(highRank.shuffled(random), count = 1)
        selected.addFrom(midRank.shuffled(random), count = 1)
        selected.addFrom(lowRank.shuffled(random), count = PAGE_TAG_COUNT - selected.size)
        selected.addFrom(shuffled(random), count = PAGE_TAG_COUNT - selected.size)

        return selected
            .take(PAGE_TAG_COUNT)
            .shuffled(random)
    }

    private fun MutableList<SuggestionQuery>.addFrom(candidates: List<SuggestionQuery>, count: Int) {
        if (count <= 0) return
        candidates
            .filterNot { it in this }
            .take(count)
            .forEach(::add)
    }

    private suspend fun buildSectionTasks(
        sources: SourceSelection,
        suggestionQueries: List<SuggestionQuery>,
        currentSortOrder: SuggestionSortOrder,
        includeSourceSection: Boolean,
        random: Random,
    ): List<SectionTask> {
        return withContext(Dispatchers.Default) {
            buildList {
                if (includeSourceSection) {
                    add(sourceSectionTask(sources, currentSortOrder))
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
                            ),
                        )
                    }
            }
        }
    }

    private fun sourceSectionTask(
        sources: SourceSelection,
        currentSortOrder: SuggestionSortOrder,
    ): SectionTask {
        return when (currentSortOrder) {
            SuggestionSortOrder.Latest -> SectionTask(
                kind = SectionKind.Latest,
                reason = LATEST_REASON,
                sortOrder = currentSortOrder,
                sources = sources.latestSources.take(MAX_LATEST_SOURCES),
                fallbackSources = sources.latestSources.drop(MAX_LATEST_SOURCES),
            )
            SuggestionSortOrder.Popular -> SectionTask(
                kind = SectionKind.Popular,
                reason = POPULAR_REASON,
                sortOrder = currentSortOrder,
                sources = sources.mixedSources.take(MAX_SOURCES_FOR_POPULAR),
                fallbackSources = sources.mixedSources.drop(MAX_SOURCES_FOR_POPULAR),
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

        val primaryResults = when (task.kind) {
            SectionKind.Latest -> fetchLatestSection(task, localKeys, localTitles, seenMangaUrls, blacklistedTags, requestGate)
            SectionKind.Popular -> fetchPopularSection(task, localKeys, localTitles, seenMangaUrls, blacklistedTags, requestGate, random)
            SectionKind.Personalized -> fetchPersonalizedSection(task, localKeys, localTitles, seenMangaUrls, blacklistedTags, requestGate, random)
        }
        if (primaryResults.isNotEmpty() || task.fallbackSources.isEmpty()) return primaryResults

        val fallbackTask = task.copy(sources = task.fallbackSources, fallbackSources = emptyList())
        return when (fallbackTask.kind) {
            SectionKind.Latest -> fetchLatestSection(fallbackTask, localKeys, localTitles, seenMangaUrls, blacklistedTags, requestGate)
            SectionKind.Popular -> fetchPopularSection(fallbackTask, localKeys, localTitles, seenMangaUrls, blacklistedTags, requestGate, random)
            SectionKind.Personalized -> fetchPersonalizedSection(fallbackTask, localKeys, localTitles, seenMangaUrls, blacklistedTags, requestGate, random)
        }
    }

    private suspend fun fetchLatestSection(
        task: SectionTask,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        seenMangaUrls: Set<String>,
        blacklistedTags: Set<String>,
        requestGate: Semaphore,
    ): List<ScoredManga> {
        val hits = coroutineScope {
            task.sources
                .filter { it.supportsLatest }
                .mapIndexed { sourceIndex, source ->
                    async {
                        fetchLatestFromSource(
                            source = source,
                            sourceIndex = sourceIndex,
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
                .take(MAX_SOURCE_SECTION_TOTAL)
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
                .take(MAX_SOURCE_SECTION_TOTAL)
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
        val hits = coroutineScope {
            task.sources
                .mapIndexed { sourceIndex, source ->
                    async {
                        fetchPersonalizedFromSource(
                            source = source,
                            sourceIndex = sourceIndex,
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
                .take(MAX_PER_AFFINITY_SECTION)
                .shuffled(random)
        }
    }

    private suspend fun fetchLatestFromSource(
        source: CatalogueSource,
        sourceIndex: Int,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        seenMangaUrls: Set<String>,
        blacklistedTags: Set<String>,
        requestGate: Semaphore,
    ): List<ScoredManga> {
        return sourceResult {
            requestGate.withPermit {
                source.getLatestUpdates(1)
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
                    relevance = BASE_RELEVANCE - (sourceIndex * SOURCE_PENALTY) - (index * POSITION_PENALTY),
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
        random: Random = Random.Default,
    ): List<ScoredManga> {
        val page = random.nextInt(1, 4)
        return sourceResult {
            requestGate.withPermit {
                source.getPopularManga(page)
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
                    relevance = BASE_RELEVANCE - (sourceIndex * SOURCE_PENALTY) - (index * POSITION_PENALTY),
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

        val hits = coroutineScope {
            allSources.mapIndexed { index, source ->
                async {
                    fetchPersonalizedFromSource(
                        source = source,
                        sourceIndex = index,
                        suggestionQuery = suggestionQuery,
                        sortOrder = sortOrder,
                        localKeys = localKeys,
                        localTitles = localTitles,
                        seenMangaUrls = emptySet(),
                        blacklistedTags = blacklistedTags,
                        requestGate = requestGate,
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
        suggestionQuery: SuggestionQuery,
        sortOrder: SuggestionSortOrder,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        seenMangaUrls: Set<String>,
        blacklistedTags: Set<String>,
        requestGate: Semaphore,
    ): List<ScoredManga> {
        return sourceResult {
            requestGate.withPermit {
                source.getSearchManga(1, suggestionQuery.query, source.searchFiltersFor(sortOrder))
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
                    relevance = suggestionQuery.score - (sourceIndex * SOURCE_PENALTY) - (index * POSITION_PENALTY),
                    reason = suggestionQuery.reason,
                    queryScore = suggestionQuery.score,
                )
            }
            ?.toList()
            .orEmpty()
    }

    private suspend fun activeSources(random: Random): SourceSelection {
        val pinnedCatalogueIds = preferences.pinnedCatalogues().get()
        val allSources = sourceManager.getCatalogueSources()
        val pinnedSources = allSources.filter { source -> source.id.toString() in pinnedCatalogueIds }
        val sourceCandidates = pinnedSources.takeIf { it.isNotEmpty() } ?: allSources
        val limit = if (pinnedSources.isNotEmpty()) MAX_ACTIVE_SOURCES else FALLBACK_SOURCE_COUNT

        return withContext(Dispatchers.Default) {
            val selectedSources = sourceCandidates.shuffled(random).take(limit)
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
            block()
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
        private const val PAGE_TAG_COUNT = 2
        private const val HIGH_BUCKET_SIZE = 5
        private const val MID_BUCKET_SIZE = 5
        private const val MAX_SOURCE_SECTION_TOTAL = 30
        private const val MAX_PER_AFFINITY_SECTION = 20
        private const val MAX_TOTAL = MAX_SOURCE_SECTION_TOTAL + (PAGE_TAG_COUNT * MAX_PER_AFFINITY_SECTION)
        private const val MAX_CONCURRENT_SOURCE_REQUESTS = 4
        // Source diversity caps
        private const val MAX_PER_SOURCE_PER_SECTION = 5  // per section (latest/popular/personalized)
        private const val MAX_PER_SOURCE_GLOBAL = 8        // across the entire feed page
        private const val MAX_PER_SOURCE_EXPANDED = 5      // for expanded sections
        private const val BASE_RELEVANCE = 1_000_000.0
        private const val SOURCE_PENALTY = 0.001
        private const val POSITION_PENALTY = 0.01
        private const val LATEST_REASON = "Latest from selected sources"
        private const val POPULAR_REASON = "Popular from selected sources"
        private val LATEST_SORT_KEYWORDS = setOf("latest", "recent", "update", "updated", "uploaded", "date", "new")
        private val POPULAR_SORT_KEYWORDS = setOf("popular", "views", "view", "follow", "follows", "rating", "score", "trend", "hot")
        private val WHITESPACE = Regex("\\s+")

        private fun mangaKey(sourceId: Long, url: String): String = "$sourceId:$url"
    }
}
