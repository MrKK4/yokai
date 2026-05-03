package yokai.domain.suggestions

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    // ── FilterList cache ─────────────────────────────────────────────────────────
    // Some extensions make a network call inside getFilterList() to build dynamic
    // genre lists. Caching per sourceId avoids redundant requests across sections
    // fetched in the same batch. Cache expires after FILTER_CACHE_TTL_MS (1 hour).
    private val filterListCache = ConcurrentHashMap<Long, Pair<FilterList, Long>>()

    private fun getCachedFilterList(source: CatalogueSource): FilterList {
        val cached = filterListCache[source.id]
        if (cached != null && System.currentTimeMillis() - cached.second < FILTER_CACHE_TTL_MS) {
            return cached.first
        }
        val fresh = source.getFilterList()
        filterListCache[source.id] = fresh to System.currentTimeMillis()
        return fresh
    }
    // ─────────────────────────────────────────────────────────────────────────────

    suspend fun retrieve(
        sections: List<PlannedSection>,
        pageOffset: Int = 1,
    ): List<CandidateRetrievalResult> {
        val results = coroutineScope {
            val requestGate = Semaphore(MAX_CONCURRENT_SOURCE_REQUESTS)
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
            val requestGate = Semaphore(MAX_CONCURRENT_SOURCE_REQUESTS)
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
        val sources = activeSources(discovery = section.type == SectionType.DISCOVERY)
        if (sources.isEmpty()) return emptyList()

        val countBySource = ConcurrentHashMap<Long, AtomicInteger>()
        val page = pageOffset.coerceAtLeast(1)
        val candidates = coroutineScope {
            when (section.type) {
                SectionType.DISCOVERY ->
                    // Discovery: one async per source — no tag search involved.
                    sources.mapIndexed { sourceIndex, source ->
                        async {
                            fetchDiscoverySource(section, source, sourceIndex, page, requestGate, countBySource)
                        }
                    }
                else ->
                    // Tag sections: ONE async per source (Phase C — de-spammed).
                    // injectGenreFilter / fallback logic inside fetchSearchSource
                    // resolves the single best query for this source; no flatMap over aliases.
                    sources.mapIndexed { sourceIndex, source ->
                        async {
                            fetchSearchSource(section, source, sourceIndex, page, requestGate, countBySource)
                        }
                    }
            }.awaitAll().flatten()
        }

        return candidates
            .distinctBy { it.sourceId to it.manga.url }
            .take(SuggestionsConfig.MAX_CANDIDATES_PER_SECTION)
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
            source.injectGenreFilter(tag, section.sortOrder)
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
            candidates.forEach { candidate ->
                candidate.manga.getGenres()?.forEach { rawTag ->
                    val canonical = tagCanonicalizer.canonicalize(rawTag, sourceId).canonicalKey
                    if (canonical.isNotBlank()) {
                        tagProfileRepository.recordSourceVocabulary(
                            rawTag = rawTag,
                            canonicalTag = canonical,
                            sourceId = sourceId,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Bug 8d: log vocabulary learning failures to the debug log.
            // No user-facing change needed — this is best-effort only.
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
        val filters = getCachedFilterList(this)
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
        val remaining = SuggestionsConfig.MAX_PER_SOURCE_FETCH - counter.get()
        if (remaining <= 0) {
            debugLog.add(LogType.SOURCE_CAP_HIT, "Source $sourceId capped at ${SuggestionsConfig.MAX_PER_SOURCE_FETCH} candidates for section '${section.sectionKey}'")
            return emptyList()
        }

        val selected = mangas.take(remaining)
        val newCount = counter.addAndGet(selected.size)
        if (newCount >= SuggestionsConfig.MAX_PER_SOURCE_FETCH && mangas.size > selected.size) {
            debugLog.add(LogType.SOURCE_CAP_HIT, "Source $sourceId capped at ${SuggestionsConfig.MAX_PER_SOURCE_FETCH} candidates for section '${section.sectionKey}'")
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

    private fun activeSources(discovery: Boolean): List<CatalogueSource> {
        val languages = preferences.enabledLanguages().get()
        val hiddenSourceIds = preferences.hiddenSources().get()
        val pinnedSourceIds = preferences.pinnedCatalogues().get()
        val recentSourceIds = preferences.recentlyUsedSourceIds().get()

        val enabledSources = sourceManager.getCatalogueSources()
            .filter { source -> source.lang in languages || source.id == LocalSource.ID }
            .filterNot { source -> source.id.toString() in hiddenSourceIds }

        val sourcePool = if (discovery) {
            enabledSources.filter { it.id.toString() in pinnedSourceIds }
                .takeIf { it.isNotEmpty() }
                ?: enabledSources
        } else {
            enabledSources
        }

        return sourcePool
            .sortedWith(
                compareBy<CatalogueSource>(
                    { it.id.toString() in recentSourceIds },
                    { it.id.toString() !in pinnedSourceIds },
                    { "(${it.lang}) ${it.name}" },
                ),
            )
            .take(MAX_ACTIVE_SOURCES)
    }

    /** Sort-filter helper reused in the text-search fallback path. */
    private fun CatalogueSource.searchFiltersFor(sortOrder: SuggestionSortOrder): FilterList {
        val filters = getCachedFilterList(this)
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
            block()
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
        private const val MAX_ACTIVE_SOURCES = 10
        private const val MAX_CONCURRENT_SOURCE_REQUESTS = 8
        /** FilterList cache TTL — 1 hour. Prevents repeated network calls for dynamic filter lists. */
        private const val FILTER_CACHE_TTL_MS = 60 * 60 * 1000L
        private val LATEST_SORT_KEYWORDS = setOf("latest", "recent", "update", "updated", "uploaded", "date", "new")
        private val POPULAR_SORT_KEYWORDS = setOf("popular", "views", "view", "follow", "follows", "rating", "score", "trend", "hot")
    }
}
