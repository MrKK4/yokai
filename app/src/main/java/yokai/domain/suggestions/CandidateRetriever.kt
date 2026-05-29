package yokai.domain.suggestions

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
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
    val sourcePoolSize: Int = candidates.map { it.sourceId }.toSet().size,
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
    private val sourceFilterAuditor: SourceFilterAuditor = SourceFilterAuditor(
        tagCanonicalizer,
        tagProfileRepository,
        debugLog,
    ),
) {
    private fun freshFilterList(source: CatalogueSource): FilterList =
        source.getFilterList()

    private fun safeFreshFilterList(source: CatalogueSource): FilterList =
        try {
            freshFilterList(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            debugLog.add(
                LogType.SORT_FALLBACK,
                "Source ${source.id} (${source.name}): filter list unavailable (${e.javaClass.simpleName}); falling back to plain text search",
            )
            FilterList()
        }

    /**
     * Tracks how many consecutive `sourceResult` calls have failed (timeout or non-transient
     * throwable) per source. Process-lifetime in-memory only — resets when the app restarts.
     * Used by [isSourceCooldownActive] to skip sources that have been failing every refresh,
     * so a chronically broken extension does not burn `SOURCE_REQUEST_TIMEOUT_MS` on every
     * refresh.
     */
    private val consecutiveSourceFailures = ConcurrentHashMap<Long, AtomicInteger>()
    private val drySearchPages = ConcurrentHashMap<String, Long>()
    private val nativeDryPageStreaks = ConcurrentHashMap<String, AtomicInteger>()
    private val textFallbackStartPages = ConcurrentHashMap<String, Int>()

    private fun isSourceCooldownActive(sourceId: Long): Boolean {
        val count = consecutiveSourceFailures[sourceId]?.get() ?: 0
        return count >= SuggestionsConfig.SOURCE_COOLDOWN_FAILURE_THRESHOLD
    }

    private fun recordSourceFailure(sourceId: Long) {
        consecutiveSourceFailures
            .getOrPut(sourceId) { AtomicInteger(0) }
            .incrementAndGet()
    }

    private fun recordSourceSuccess(sourceId: Long) {
        consecutiveSourceFailures[sourceId]?.set(0)
    }

    private fun dryPageKey(
        source: CatalogueSource,
        section: PlannedSection,
        mode: SearchQueryMode,
        page: Int,
    ): String =
        "${source.id}:${section.sectionKey}:${section.sortOrder}:${mode.name}:${page.coerceAtLeast(1)}"

    private fun dryStreakKey(source: CatalogueSource, section: PlannedSection): String =
        "${source.id}:${section.sectionKey}:${section.sortOrder}"

    private fun isDrySearchPage(
        source: CatalogueSource,
        section: PlannedSection,
        mode: SearchQueryMode,
        page: Int,
    ): Boolean {
        val normalizedPage = page.coerceAtLeast(1)
        if (normalizedPage == 1) return false
        val key = dryPageKey(source, section, mode, normalizedPage)
        val markedAt = drySearchPages[key] ?: return false
        return if (System.currentTimeMillis() - markedAt <= SuggestionsConfig.DRY_SEARCH_PAGE_TTL_MS) {
            true
        } else {
            drySearchPages.remove(key)
            false
        }
    }

    private fun markDrySearchPage(
        source: CatalogueSource,
        section: PlannedSection,
        mode: SearchQueryMode,
        page: Int,
    ) {
        val normalizedPage = page.coerceAtLeast(1)
        if (normalizedPage == 1) return
        drySearchPages[dryPageKey(source, section, mode, normalizedPage)] = System.currentTimeMillis()
    }

    private fun recordNativeSearchPageResult(
        source: CatalogueSource,
        section: PlannedSection,
        page: Int,
        candidateCount: Int,
    ): Boolean {
        val streakKey = dryStreakKey(source, section)
        if (candidateCount > 0) {
            nativeDryPageStreaks[streakKey]?.set(0)
            return false
        }
        markDrySearchPage(source, section, SearchQueryMode.NATIVE_TAG, page)
        val streak = nativeDryPageStreaks
            .getOrPut(streakKey) { AtomicInteger(0) }
            .incrementAndGet()
        if (streak >= SuggestionsConfig.TAG_NATIVE_DRY_PAGE_FALLBACK_THRESHOLD) {
            textFallbackStartPages.putIfAbsent(streakKey, page.coerceAtLeast(1))
            return true
        }
        return false
    }

    private fun textFallbackPage(source: CatalogueSource, section: PlannedSection, requestedNativePage: Int): Int? {
        val normalizedPage = requestedNativePage.coerceAtLeast(1)
        if (normalizedPage == 1) return null
        val startPage = textFallbackStartPages[dryStreakKey(source, section)] ?: return null
        val fallbackPage = normalizedPage - startPage + 1
        return fallbackPage.takeIf { it >= 1 }
    }

    /**
     * Synchronous seed of hardcoded source-vocabulary aliases + async kick-off of
     * the filter-list audit. Suspending so the static seed (no network) commits
     * before the caller's first `getSearchManga` reads `tag_alias`.
     */
    private suspend fun seedAndScheduleFilterAudit() {
        val sources = activeNetworkSources()
        sourceFilterAuditor.seedHardcodedAliasesNow(sources)
        sourceFilterAuditor.scheduleAudit(sources)
    }

    /**
     * Public entry point for callers that want to pre-warm the filter audit before the
     * first `retrieve` call. Triggers the lazy genre fetch on every active source so by
     * the time the user opens the Suggestions tab, the `tag_alias` table has populated
     * genre entries and Phase A can inject filters on the very first refresh.
     *
     * No-op if no sources are active yet. Idempotent — only the first call per process
     * actually does work.
     */
    suspend fun prewarmSourceFilters() {
        seedAndScheduleFilterAudit()
    }

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
        allowPageBackstop: Boolean = true,
    ): List<CandidateRetrievalResult> {
        seedAndScheduleFilterAudit()
        val results = coroutineScope {
            val requestGate = Semaphore(SuggestionsConfig.MAX_CONCURRENT_SOURCE_REQUESTS)
            sections.map { section ->
                async {
                    val result = withTimeoutOrNull(sectionTimeoutMs) {
                        retrieveSection(
                            section = section,
                            pageOffset = pageOffset,
                            requestGate = requestGate,
                            maxPerSourceFetch = maxPerSourceFetch,
                            globalSeenKeys = globalSeenKeys,
                            sectionSeenKeys = sectionSeenKeys[section.sectionKey].orEmpty(),
                            sourceCohortSeed = sourceCohortSeed,
                            allowPageBackstop = allowPageBackstop,
                        )
                    } ?: CandidateRetrievalResult(
                        section = section,
                        candidates = emptyList(),
                    )
                    result
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
        seedAndScheduleFilterAudit()
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
                    val finalCandidates = complete?.candidates ?: partial.distinctBy { it.sourceId to it.manga.url }
                    results.send(
                        CandidateRetrievalResult(
                            section = section,
                            candidates = finalCandidates,
                            isSectionComplete = true,
                            sourcePoolSize = complete?.sourcePoolSize ?: finalCandidates.map { it.sourceId }.toSet().size,
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
        allowPageBackstop: Boolean = true,
    ): CandidateRetrievalResult {
        val blockedMangaKeys = globalSeenKeys + sectionSeenKeys
        if (section.isColdStartDiscovery()) {
            return retrieveColdStartDiscoverySection(section, requestGate, blockedMangaKeys, sourceCohortSeed, onSourceComplete)
        }

        val sources = activeSources(
            discovery = section.type == SectionType.DISCOVERY,
            sourceCohortSeed = sourceCohortSeed,
        )
        if (sources.isEmpty()) {
            return CandidateRetrievalResult(section = section, candidates = emptyList(), sourcePoolSize = 0)
        }

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
        if (allowPageBackstop && canOverfillSourceCap && candidates.size < SuggestionsConfig.MAX_RESULTS_PER_SECTION) {
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
        if (allowPageBackstop && candidates.distinctBy { it.sourceId to it.manga.url }.size < SuggestionsConfig.MAX_RESULTS_PER_SECTION) {
            val page2Candidates = fetchPageProgressive(page + 1, countBySource, onSourceComplete)
            candidates.addAll(page2Candidates)
        }

        return CandidateRetrievalResult(
            section = section,
            candidates = candidates
                .distinctBy { it.sourceId to it.manga.url }
                .take(SuggestionsConfig.MAX_CANDIDATES_PER_SECTION),
            sourcePoolSize = sources.size,
        )
    }

    private suspend fun retrieveColdStartDiscoverySection(
        section: PlannedSection,
        requestGate: Semaphore,
        blockedMangaKeys: Set<String>,
        sourceCohortSeed: Int,
        onSourceComplete: (suspend (List<SuggestionCandidate>) -> Unit)?,
    ): CandidateRetrievalResult {
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
        if (sources.isEmpty()) {
            return CandidateRetrievalResult(section = section, candidates = emptyList(), sourcePoolSize = 0)
        }

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

        return CandidateRetrievalResult(
            section = section,
            candidates = candidates
                .distinctBy { it.sourceId to it.manga.url }
                .shuffled()
                .take(SuggestionsConfig.COLD_START_MAX_CANDIDATES),
            sourcePoolSize = sources.size,
        )
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
        if (isSourceCooldownActive(source.id)) {
            debugLog.add(
                LogType.SOURCE_CAP_HIT,
                "Source ${source.id} (${source.name}): skipped — ${SuggestionsConfig.SOURCE_COOLDOWN_FAILURE_THRESHOLD}+ consecutive failures",
            )
            return emptyList()
        }
        suspend fun fetchPage(p: Int) = requestGate.withPermit {
            when (section.sortOrder) {
                SuggestionSortOrder.Latest -> {
                    val latest = if (source.supportsLatest) {
                        sourceResult(sourceId = source.id) {
                            source.getLatestUpdates(p)
                        }
                    } else {
                        null
                    }
                    latest?.takeIf { it.mangas.isNotEmpty() }
                        ?: sourceResult(sourceId = source.id) {
                            source.getPopularManga(p)
                        }
                }
                SuggestionSortOrder.Popular ->
                    sourceResult(sourceId = source.id) {
                        source.getPopularManga(p)
                    }
            }
        }

        var pageResult = fetchPage(page) ?: return emptyList()
        // Page-1 fallback: small sources have very few pages, so a random higher page
        // can land past the end and return an empty list. Retry once at page 1 so the
        // source still contributes instead of silently dropping out.
        if (pageResult.mangas.isEmpty() && page > 1) {
            debugLog.add(
                LogType.SORT_FALLBACK,
                "Source ${source.id} (${source.name}): discovery page $page empty, retrying page 1",
            )
            pageResult = fetchPage(1) ?: pageResult
        }

        learnVocabulary(pageResult.mangas, source.id)

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
        logSourceResult(
            section = section,
            source = source,
            mode = "DISCOVERY_${section.sortOrder.name.uppercase()}",
            page = page.coerceAtLeast(1),
            query = null,
            rawCount = pageResult.mangas.size,
            usableCount = candidates.size,
        )
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
        if (isSourceCooldownActive(source.id)) {
            debugLog.add(
                LogType.SOURCE_CAP_HIT,
                "Source ${source.id} (${source.name}): skipped — ${SuggestionsConfig.SOURCE_COOLDOWN_FAILURE_THRESHOLD}+ consecutive failures",
            )
            return emptyList()
        }
        val canonicalTag = section.canonicalTag

        fun textQueryCandidates(initialQuery: String): List<String> {
            val seenQueries = mutableSetOf(initialQuery)
            return buildList {
                add(initialQuery)
                if (canonicalTag != null) {
                    section.searchTerms.drop(1).forEach {
                        if (it !in seenQueries) {
                            add(it)
                            seenQueries.add(it)
                        }
                    }
                    if (canonicalTag !in seenQueries) {
                        add(canonicalTag)
                        seenQueries.add(canonicalTag)
                    }
                    val titleCase = initialQuery.replaceFirstChar {
                        if (it.isLowerCase()) it.uppercase() else it.toString()
                    }
                    if (titleCase !in seenQueries) {
                        add(titleCase)
                        seenQueries.add(titleCase)
                    }
                    val upper = initialQuery.uppercase()
                    if (upper !in seenQueries) {
                        add(upper)
                        seenQueries.add(upper)
                    }
                }
            }.distinct()
        }

        suspend fun fetchTextSearch(
            textPage: Int,
            initialQuery: String,
            reason: String,
        ): List<SuggestionCandidate> {
            val normalizedPage = textPage.coerceAtLeast(1)
            if (isDrySearchPage(source, section, SearchQueryMode.TEXT, normalizedPage)) {
                debugLog.add(
                    LogType.SORT_FALLBACK,
                    "Source ${source.id} (${source.name}): skipped dry text page $normalizedPage for '${section.sectionKey}'",
                )
                return emptyList()
            }
            var sawResponse = false
            for ((qIndex, q) in textQueryCandidates(initialQuery).withIndex()) {
                if (qIndex > 0) {
                    debugLog.add(
                        LogType.SORT_FALLBACK,
                        "Source ${source.id} (${source.name}): text fallback retry with query '$q' for $reason",
                    )
                }
                val filters = safeFreshFilterList(source).also {
                    it.tryApplySuggestionSort(section.sortOrder)
                }
                val result = requestGate.withPermit {
                    sourceResult(sourceId = source.id) {
                        source.getSearchManga(normalizedPage, q, filters)
                    }
                } ?: continue
                sawResponse = true
                learnVocabulary(result.mangas, source.id)
                val candidates = cappedCandidates(
                    section = section,
                    sourceId = source.id,
                    sourceIndex = sourceIndex,
                    searchTerm = q,
                    mangas = result.mangas,
                    countBySource = countBySource,
                    maxPerSourceFetch = maxPerSourceFetch,
                    blockedMangaKeys = blockedMangaKeys,
                )
                logSourceResult(
                    section = section,
                    source = source,
                    mode = "TEXT",
                    page = normalizedPage,
                    query = q,
                    rawCount = result.mangas.size,
                    usableCount = candidates.size,
                    reason = reason,
                )
                if (candidates.isNotEmpty()) {
                    if (qIndex > 0 || reason != "primary text search") {
                        debugLog.add(
                            LogType.SECTION_SELECTED,
                            "Source ${source.id} (${source.name}): text fallback '$q' page $normalizedPage produced ${candidates.size} usable results for '${section.sectionKey}'",
                        )
                    }
                    return candidates
                }
            }
            if (sawResponse) {
                markDrySearchPage(source, section, SearchQueryMode.TEXT, normalizedPage)
            }
            return emptyList()
        }

        // ── Phase A: tag/genre filter injection ──────────────────────────────────
        val injectedFilters = canonicalTag?.let { tag ->
            sourceResult(sourceId = source.id, countFailure = false) {
                source.tryIncludeTagFilter(tag, tagCanonicalizer)
            }
        }

        val fallbackPage = textFallbackPage(source, section, page)
        val exactTerm = canonicalTag?.let {
            tagProfileRepository.getExactTermForSource(it, source.id)
        }
        val textQuery = exactTerm
            ?: section.searchTerms.firstOrNull()
            ?: canonicalTag
            ?: return emptyList()

        if (fallbackPage != null) {
            return fetchTextSearch(
                textPage = fallbackPage,
                initialQuery = textQuery,
                reason = "native tag dry-page fallback",
            )
        }

        if (injectedFilters != null) {
            debugLog.add(
                LogType.SECTION_SELECTED,
                "Source ${source.id} (${source.name}): tag filter injected for '$canonicalTag'",
            )
            val normalizedPage = page.coerceAtLeast(1)
            if (isDrySearchPage(source, section, SearchQueryMode.NATIVE_TAG, normalizedPage)) {
                debugLog.add(
                    LogType.SORT_FALLBACK,
                    "Source ${source.id} (${source.name}): skipped dry native tag page $normalizedPage for '${section.sectionKey}'",
                )
                return emptyList()
            }
            val filters = injectedFilters.also {
                it.tryApplySuggestionSort(section.sortOrder)
            }
            val result = requestGate.withPermit {
                sourceResult(sourceId = source.id) {
                    source.getSearchManga(normalizedPage, "", filters)
                }
            } ?: return emptyList()
            learnVocabulary(result.mangas, source.id)
            val candidates = cappedCandidates(
                section = section,
                sourceId = source.id,
                sourceIndex = sourceIndex,
                searchTerm = "",
                mangas = result.mangas,
                countBySource = countBySource,
                maxPerSourceFetch = maxPerSourceFetch,
                blockedMangaKeys = blockedMangaKeys,
            )
            logSourceResult(
                section = section,
                source = source,
                mode = "NATIVE_TAG",
                page = normalizedPage,
                query = null,
                rawCount = result.mangas.size,
                usableCount = candidates.size,
            )
            val shouldFallbackNow = recordNativeSearchPageResult(
                source = source,
                section = section,
                page = normalizedPage,
                candidateCount = candidates.size,
            )
            if (candidates.isNotEmpty()) {
                return candidates
            }
            if (shouldFallbackNow) {
                debugLog.add(
                    LogType.SORT_FALLBACK,
                    "Source ${source.id} (${source.name}): native tag pages dry, switching '${section.sectionKey}' to text fallback",
                )
                return fetchTextSearch(
                    textPage = 1,
                    initialQuery = textQuery,
                    reason = "native tag dry-page fallback",
                )
            }
            return emptyList()
        }

        if (exactTerm == null && canonicalTag != null) {
            debugLog.add(
                LogType.SORT_FALLBACK,
                "Source ${source.id} (${source.name}): no tag filter for '$canonicalTag' - text search with '$textQuery'",
            )
        }
        return fetchTextSearch(
            textPage = page,
            initialQuery = textQuery,
            reason = "primary text search",
        )
    }

    /**
     * Record raw genre strings returned by [sourceId] into the alias table so future
     * fallback text-searches can use the exact string this source understands.
     * Fire-and-forget: any DB error is swallowed to never interrupt the fetch path.
     */
    private suspend fun learnVocabulary(mangas: List<SManga>, sourceId: Long) {
        if (mangas.isEmpty()) return
        val batch = try {
            val entries = mutableListOf<Triple<String, String, Long>>()
            mangas.forEach { manga ->
                manga.getGenres()?.forEach { rawTag ->
                    val canonical = tagCanonicalizer.canonicalize(rawTag, sourceId).canonicalKey
                    if (canonical.isNotBlank()) {
                        entries.add(Triple(rawTag, canonical, sourceId))
                    }
                }
            }
            entries
        } catch (e: Exception) {
            debugLog.add(
                LogType.SECTION_DROPPED,
                "learnVocabulary canonicalize failed for source $sourceId: ${e.javaClass.simpleName}: ${e.message}",
            )
            return
        }
        if (batch.isEmpty()) return
        // Retry once on DB contention (SQLITE_BUSY/locked). Heavy refreshes can have
        // multiple writers (auditor, seedAliases, learnVocabulary) contending on the
        // tag_alias table; a single failure here would lose the source's vocabulary
        // for this refresh and force the next fetch to still send the raw canonical.
        var attempt = 0
        while (attempt < LEARN_VOCAB_MAX_ATTEMPTS) {
            attempt++
            try {
                tagProfileRepository.recordSourceVocabularyBatch(batch)
                return
            } catch (e: Exception) {
                if (attempt >= LEARN_VOCAB_MAX_ATTEMPTS) {
                    debugLog.add(
                        LogType.SECTION_DROPPED,
                        "learnVocabulary DB write failed for source $sourceId after $attempt attempts: ${e.javaClass.simpleName}: ${e.message}",
                    )
                    return
                }
                kotlinx.coroutines.delay(LEARN_VOCAB_RETRY_BACKOFF_MS)
            }
        }
    }

    private companion object {
        private const val LEARN_VOCAB_MAX_ATTEMPTS = 2
        private const val LEARN_VOCAB_RETRY_BACKOFF_MS = 250L
    }

    private fun logSourceResult(
        section: PlannedSection,
        source: CatalogueSource,
        mode: String,
        page: Int,
        query: String?,
        rawCount: Int,
        usableCount: Int,
        reason: String? = null,
    ) {
        val queryPart = query?.let { " query='$it'" }.orEmpty()
        val reasonPart = reason?.let { " reason='$it'" }.orEmpty()
        debugLog.add(
            LogType.SOURCE_RESULT,
            "section='${section.sectionKey}' source=${source.id} sourceName='${source.name}' mode=$mode page=$page$queryPart raw=$rawCount usable=$usableCount sort=${section.sortOrder.name}$reasonPart",
        )
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
            // Tag sections widen the source pool (see TAG_SECTION_SOURCE_COHORT_SIZE) since
            // many sources contribute 0 results to a given tag — we want more chances to
            // populate the section.
            maxSources = if (discovery) {
                SuggestionsConfig.MAIN_FEED_SOURCE_COHORT_SIZE
            } else {
                SuggestionsConfig.TAG_SECTION_SOURCE_COHORT_SIZE
            },
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

    private suspend fun <T> sourceResult(
        sourceId: Long? = null,
        countFailure: Boolean = true,
        block: suspend () -> T,
    ): T? =
        try {
            var completed = false
            val result = withTimeoutOrNull(SuggestionsConfig.SOURCE_REQUEST_TIMEOUT_MS) {
                block().also { completed = true }
            }
            if (!completed && sourceId != null) {
                if (countFailure) {
                    debugLog.add(
                        LogType.SECTION_DROPPED,
                        "Source $sourceId timed out after ${SuggestionsConfig.SOURCE_REQUEST_TIMEOUT_MS}ms",
                    )
                } else {
                    debugLog.add(
                        LogType.SORT_FALLBACK,
                        "Source $sourceId optional filter lookup timed out; falling back to text search",
                    )
                }
                if (countFailure) recordSourceFailure(sourceId)
                if (!networkStatus.isOnline()) {
                    throw TransientSuggestionNetworkException(
                        java.io.IOException("Network unavailable while waiting for source $sourceId"),
                    )
                }
            } else if (completed && sourceId != null) {
                if (countFailure) recordSourceSuccess(sourceId)
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
                if (countFailure) {
                    debugLog.add(
                        LogType.SECTION_DROPPED,
                        "Source $sourceId network error: ${e.javaClass.simpleName}: ${e.message}",
                    )
                } else {
                    debugLog.add(
                        LogType.SORT_FALLBACK,
                        "Source $sourceId optional filter lookup failed (${e.javaClass.simpleName}); falling back to text search",
                    )
                }
                if (countFailure) recordSourceFailure(sourceId)
            }
            null
        }

}

private enum class SearchQueryMode {
    NATIVE_TAG,
    TEXT,
}
