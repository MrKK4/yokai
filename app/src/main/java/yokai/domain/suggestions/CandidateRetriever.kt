package yokai.domain.suggestions

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Filter
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
            sections.forEach { section ->
                launch {
                    results.send(
                        CandidateRetrievalResult(
                            section = section,
                            candidates = retrieveSection(
                                section = section,
                                pageOffset = pageOffset,
                                requestGate = requestGate,
                            ),
                        ),
                    )
                }
            }

            repeat(sections.size) {
                val result = results.receive()
                if (result.section.type == SectionType.DISCOVERY) {
                    val discoverySourceIds = result.candidates
                        .map { it.sourceId.toString() }
                        .toSet()
                    if (discoverySourceIds.isNotEmpty()) {
                        preferences.recentlyUsedSourceIds().set(discoverySourceIds)
                    }
                }
                onResult(result)
            }
            results.close()
        }
    }

    private suspend fun retrieveSection(
        section: PlannedSection,
        pageOffset: Int,
        requestGate: Semaphore,
    ): List<SuggestionCandidate> {
        if (section.isColdStartDiscovery()) {
            return retrieveColdStartDiscoverySection(section, requestGate)
        }

        val sources = activeSources(discovery = section.type == SectionType.DISCOVERY)
        if (sources.isEmpty()) return emptyList()

        suspend fun fetchPage(page: Int, countBySource: ConcurrentHashMap<Long, AtomicInteger>): List<SuggestionCandidate> {
            return coroutineScope {
                when (section.type) {
                    SectionType.DISCOVERY ->
                        sources.mapIndexed { sourceIndex, source ->
                            async { fetchDiscoverySource(section, source, sourceIndex, page, requestGate, countBySource) }
                        }
                    else ->
                        sources.mapIndexed { sourceIndex, source ->
                            async { fetchSearchSource(section, source, sourceIndex, page, requestGate, countBySource) }
                        }
                }.awaitAll().flatten()
            }
        }

        val countBySource = ConcurrentHashMap<Long, AtomicInteger>()
        val page = pageOffset.coerceAtLeast(1)
        val candidates = fetchPage(page, countBySource).toMutableList()

        // ── Source-rotation backfill ───────────────────────────────────────────
        // If the initial concurrent fetch did not reach the section minimum, identify
        // which sources returned 0 results (dry sources with unused quota) and fire
        // sequential top-up requests to the productive sources, each receiving a cap
        // equal to the remaining shortfall, until the minimum is met.
        if (candidates.size < SuggestionsConfig.MIN_RESULTS_PER_SECTION) {
            val drySources = sources.filter { s ->
                (countBySource[s.id]?.get() ?: 0) == 0
            }.toSet()
            val backfillSources = sources.filterNot { it in drySources }
            for (source in backfillSources) {
                val shortfall = SuggestionsConfig.MIN_RESULTS_PER_SECTION - candidates.size
                if (shortfall <= 0) break
                // Temporarily reduce the counter so cappedCandidates grants an extra
                // `shortfall` items on top of what this source already contributed.
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
                // Restore counter to the true total so later cap checks remain accurate.
                counter.set(savedCount + topUp.size)
            }
        }
        // ──────────────────────────────────────────────────────────────────────

        // Page-2 backstop: if source rotation still couldn't fill the minimum, extend
        // to the next page from all sources (uses existing per-source caps).
        if (candidates.size < SuggestionsConfig.MIN_RESULTS_PER_SECTION) {
            candidates.addAll(fetchPage(page + 1, countBySource))
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
        val pageResult = sourceResult(sourceId = source.id) {
            requestGate.withPermit {
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
     * 1. Try to inject the genre into the source's native filter checkboxes via
     *    [injectGenreFilter]. If a match is found, call `getSearchManga(query="", filters)`.
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

        // ── Phase A: genre-filter injection ──────────────────────────────────────
        val injectedFilters = canonicalTag?.let { tag ->
            sourceResult(sourceId = source.id) {
                source.injectGenreFilter(tag, section.sortOrder)
            }
        }

        val pageResult = if (injectedFilters != null) {
            // SUCCESS: source has a native genre filter for this tag — empty query, filter checked.
            debugLog.add(
                LogType.SECTION_SELECTED,
                "Source ${source.id} (${source.name}): genre filter injected for '$canonicalTag'",
            )
            sourceResult(sourceId = source.id) {
                requestGate.withPermit {
                    source.getSearchManga(page, query = "", injectedFilters)
                }
            }
        } else {
            // FALLBACK: no native genre checkbox — look up source-specific vocabulary.
            val exactTerm = canonicalTag?.let {
                tagProfileRepository.getExactTermForSource(it, source.id)
            }
            val query = exactTerm ?: canonicalTag ?: section.searchTerms.firstOrNull() ?: return emptyList()
            if (exactTerm == null && canonicalTag != null) {
                debugLog.add(
                    LogType.SORT_FALLBACK,
                    "Source ${source.id} (${source.name}): no genre filter for '$canonicalTag' — text search with '$query'",
                )
            }
            sourceResult(sourceId = source.id) {
                requestGate.withPermit {
                    source.getSearchManga(page, query, source.searchFiltersFor(section.sortOrder))
                }
            }
        } ?: return emptyList()
        // ─────────────────────────────────────────────────────────────────────────

        val candidates = cappedCandidates(
            section,
            source.id,
            sourceIndex,
            searchTerm = injectedFilters?.let { "" } ?: (canonicalTag ?: section.searchTerms.firstOrNull()),
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

    /**
     * Phase A: attempt to programmatically "check the box" for [canonicalTag] in the
     * source's native filter list. Returns the modified [FilterList] if a match was
     * found, or `null` if this source doesn't expose a genre filter for this tag.
     *
     * Handles all three filter types used by Tachiyomi/Yokai extensions:
     * - `Filter.CheckBox` — binary include/exclude checkbox
     * - `Filter.TriState` — include / exclude / ignore (most common for mature extensions)
     * - `Filter.Select`   — dropdown selector (less common; matches on option name)
     */
    private fun CatalogueSource.injectGenreFilter(
        canonicalTag: String,
        sortOrder: SuggestionSortOrder,
    ): FilterList? {
        val filters = freshFilterList(this)
        var filterInjected = false

        // Apply sort order first (existing behaviour).
        val sortFilter = filters.filterIsInstance<Filter.Sort>().firstOrNull()
        if (sortFilter != null) {
            val sortIndex = sortFilter.values.indexOfFirst { it.matchesSortOrder(sortOrder) }
            if (sortIndex >= 0) {
                sortFilter.state = Filter.Sort.Selection(index = sortIndex, ascending = false)
            }
        }

        // Scan for genre filters and check the matching box.
        filters.forEach { filter ->
            when (filter) {
                is Filter.Group<*> -> {
                    filter.state.forEach { item ->
                        val matchedTag = when (item) {
                            is Filter.CheckBox -> {
                                if (tagCanonicalizer.normalizeToLookupKey(item.name) == canonicalTag) {
                                    item.state = true
                                    true
                                } else false
                            }
                            is Filter.TriState -> {
                                if (tagCanonicalizer.normalizeToLookupKey(item.name) == canonicalTag) {
                                    item.state = Filter.TriState.STATE_INCLUDE
                                    true
                                } else false
                            }
                            else -> false
                        }
                        if (matchedTag) filterInjected = true
                    }
                }
                is Filter.Select<*> -> {
                    val matchIndex = filter.values.indexOfFirst { value ->
                        tagCanonicalizer.normalizeToLookupKey(value.toString()) == canonicalTag
                    }
                    if (matchIndex >= 0) {
                        filter.state = matchIndex
                        filterInjected = true
                    }
                }
                else -> { /* Sort and Header — already handled above */ }
            }
        }

        return if (filterInjected) filters else null
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
        activeNetworkSources(discovery)

    private fun activeNetworkSources(discovery: Boolean = false): List<CatalogueSource> =
        SuggestionSourceSelector.activeNetworkSources(
            sources = sourceManager.getCatalogueSources(),
            selection = sourceSelection(),
            discovery = discovery,
        )

    private fun sourceSelection(): SuggestionSourceSelection =
        SuggestionSourceSelection(
            enabledLanguages = preferences.enabledLanguages().get(),
            hiddenSourceIds = preferences.hiddenSources().get(),
            pinnedSourceIds = preferences.pinnedCatalogues().get(),
            recentSourceIds = preferences.recentlyUsedSourceIds().get(),
        )

    /** Sort-filter helper reused in the text-search fallback path. */
    private fun CatalogueSource.searchFiltersFor(sortOrder: SuggestionSortOrder): FilterList {
        val filters = freshFilterList(this)
        val sortFilter = filters.filterIsInstance<Filter.Sort>().firstOrNull() ?: return filters
        val sortIndex = sortFilter.values.indexOfFirst { value -> value.matchesSortOrder(sortOrder) }
        if (sortIndex >= 0) {
            sortFilter.state = Filter.Sort.Selection(index = sortIndex, ascending = false)
        } else if (sortOrder == SuggestionSortOrder.Latest) {
            debugLog.add(LogType.SORT_FALLBACK, "Source $id does not expose latest-by-tag sort - fell back to default search order")
        }
        return filters
    }

    private fun String.matchesSortOrder(sortOrder: SuggestionSortOrder): Boolean {
        val value = lowercase().trim()
        return when (sortOrder) {
            SuggestionSortOrder.Latest -> LATEST_SORT_KEYWORDS.any { it in value }
            SuggestionSortOrder.Popular -> POPULAR_SORT_KEYWORDS.any { it in value }
        }
    }

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

    private companion object {
        private val LATEST_SORT_KEYWORDS = setOf("latest", "recent", "update", "updated", "uploaded", "date", "new")
        private val POPULAR_SORT_KEYWORDS = setOf("popular", "views", "view", "follow", "follows", "rating", "score", "trend", "hot")
    }
}
