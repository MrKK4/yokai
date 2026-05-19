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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

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
) {
    private fun freshFilterList(source: CatalogueSource): FilterList =
        source.getFilterList()

    fun hasActiveNetworkSources(): Boolean =
        activeNetworkSources().isNotEmpty()

    fun activeNetworkSourceIdsFlow(): Flow<Set<Long>> =
        sourceManager.catalogueSources
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
    ): List<CandidateRetrievalResult> {
        val results = coroutineScope {
            val requestGate = Semaphore(SuggestionsConfig.MAX_CONCURRENT_SOURCE_REQUESTS)
            sections.map { section ->
                async {
                    CandidateRetrievalResult(
                        section = section,
                        candidates = retrieveSection(section, pageOffset, requestGate),
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
        onResult: suspend (CandidateRetrievalResult) -> Unit,
    ) {
        if (sections.isEmpty()) return
        coroutineScope {
            val requestGate = Semaphore(SuggestionsConfig.MAX_CONCURRENT_SOURCE_REQUESTS)
            val results = Channel<CandidateRetrievalResult>(Channel.UNLIMITED)
            val pendingSections = java.util.concurrent.atomic.AtomicInteger(sections.size)

            sections.forEach { section ->
                launch {
                    val allCandidates = retrieveSection(
                        section = section,
                        pageOffset = pageOffset,
                        requestGate = requestGate,
                        onSourceComplete = { batch ->
                            results.send(
                                CandidateRetrievalResult(
                                    section = section,
                                    candidates = batch,
                                    isSectionComplete = false,
                                ),
                            )
                        },
                    )

                    // Signal section completion
                    results.send(
                        CandidateRetrievalResult(
                            section = section,
                            candidates = allCandidates,
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
        onSourceComplete: (suspend (List<SuggestionCandidate>) -> Unit)? = null,
    ): List<SuggestionCandidate> {
        if (section.isColdStartDiscovery()) {
            return retrieveColdStartDiscoverySection(section, requestGate)
        }

        val sources = activeSources(discovery = section.type == SectionType.DISCOVERY)
        if (sources.isEmpty()) return emptyList()

        suspend fun fetchPageProgressive(
            page: Int,
            countBySource: ConcurrentHashMap<Long, AtomicInteger>,
            emit: (suspend (List<SuggestionCandidate>) -> Unit)?,
        ): List<SuggestionCandidate> {
            return coroutineScope {
                val allCandidates = mutableListOf<SuggestionCandidate>()
                when (section.type) {
                    SectionType.DISCOVERY ->
                        sources.mapIndexed { sourceIndex, source ->
                            async { fetchDiscoverySource(section, source, sourceIndex, page, requestGate, countBySource) }
                        }
                    else ->
                        sources.mapIndexed { sourceIndex, source ->
                            async { fetchSearchSource(section, source, sourceIndex, page, requestGate, countBySource) }
                        }
                }.forEach { job ->
                    val batch = job.await()
                    if (batch.isNotEmpty() && emit != null) {
                        emit(batch)
                    }
                    allCandidates.addAll(batch)
                }
                allCandidates
            }
        }

        val countBySource = ConcurrentHashMap<Long, AtomicInteger>()
        val page = pageOffset.coerceAtLeast(1)
        val candidates = fetchPageProgressive(page, countBySource, onSourceComplete).toMutableList()

        // ── Source-rotation backfill ───────────────────────────────────────────
        if (candidates.size < SuggestionsConfig.MIN_RESULTS_PER_SECTION) {
            val drySources = sources.filter { s ->
                (countBySource[s.id]?.get() ?: 0) == 0
            }.toSet()
            val backfillSources = sources.filterNot { it in drySources }
            for (source in backfillSources) {
                val shortfall = SuggestionsConfig.MIN_RESULTS_PER_SECTION - candidates.size
                if (shortfall <= 0) break
                val counter = countBySource.getOrPut(source.id) { AtomicInteger(0) }
                val savedCount = counter.get()
                counter.set((savedCount - shortfall).coerceAtLeast(0))
                val topUp = when (section.type) {
                    SectionType.DISCOVERY -> fetchDiscoverySource(
                        section, source, sources.indexOf(source), page, requestGate, countBySource,
                    )
                    else -> fetchSearchSource(
                        section, source, sources.indexOf(source), page, requestGate, countBySource,
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

        // Page-2 backstop
        if (candidates.size < SuggestionsConfig.MIN_RESULTS_PER_SECTION) {
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
    ): List<SuggestionCandidate> {
        val sources = activeSources(discovery = true)
            .shuffled()
        if (sources.isEmpty()) return emptyList()

        val countBySource = ConcurrentHashMap<Long, AtomicInteger>()
        val candidates = mutableListOf<SuggestionCandidate>()
        val indexedSources = sources.withIndex().toList()
        pages@ for (page in 1..SuggestionsConfig.COLD_START_SOURCE_PAGE_LIMIT) {
            for (chunk in indexedSources.chunked(SuggestionsConfig.COLD_START_SOURCE_CHUNK_SIZE)) {
                val pageCandidates = coroutineScope {
                    chunk.map { indexedSource ->
                        async {
                            fetchDiscoverySource(
                                section = section,
                                source = indexedSource.value,
                                sourceIndex = indexedSource.index,
                                page = page,
                                requestGate = requestGate,
                                countBySource = countBySource,
                            )
                        }
                    }.awaitAll().flatten()
                }
                candidates.addAll(pageCandidates)
                if (candidates.distinctBy { it.sourceId to it.manga.url }.size >= SuggestionsConfig.COLD_START_EARLY_BAILOUT_CANDIDATES) {
                    break@pages
                }
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
    ): List<SuggestionCandidate> {
        val pageResult = requestGate.withPermit {
            sourceResult(sourceId = source.id) {
                when (section.sortOrder) {
                    SuggestionSortOrder.Latest ->
                        if (source.supportsLatest) source.getLatestUpdates(page) else source.getPopularManga(page)
                    SuggestionSortOrder.Popular -> source.getPopularManga(page)
                }
            }
        } ?: return emptyList()

        val candidates = cappedCandidates(section, source.id, sourceIndex, null, pageResult.mangas, countBySource)
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
    ): List<SuggestionCandidate> {
        val canonicalTag = section.canonicalTag

        // ── Phase A: tag/genre filter injection ──────────────────────────────────
        val injectedFilters = canonicalTag?.let { tag ->
            sourceResult(sourceId = source.id) {
                source.tryIncludeTagFilter(tag, tagCanonicalizer)
            }
        }

        val pageResult = if (injectedFilters != null) {
            // SUCCESS: source has a native tag/genre filter for this tag - empty query, filter checked.
            debugLog.add(
                LogType.SECTION_SELECTED,
                "Source ${source.id} (${source.name}): tag filter injected for '$canonicalTag'",
            )
            requestGate.withPermit {
                sourceResult(sourceId = source.id) {
                    source.getSearchManga(page, query = "", injectedFilters)
                }
            }
        } else {
            // FALLBACK: no native tag checkbox - use source vocabulary, then aliases, then canonical key.
            val exactTerm = canonicalTag?.let {
                tagProfileRepository.getExactTermForSource(it, source.id)
            }
            val query = exactTerm
                ?: section.searchTerms.firstOrNull()
                ?: canonicalTag
                ?: return emptyList()
            if (exactTerm == null && canonicalTag != null) {
                debugLog.add(
                    LogType.SORT_FALLBACK,
                    "Source ${source.id} (${source.name}): no tag filter for '$canonicalTag' - text search with '$query'",
                )
            }
            requestGate.withPermit {
                sourceResult(sourceId = source.id) {
                    source.getSearchManga(page, query, freshFilterList(source))
                }
            }
        } ?: return emptyList()
        // ─────────────────────────────────────────────────────────────────────────

        val candidates = cappedCandidates(
            section,
            source.id,
            sourceIndex,
            searchTerm = injectedFilters?.let { "" } ?: (section.searchTerms.firstOrNull() ?: canonicalTag),
            pageResult.mangas,
            countBySource,
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
    ): List<SuggestionCandidate> {
        val counter = countBySource.getOrPut(sourceId) { AtomicInteger(0) }
        val maxPerSource = if (section.isColdStartDiscovery()) {
            SuggestionsConfig.COLD_START_MAX_PER_SOURCE_FETCH
        } else {
            SuggestionsConfig.MAX_PER_SOURCE_FETCH
        }
        val remaining = maxPerSource - counter.get()
        if (remaining <= 0) {
            debugLog.add(LogType.SOURCE_CAP_HIT, "Source $sourceId capped at $maxPerSource candidates for section '${section.sectionKey}'")
            return emptyList()
        }

        val selected = mangas.take(remaining)
        val newCount = counter.addAndGet(selected.size)
        if (newCount >= maxPerSource && mangas.size > selected.size) {
            debugLog.add(LogType.SOURCE_CAP_HIT, "Source $sourceId capped at $maxPerSource candidates for section '${section.sectionKey}'")
        }

        return selected.mapIndexed { index, manga ->
            SuggestionCandidate(
                section = section,
                sourceId = sourceId,
                manga = manga,
                searchTerm = searchTerm,
                sourceIndex = sourceIndex,
                position = index,
            )
        }
    }

    private fun activeSources(discovery: Boolean): List<CatalogueSource> =
        activeNetworkSources(discovery, freshSourceFirst = !discovery)

    private fun activeNetworkSources(discovery: Boolean = false, freshSourceFirst: Boolean = false): List<CatalogueSource> =
        SuggestionSourceSelector.activeNetworkSources(
            sources = sourceManager.getCatalogueSources(),
            selection = sourceSelection(),
            discovery = discovery,
            freshSourceFirst = freshSourceFirst,
        )

    private fun sourceSelection(): SuggestionSourceSelection =
        SuggestionSourceSelection(
            enabledLanguages = preferences.enabledLanguages().get(),
            hiddenSourceIds = preferences.hiddenSources().get(),
            pinnedSourceIds = preferences.pinnedCatalogues().get(),
            recentSourceIds = preferences.recentlyUsedSourceIds().get(),
            lastFetchedSourceIds = preferences.lastFetchedSuggestionsSourceIds().get(),
        )

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
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Log individual source failures to the debug log (Bug 8a).
            // Not surfaced to the user per-source; only aggregate failures are shown.
            if (sourceId != null) {
                debugLog.add(
                    LogType.SECTION_DROPPED,
                    "Source $sourceId network error: ${e.javaClass.simpleName}: ${e.message}",
                )
            }
            null
        }

}
