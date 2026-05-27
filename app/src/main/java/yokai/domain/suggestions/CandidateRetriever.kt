package yokai.domain.suggestions

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

data class SuggestionCandidate(
    val section: PlannedSection,
    val sourceId: Long,
    val manga: SManga,
    val searchTerm: String?,
    val sourceIndex: Int,
    val position: Int,
)

data class CandidateRetrievalResult(
    val section: PlannedSection,
    val candidates: List<SuggestionCandidate>,
    val isSectionComplete: Boolean = false,
)

class CandidateRetriever(
    private val sourceManager: SourceManager,
    private val preferences: PreferencesHelper,
    private val debugLog: SuggestionsDebugLog,
    private val tagCanonicalizer: TagCanonicalizer,
    private val tagProfileRepository: TagProfileRepository,
    private val networkStatus: SuggestionNetworkStatus = AlwaysOnlineSuggestionNetworkStatus,
    private val catalogueSourcesProvider: () -> List<CatalogueSource> = sourceManager::getCatalogueSources,
    private val catalogueSourcesFlowProvider: () -> Flow<List<CatalogueSource>> = { sourceManager.catalogueSources },
) {
    private fun freshFilterList(source: CatalogueSource): FilterList =
        source.getFilterList()

    fun hasActiveNetworkSources(): Boolean =
        activeNetworkSources().isNotEmpty()

    fun activeNetworkSourceIdsFlow(): Flow<Set<Long>> =
        catalogueSourcesFlowProvider()
            .map { sources ->
                SuggestionSourceSelector.activeNetworkSourceIds(
                    sources = sources,
                    selection = sourceSelection(),
                )
            }
            .distinctUntilChanged()

    suspend fun retrieve(
        sections: List<PlannedSection>,
        pageOffset: Int = 1,
        maxPerSourceFetch: Int? = null,
        globalSeenKeys: Set<String> = emptySet(),
        sectionSeenKeys: Map<String, Set<String>> = emptyMap(),
        sectionTimeoutMs: Long = SuggestionsConfig.SECTION_TIMEOUT_MS,
        sourceCohortSeed: Int = Random.nextInt(),
    ): List<CandidateRetrievalResult> {
        val results = coroutineScope {
            val requestGate = Semaphore(SuggestionsConfig.MAX_CONCURRENT_SOURCE_REQUESTS)
            sections.map { section ->
                async {
                    val candidates = withTimeoutOrNull(sectionTimeoutMs) {
                        retrieveSection(
                            section = section,
                            pageOffset = pageOffset,
                            requestGate = requestGate,
                            maxPerSourceFetch = maxPerSourceFetch,
                            globalSeenKeys = globalSeenKeys,
                            sectionSeenKeys = sectionSeenKeys[section.sectionKey].orEmpty(),
                            sourceCohortSeed = sourceCohortSeed,
                        )
                    }.orEmpty()
                    CandidateRetrievalResult(
                        section = section,
                        candidates = candidates,
                    )
                }
            }.awaitAll()
        }
        val discoverySourceIds = results
            .firstOrNull { it.section.type == SectionType.DISCOVERY }
            ?.candidates
            .orEmpty()
            .map { it.sourceId.toString() }
            .toSet()
        if (discoverySourceIds.isNotEmpty()) {
            preferences.recentlyUsedSourceIds().set(discoverySourceIds)
        }
        return results
    }

    suspend fun retrieveProgressively(
        sections: List<PlannedSection>,
        pageOffset: Int = 1,
        // Per-section page offset override. When provided, each section's fetch
        // starts at a different page so that a single refresh covering multiple
        // tag sections does not pull the same "popular page N" subset for every
        // section. Falls back to the shared [pageOffset] when null so existing
        // call sites stay backward-compatible.
        pageOffsetFor: ((PlannedSection) -> Int)? = null,
        maxPerSourceFetch: Int? = null,
        globalSeenKeys: Set<String> = emptySet(),
        sectionSeenKeys: Map<String, Set<String>> = emptyMap(),
        sourceCohortSeed: Int = Random.nextInt(),
        onResult: suspend (CandidateRetrievalResult) -> Unit,
    ) {
        if (sections.isEmpty()) return
        coroutineScope {
            val requestGate = Semaphore(SuggestionsConfig.MAX_CONCURRENT_SOURCE_REQUESTS)
            val results = Channel<CandidateRetrievalResult>(Channel.UNLIMITED)
            val pendingSections = java.util.concurrent.atomic.AtomicInteger(sections.size)

            sections.forEach { section ->
                launch {
                    val sectionPageOffset = pageOffsetFor?.invoke(section) ?: pageOffset
                    val partial = mutableListOf<SuggestionCandidate>()
                    val complete = withTimeoutOrNull(SuggestionsConfig.SECTION_TIMEOUT_MS) {
                        retrieveSection(
                            section = section,
                            pageOffset = sectionPageOffset,
                            requestGate = requestGate,
                            maxPerSourceFetch = maxPerSourceFetch,
                            globalSeenKeys = globalSeenKeys,
                            sectionSeenKeys = sectionSeenKeys[section.sectionKey].orEmpty(),
                            sourceCohortSeed = sourceCohortSeed,
                            onSourceComplete = { batch ->
                                partial.addAll(batch)
                                results.trySend(
                                    CandidateRetrievalResult(
                                        section = section,
                                        candidates = batch,
                                        isSectionComplete = false,
                                    ),
                                )
                            },
                        )
                    }
                    // complete == null means section timed out — emit partial results as final
                    val finalCandidates = complete ?: partial.distinctBy { it.sourceId to it.manga.url }
                    results.send(
                        CandidateRetrievalResult(
                            section = section,
                            candidates = finalCandidates,
                            isSectionComplete = true,
                        ),
                    )

                    if (pendingSections.decrementAndGet() == 0) {
                        results.close()
                    }
                }
            }

            for (result in results) {
                if (result.section.type == SectionType.DISCOVERY && result.isSectionComplete) {
                    val discoverySourceIds = result.candidates
                        .map { it.sourceId.toString() }
                        .toSet()
                    if (discoverySourceIds.isNotEmpty()) {
                        preferences.recentlyUsedSourceIds().set(discoverySourceIds)
                    }
                }
                onResult(result)
            }
        }
    }

    private suspend fun retrieveSection(
        section: PlannedSection,
        pageOffset: Int,
        requestGate: Semaphore,
        maxPerSourceFetch: Int? = null,
        globalSeenKeys: Set<String> = emptySet(),
        sectionSeenKeys: Set<String> = emptySet(),
        sourceCohortSeed: Int,
        onSourceComplete: (suspend (List<SuggestionCandidate>) -> Unit)? = null,
    ): List<SuggestionCandidate> {
        val blockedMangaKeys = globalSeenKeys + sectionSeenKeys
        if (section.isColdStartDiscovery()) {
            return retrieveColdStartDiscoverySection(section, requestGate, blockedMangaKeys, sourceCohortSeed, onSourceComplete)
        }

        val sources = activeSources(
            discovery = section.type == SectionType.DISCOVERY,
            sourceCohortSeed = sourceCohortSeed,
        )
        if (sources.isEmpty()) return emptyList()

        suspend fun fetchPageProgressive(
            page: Int,
            countBySource: ConcurrentHashMap<Long, AtomicInteger>,
            emit: (suspend (List<SuggestionCandidate>) -> Unit)?,
        ): List<SuggestionCandidate> {
            return coroutineScope {
                val allCandidates = mutableListOf<SuggestionCandidate>()
                val sourceFetches = when (section.type) {
                    SectionType.DISCOVERY ->
                        sources.mapIndexed { sourceIndex, source ->
                            suspend {
                                fetchDiscoverySource(
                                    section,
                                    source,
                                    sourceIndex,
                                    page,
                                    requestGate,
                                    countBySource,
                                    maxPerSourceFetch,
                                    blockedMangaKeys,
                                )
                            }
                        }
                    else ->
                        sources.mapIndexed { sourceIndex, source ->
                            suspend {
                                fetchSearchSource(
                                    section,
                                    source,
                                    sourceIndex,
                                    page,
                                    requestGate,
                                    countBySource,
                                    maxPerSourceFetch,
                                    blockedMangaKeys,
                                )
                            }
                        }
                }
                val targetCandidateCount = targetCandidateCount(section, maxPerSourceFetch)
                for (chunk in sourceFetches.chunked(SuggestionsConfig.MAX_CONCURRENT_SOURCE_REQUESTS)) {
                    val sourceResults = Channel<List<SuggestionCandidate>>(Channel.UNLIMITED)
                    chunk.forEach { fetch ->
                        launch {
                            // Defend against non-Exception throwables (OOM, NoClassDefFoundError,
                            // ExceptionInInitializerError from a corrupt extension). Without this
                            // any sibling source failing with a Throwable would propagate up the
                            // coroutineScope and cancel every other in-flight source fetch,
                            // killing the whole section instead of just the bad source.
                            val batch = try {
                                fetch()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: TransientSuggestionNetworkException) {
                                throw e
                            } catch (_: Throwable) {
                                emptyList()
                            }
                            sourceResults.send(batch)
                        }
                    }
                    repeat(chunk.size) {
                        val batch = sourceResults.receive()
                        if (batch.isNotEmpty() && emit != null) {
                            emit(batch)
                        }
                        allCandidates.addAll(batch)
                    }
                    if (allCandidates.distinctBy { it.sourceId to it.manga.url }.size >= targetCandidateCount) {
                        break
                    }
                }
                allCandidates
            }
        }

        val countBySource = ConcurrentHashMap<Long, AtomicInteger>()
        val page = pageOffset.coerceAtLeast(1)
        val candidates = fetchPageProgressive(page, countBySource, onSourceComplete).toMutableList()

        // ── Source-rotation backfill ───────────────────────────────────────────
        val canOverfillSourceCap = maxPerSourceFetch == null ||
            maxPerSourceFetch > SuggestionsConfig.MANUAL_REFRESH_MAX_PER_SOURCE_FETCH
        if (canOverfillSourceCap && candidates.size < SuggestionsConfig.MAX_RESULTS_PER_SECTION) {
            val drySources = sources.filter { s ->
                (countBySource[s.id]?.get() ?: 0) == 0
            }.toSet()
            val backfillSources = sources.filterNot { it in drySources }
            for (source in backfillSources) {
                val shortfall = SuggestionsConfig.MAX_RESULTS_PER_SECTION - candidates.size
                if (shortfall <= 0) break
                val currentBlockedMangaKeys = blockedMangaKeys + candidates.map { mangaKey(it.sourceId, it.manga.url) }
                val counter = countBySource.getOrPut(source.id) { AtomicInteger(0) }
                val savedCount = counter.get()
                counter.set((savedCount - shortfall).coerceAtLeast(0))
                val topUp = when (section.type) {
                    SectionType.DISCOVERY -> fetchDiscoverySource(
                        section, source, sources.indexOf(source), page, requestGate, countBySource, maxPerSourceFetch, currentBlockedMangaKeys,
                    )
                    else -> fetchSearchSource(
                        section, source, sources.indexOf(source), page, requestGate, countBySource, maxPerSourceFetch, currentBlockedMangaKeys,
                    )
                }
                candidates.addAll(topUp)
                if (topUp.isNotEmpty() && onSourceComplete != null) {
                    onSourceComplete(topUp)
                }
                counter.set(savedCount + topUp.size)
            }
        }
        // ──────────────────────────────────────────────────────────────────────

        // Page-2 backstop. If seen/history filtering leaves the section below the
        // visible target, give each source one bounded chance to replace filtered items.
        if (candidates.distinctBy { it.sourceId to it.manga.url }.size < SuggestionsConfig.MAX_RESULTS_PER_SECTION) {
            val page2Candidates = fetchPageProgressive(page + 1, countBySource, onSourceComplete)
            candidates.addAll(page2Candidates)
        }

        return candidates
            .distinctBy { it.sourceId to it.manga.url }
            .take(SuggestionsConfig.MAX_CANDIDATES_PER_SECTION)
    }

    private suspend fun retrieveColdStartDiscoverySection(
        section: PlannedSection,
        requestGate: Semaphore,
        blockedMangaKeys: Set<String>,
        sourceCohortSeed: Int,
        onSourceComplete: (suspend (List<SuggestionCandidate>) -> Unit)?,
    ): List<SuggestionCandidate> {
        var sources = activeSources(discovery = true, sourceCohortSeed = sourceCohortSeed)
        if (sources.isEmpty()) {
            // SourceManager's IO coroutine may not have finished populating the map yet.
            // Wait for the live Flow to emit at least one source before giving up.
            debugLog.add(LogType.SECTION_DROPPED, "Cold-start: no sources in snapshot — waiting up to ${SuggestionsConfig.SOURCE_POPULATION_TIMEOUT_MS}ms for source map")
            withTimeoutOrNull(SuggestionsConfig.SOURCE_POPULATION_TIMEOUT_MS) {
                catalogueSourcesFlowProvider().first { it.isNotEmpty() }
            }
            sources = activeSources(discovery = true, sourceCohortSeed = sourceCohortSeed)
            debugLog.add(LogType.SECTION_DROPPED, "Cold-start: after wait — ${sources.size} sources available")
        }
        if (sources.isEmpty()) return emptyList()

        val countBySource = ConcurrentHashMap<Long, AtomicInteger>()
        val candidates = mutableListOf<SuggestionCandidate>()
        val indexedSources = sources.withIndex().toList()
        pages@ for (page in 1..SuggestionsConfig.COLD_START_SOURCE_PAGE_LIMIT) {
            val pageCandidates = coroutineScope {
                val sourceResults = Channel<List<SuggestionCandidate>>(Channel.UNLIMITED)
                indexedSources.forEach { indexedSource ->
                    launch {
                        val batch = try {
                            fetchDiscoverySource(
                                section = section,
                                source = indexedSource.value,
                                sourceIndex = indexedSource.index,
                                page = page,
                                requestGate = requestGate,
                                countBySource = countBySource,
                                blockedMangaKeys = blockedMangaKeys,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: TransientSuggestionNetworkException) {
                            throw e
                        } catch (_: Throwable) {
                            // Bad cold-start source -> empty batch instead of cancelling siblings.
                            emptyList()
                        }
                        sourceResults.send(batch)
                    }
                }

                val pageCandidates = mutableListOf<SuggestionCandidate>()
                repeat(indexedSources.size) {
                    val batch = sourceResults.receive()
                    if (batch.isNotEmpty()) {
                        pageCandidates.addAll(batch)
                        onSourceComplete?.invoke(batch)
                    }
                }
                pageCandidates
            }
            candidates.addAll(pageCandidates)
            // Check after full page so every source gets at least one page queried.
            if (candidates.distinctBy { it.sourceId to it.manga.url }.size >= SuggestionsConfig.COLD_START_EARLY_BAILOUT_CANDIDATES) {
                break@pages
            }
        }

        return candidates
            .distinctBy { it.sourceId to it.manga.url }
            .shuffled()
            .take(SuggestionsConfig.COLD_START_MAX_CANDIDATES)
    }

    private suspend fun fetchDiscoverySource(
        section: PlannedSection,
        source: CatalogueSource,
        sourceIndex: Int,
        page: Int,
        requestGate: Semaphore,
        countBySource: ConcurrentHashMap<Long, AtomicInteger>,
        maxPerSourceFetch: Int? = null,
        blockedMangaKeys: Set<String> = emptySet(),
    ): List<SuggestionCandidate> {
        val pageResult = requestGate.withPermit {
            when (section.sortOrder) {
                SuggestionSortOrder.Latest -> {
                    val latest = if (source.supportsLatest) {
                        sourceResult(sourceId = source.id) {
                            source.getLatestUpdates(page)
                        }
                    } else {
                        null
                    }
                    latest?.takeIf { it.mangas.isNotEmpty() }
                        ?: sourceResult(sourceId = source.id) {
                            source.getPopularManga(page)
                        }
                }
                SuggestionSortOrder.Popular ->
                    sourceResult(sourceId = source.id) {
                        source.getPopularManga(page)
                    }
                }
        } ?: return emptyList()

        val candidates = cappedCandidates(
            section = section,
            sourceId = source.id,
            sourceIndex = sourceIndex,
            searchTerm = null,
            mangas = pageResult.mangas,
            countBySource = countBySource,
            maxPerSourceFetch = maxPerSourceFetch,
            blockedMangaKeys = blockedMangaKeys,
        )
        learnVocabulary(candidates, source.id)
        return candidates
    }

    /**
     * Fetch candidates for a tag section from a single source.
     *
     * Strategy (Phase B):
     * 1. Try to inject the tag into the source's native tag/genre filters via
     *    [tryIncludeTagFilter]. If a match is found, call `getSearchManga(query="", filters)`.
     *    This uses the source's exact internal genre ID — no vocabulary mismatch possible.
     * 2. Fallback: look up the exact raw string this source has used before for the
     *    canonical tag via [TagProfileRepository.getExactTermForSource].
     * 3. Last resort: use the canonical tag itself as the query string.
     *
     * In all cases: exactly **one** network call per source per section (Phase C).
     */
    private suspend fun fetchSearchSource(
        section: PlannedSection,
        source: CatalogueSource,
        sourceIndex: Int,
        page: Int,
        requestGate: Semaphore,
        countBySource: ConcurrentHashMap<Long, AtomicInteger>,
        maxPerSourceFetch: Int? = null,
        blockedMangaKeys: Set<String> = emptySet(),
    ): List<SuggestionCandidate> {
        val canonicalTag = section.canonicalTag

        // ── Phase A: tag/genre filter injection ──────────────────────────────────
        val injectedFilters = canonicalTag?.let { tag ->
            sourceResult(sourceId = source.id) {
                source.tryIncludeTagFilter(tag, tagCanonicalizer)
            }
        }

        val query: String
        val filters = if (injectedFilters != null) {
            // SUCCESS: source has a native tag/genre filter for this tag - empty query, filter checked.
            debugLog.add(
                LogType.SECTION_SELECTED,
                "Source ${source.id} (${source.name}): tag filter injected for '$canonicalTag'",
            )
            query = ""
            injectedFilters
        } else {
            // FALLBACK: no native tag checkbox - use source vocabulary, then aliases, then canonical key.
            val exactTerm = canonicalTag?.let {
                tagProfileRepository.getExactTermForSource(it, source.id)
            }
            query = exactTerm
                ?: section.searchTerms.firstOrNull()
                ?: canonicalTag
                ?: return emptyList()
            if (exactTerm == null && canonicalTag != null) {
                debugLog.add(
                    LogType.SORT_FALLBACK,
                    "Source ${source.id} (${source.name}): no tag filter for '$canonicalTag' - text search with '$query'",
                )
            }
            freshFilterList(source)
        }
        filters.tryApplySuggestionSort(section.sortOrder)
        val pageResult = requestGate.withPermit {
            sourceResult(sourceId = source.id) {
                source.getSearchManga(page, query, filters)
            }
        } ?: return emptyList()
        // ─────────────────────────────────────────────────────────────────────────

        val candidates = cappedCandidates(
            section = section,
            sourceId = source.id,
            sourceIndex = sourceIndex,
            searchTerm = query,
            mangas = pageResult.mangas,
            countBySource = countBySource,
            maxPerSourceFetch = maxPerSourceFetch,
            blockedMangaKeys = blockedMangaKeys,
        )

        // ── Phase: vocabulary learning ────────────────────────────────────────────
        // After a successful fetch, persist each (sourceId, rawGenreString → canonicalTag)
        // so future fallback text-searches use the source's own vocabulary.
        learnVocabulary(candidates, source.id)
        // ─────────────────────────────────────────────────────────────────────────

        return candidates
    }

    /**
     * Record genre strings returned by [sourceId] into the alias table so future fallback
     * text-searches can use the exact string this source understands.
     * Fire-and-forget: any DB error is swallowed to never interrupt the fetch path.
     */
    private suspend fun learnVocabulary(candidates: List<SuggestionCandidate>, sourceId: Long) {
        if (candidates.isEmpty()) return
        try {
            val batch = mutableListOf<Triple<String, String, Long>>()
            candidates.forEach { candidate ->
                candidate.manga.getGenres()?.forEach { rawTag ->
                    val canonical = tagCanonicalizer.canonicalize(rawTag, sourceId).canonicalKey
                    if (canonical.isNotBlank()) {
                        batch.add(Triple(rawTag, canonical, sourceId))
                    }
                }
            }
            if (batch.isNotEmpty()) {
                tagProfileRepository.recordSourceVocabularyBatch(batch)
            }
        } catch (e: Exception) {
            debugLog.add(
                LogType.SECTION_DROPPED,
                "learnVocabulary DB write failed for source $sourceId: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun cappedCandidates(
        section: PlannedSection,
        sourceId: Long,
        sourceIndex: Int,
        searchTerm: String?,
        mangas: List<SManga>,
        countBySource: ConcurrentHashMap<Long, AtomicInteger>,
        maxPerSourceFetch: Int? = null,
        blockedMangaKeys: Set<String> = emptySet(),
    ): List<SuggestionCandidate> {
        val counter = countBySource.getOrPut(sourceId) { AtomicInteger(0) }
        val maxPerSource = if (section.isColdStartDiscovery()) {
            SuggestionsConfig.COLD_START_MAX_PER_SOURCE_FETCH
        } else {
            maxPerSourceFetch ?: SuggestionsConfig.MAX_PER_SOURCE_FETCH
        }
        val remaining = maxPerSource - counter.get()
        if (remaining <= 0) {
            debugLog.add(LogType.SOURCE_CAP_HIT, "Source $sourceId capped at $maxPerSource candidates for section '${section.sectionKey}'")
            return emptyList()
        }

        val filteredMangas = mangas
            .withIndex()
            .filterNot { indexedManga -> mangaKey(sourceId, indexedManga.value.url) in blockedMangaKeys }
        val filteredCount = mangas.size - filteredMangas.size
        if (filteredCount > 0) {
            debugLog.add(
                LogType.ITEM_FILTERED,
                "Source $sourceId filtered $filteredCount seen candidates before cap for section '${section.sectionKey}'",
            )
        }

        val selected = filteredMangas.take(remaining)
        val newCount = counter.addAndGet(selected.size)
        if (newCount >= maxPerSource && filteredMangas.size > selected.size) {
            debugLog.add(LogType.SOURCE_CAP_HIT, "Source $sourceId capped at $maxPerSource candidates for section '${section.sectionKey}'")
        }

        return selected.map { indexedManga ->
            SuggestionCandidate(
                section = section,
                sourceId = sourceId,
                manga = indexedManga.value,
                searchTerm = searchTerm,
                sourceIndex = sourceIndex,
                position = indexedManga.index,
            )
        }
    }

    private fun activeSources(discovery: Boolean, sourceCohortSeed: Int): List<CatalogueSource> =
        activeNetworkSources(
            discovery = discovery,
            freshSourceFirst = true,
            maxSources = SuggestionsConfig.MAIN_FEED_SOURCE_COHORT_SIZE,
            freshSourceSeed = sourceCohortSeed,
        )

    private fun activeNetworkSources(
        discovery: Boolean = false,
        freshSourceFirst: Boolean = false,
        maxSources: Int = SuggestionsConfig.MAX_ACTIVE_SOURCES,
        freshSourceSeed: Int? = null,
    ): List<CatalogueSource> =
        SuggestionSourceSelector.activeNetworkSources(
            sources = catalogueSourcesProvider(),
            selection = sourceSelection(),
            discovery = discovery,
            maxSources = maxSources,
            freshSourceFirst = freshSourceFirst,
            freshSourceSeed = freshSourceSeed,
        )

    private fun sourceSelection(): SuggestionSourceSelection =
        SuggestionSourceSelection(
            enabledLanguages = preferences.enabledLanguages().get(),
            hiddenSourceIds = preferences.hiddenSources().get(),
            pinnedSourceIds = preferences.pinnedCatalogues().get(),
            recentSourceIds = preferences.recentlyUsedSourceIds().get(),
            lastFetchedSourceIds = preferences.lastFetchedSuggestionsSourceIds().get(),
        )

    private fun targetCandidateCount(section: PlannedSection, maxPerSourceFetch: Int?): Int =
        when {
            section.isColdStartDiscovery() -> SuggestionsConfig.COLD_START_EARLY_BAILOUT_CANDIDATES
            maxPerSourceFetch != null && maxPerSourceFetch <= SuggestionsConfig.MANUAL_REFRESH_MAX_PER_SOURCE_FETCH ->
                SuggestionsConfig.MAX_RESULTS_PER_SECTION
            else -> SuggestionsConfig.MAX_RESULTS_PER_SECTION * 2
        }

    private fun mangaKey(sourceId: Long, url: String): String = "$sourceId:$url"

    private suspend fun <T> sourceResult(sourceId: Long? = null, block: suspend () -> T): T? =
        try {
            var completed = false
            val result = withTimeoutOrNull(SuggestionsConfig.SOURCE_REQUEST_TIMEOUT_MS) {
                block().also { completed = true }
            }
            if (!completed && sourceId != null) {
                debugLog.add(
                    LogType.SECTION_DROPPED,
                    "Source $sourceId timed out after ${SuggestionsConfig.SOURCE_REQUEST_TIMEOUT_MS}ms",
                )
                if (!networkStatus.isOnline()) {
                    throw TransientSuggestionNetworkException(
                        java.io.IOException("Network unavailable while waiting for source $sourceId"),
                    )
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransientSuggestionNetworkException) {
            throw e
        } catch (e: Throwable) {
            if (e.isTransientSuggestionNetworkFailure(networkStatus.isOnline())) {
                if (sourceId != null) {
                    debugLog.add(
                        LogType.SECTION_DROPPED,
                        "Network interrupted while fetching source $sourceId: ${e.javaClass.simpleName}: ${e.message}",
                    )
                }
                throw TransientSuggestionNetworkException(e)
            }
            // Log individual source failures to the debug log (Bug 8a). Catching Throwable
            // (not just Exception) keeps a corrupt extension that throws e.g. NoClassDefFoundError
            // from killing every other source in the same coroutineScope.
            if (sourceId != null) {
                debugLog.add(
                    LogType.SECTION_DROPPED,
                    "Source $sourceId network error: ${e.javaClass.simpleName}: ${e.message}",
                )
            }
            null
        }

}
