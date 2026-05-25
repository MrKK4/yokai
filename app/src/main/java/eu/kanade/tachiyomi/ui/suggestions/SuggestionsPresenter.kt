package eu.kanade.tachiyomi.ui.suggestions

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.suggestions.SuggestionsWorker
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate
import yokai.domain.suggestions.FeedAggregator
import yokai.domain.suggestions.ExpandedSectionPage
import yokai.domain.suggestions.GetUserSuggestionQueriesUseCase
import yokai.domain.suggestions.CandidateRetriever
import yokai.domain.suggestions.CandidateRetrievalResult
import yokai.domain.suggestions.COLD_START_DISCOVERY_SECTION_KEY
import yokai.domain.suggestions.SuggestionCandidate
import yokai.domain.suggestions.InterestProfileBuilder
import yokai.domain.suggestions.RankingContext
import yokai.domain.suggestions.SeenEntry
import yokai.domain.suggestions.TagCanonicalizer
import yokai.domain.suggestions.TagProfileRepository
import yokai.domain.suggestions.TagState
import yokai.domain.suggestions.PlannedSection
import yokai.domain.suggestions.PlannedSectionRepository
import yokai.domain.suggestions.SectionPlanner
import yokai.domain.suggestions.SectionBatcher
import yokai.domain.suggestions.SectionType
import yokai.domain.suggestions.SessionContext
import yokai.domain.suggestions.ShownMangaHistoryRepository
import yokai.domain.suggestions.SuggestionRanker
import yokai.domain.suggestions.SuggestionMetadataVerifier
import yokai.domain.suggestions.SuggestionNetworkStatus
import yokai.domain.suggestions.SuggestionRefreshReason
import yokai.domain.suggestions.SuggestionRefreshSession
import yokai.domain.suggestions.SuggestionRefreshSessionTracker
import yokai.domain.suggestions.SuggestionResultMode
import yokai.domain.suggestions.SuggestionSeenLogRepository
import yokai.domain.suggestions.SuggestedManga
import yokai.domain.suggestions.SuggestionSortOrder
import yokai.domain.suggestions.SuggestionsRepository
import yokai.domain.suggestions.SuggestionsConfig
import yokai.domain.suggestions.SuggestionsDebugLog
import yokai.domain.suggestions.LogType
import yokai.domain.suggestions.SuggestionsModeStoragePolicy
import yokai.domain.suggestions.SuggestionsRefreshCoordinator
import yokai.domain.suggestions.TransientSuggestionNetworkException
import yokai.i18n.MR
import yokai.util.lang.getString

data class SuggestionsState(
    val suggestions: Map<String, List<Manga>> = emptyMap(),
    val sectionDisplayNames: Map<String, String> = emptyMap(),
    val selectedSectionKey: String? = null,
    val availableTags: List<String> = emptyList(),
    val blacklistedTags: Set<String> = emptySet(),
    val pinnedTags: Set<String> = emptySet(),
    val isTagFilterSheetVisible: Boolean = false,
    /** Pin is V1-only; the filter sheet hides pin controls when this is false. */
    val pinFilterEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val isForegroundRefresh: Boolean = false,
    val isFetching: Boolean = false,
    val plannedSections: List<PlannedSection> = emptyList(),
    val nextBatchStartIndex: Int = 0,
    val isFetchingBatch: Boolean = false,
    val allSectionsLoaded: Boolean = false,
    val hasReachedEnd: Boolean = false,
    val endMessage: String? = null,
    val emptyMessage: String? = null,
    val sortOrder: SuggestionSortOrder = SuggestionSortOrder.Popular,
    val sheetSectionKey: String? = null,
    val sheetResults: List<Manga> = emptyList(),
    val sheetIsLoading: Boolean = false,
    val sheetIsLoadingMore: Boolean = false,
    val sheetHasMore: Boolean = false,
    val sheetError: String? = null,
    val sheetSuppressed: Boolean = false,
    val refreshingSectionKeys: Set<String> = emptySet(),
    val refreshBannerMessage: String? = null,
    val staleSnapshotMode: Int? = null,
    val isPausedForNetwork: Boolean = false,
)

/**
 * In-memory snapshot of the rendered state for a single sort order.
 * Captured the moment the user switches away from a sort so switching back
 * immediately shows the previous results without a network round-trip.
 */
private data class SortSnapshot(
    val suggestions: Map<String, List<Manga>>,
    val plannedSections: List<PlannedSection>,
    val nextBatchStartIndex: Int,
    val allSectionsLoaded: Boolean,
    val hasReachedEnd: Boolean,
    val endMessage: String?,
)

internal fun shouldAutoRefreshStoredSuggestions(
    newestFetchedAt: Long?,
    now: Long,
    staleAfterMs: Long = SuggestionsConfig.AUTO_REFRESH_STALE_AFTER_MS,
): Boolean =
    newestFetchedAt != null && now - newestFetchedAt >= staleAfterMs

internal fun shouldShowFullPageRefreshLoading(
    isForegroundRefreshing: Boolean,
    hasRenderedSuggestions: Boolean,
): Boolean =
    isForegroundRefreshing && !hasRenderedSuggestions

internal fun shouldShowBlockingRefreshLockMessage(): Boolean =
    false

internal fun shouldCloseExpandedSheetOnDismiss(sheetSuppressed: Boolean): Boolean =
    !sheetSuppressed

internal fun sourceSortOrderForExpandableSection(
    sectionKey: String,
    currentSortOrder: SuggestionSortOrder,
): SuggestionSortOrder? =
    when (sectionKey) {
        "latest" -> SuggestionSortOrder.Latest
        "popular" -> SuggestionSortOrder.Popular
        "discovery", COLD_START_DISCOVERY_SECTION_KEY -> currentSortOrder
        else -> null
    }

/**
 * Converts a V1 sectionKey into a user-friendly header. V2 sections carry their own
 * displayReason from SectionPlanner; V1 doesn't, so we synthesise headers from the
 * prefix. Icons keep labels short (the user complaint was long "Because you read…"
 * style headers getting cut off on wider tag names).
 *
 *  popular           → 🔥 Popular
 *  latest            → 🆕 Latest
 *  tag:romance       → ⭐ Romance        (affinity)
 *  pinned:mecha      → 📌 Mecha          (no "Pinned:" word — icon carries meaning)
 *  search:cyberpunk  → 🔍 Cyberpunk      (no "Search:" word)
 *  expanded:foo      → Foo               (sheet header already has its own context)
 *  anything else     → raw key           (never silently blank)
 */
internal fun sectionKeyToV1DisplayName(sectionKey: String): String {
    val (icon, tail) = when {
        sectionKey == "popular" -> "🔥 " to "popular"
        sectionKey == "latest" -> "🆕 " to "latest"
        sectionKey.startsWith("tag:") -> "⭐ " to sectionKey.removePrefix("tag:")
        sectionKey.startsWith("pinned:") -> "📌 " to sectionKey.removePrefix("pinned:")
        sectionKey.startsWith("search:") -> "🔍 " to sectionKey.removePrefix("search:")
        sectionKey.startsWith("expanded:") -> "" to sectionKey.removePrefix("expanded:")
        else -> return sectionKey
    }
    val pretty = tail.trim()
        .takeIf { it.isNotBlank() }
        ?.split(' ', '-', '_')
        ?.filter { it.isNotBlank() }
        ?.joinToString(" ") { word -> word.replaceFirstChar { c -> c.titlecase() } }
        ?: return sectionKey
    return "$icon$pretty"
}

class SuggestionsPresenter(
    private val context: Context,
    private val suggestionsRepository: SuggestionsRepository = Injekt.get(),
    private val shownHistoryRepository: ShownMangaHistoryRepository = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val insertManga: InsertManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val interestProfileBuilder: InterestProfileBuilder = Injekt.get(),
    private val sectionPlanner: SectionPlanner = Injekt.get(),
    private val plannedSectionRepository: PlannedSectionRepository = Injekt.get(),
    private val candidateRetriever: CandidateRetriever = Injekt.get(),
    private val suggestionRanker: SuggestionRanker = Injekt.get(),
    private val suggestionSeenLogRepository: SuggestionSeenLogRepository = Injekt.get(),
    private val sessionContext: SessionContext = Injekt.get(),
    private val debugLog: SuggestionsDebugLog = Injekt.get(),
    private val tagCanonicalizer: TagCanonicalizer = Injekt.get(),
    private val tagProfileRepository: TagProfileRepository = Injekt.get(),
    private val metadataVerifier: SuggestionMetadataVerifier = Injekt.get(),
    private val networkStatus: SuggestionNetworkStatus = Injekt.get(),
) : BaseCoroutinePresenter<SuggestionsController>() {

    private val _state = MutableStateFlow(
        SuggestionsState(sortOrder = preferences.suggestionsSortOrder().get()),
    )
    val state: StateFlow<SuggestionsState> = _state.asStateFlow()
    private val isForegroundRefreshing = AtomicBoolean(false)
    private val isPageFetching = AtomicBoolean(false)
    private val isSectionBatchFetching = AtomicBoolean(false)
    private val feedGeneration = AtomicLong(0L)
    private val refreshSessions = SuggestionRefreshSessionTracker()
    private var isWorkerRefreshing = false
    private var refreshJob: Job? = null
    private var sourceChangeRefreshJob: Job? = null
    private var observedActiveNetworkSourceIds: Set<Long>? = null
    private val pendingV2HardRefreshReplace = AtomicBoolean(false)
    /** True while the very first load of suggestions is in flight, so the DB flow
     *  observer does not overwrite the loading state with an empty emission. */
    private val isInitialLoad = AtomicBoolean(false)
    /**
     * True when a rebuild (V1/V2 toggle, sort switch, filter change) is in progress.
     * The DB flow observer skips empty emissions while this is set so the user never
     * sees a blank-screen flash — old suggestions stay visible until new ones arrive.
     */
    @Volatile private var isRebuilding = false
    /**
     * Debounce job for [setSortOrder]. Cancels any pending rebuild when the user
     * changes sort mode again within 300 ms, so only the final selection fires a
     * network request (v3 plan: "300 ms debounce sort changes").
     */
    private var sortDebounceJob: Job? = null
    /**
     * Per-sort snapshot cache. Populated the moment the user switches away from a sort
     * so switching back immediately restores the previous results.
     * Cleared on explicit refresh or non-sort rebuilds (filter/source/V2-mode changes).
     */
    private val sortSnapshotCache = mutableMapOf<SuggestionSortOrder, SortSnapshot>()
    /**
     * When non-null, the DB flow observer protects a snapshot-restored sort from the
     * exact DB contents that were present at restore time. Once the DB contents change,
     * the observer accepts the new data and clears this freeze.
     */
    @Volatile private var frozenForSort: SuggestionSortOrder? = null
    @Volatile private var frozenDbSignature: Long? = null
    /**
     * Generation value captured at the time the most recent [rebuildFeed] or [refresh] starts.
     * The DB flow observer rejects any emission where [feedGeneration] no longer matches this
     * value, preventing stale in-flight results from overwriting new sort/filter state.
     * (Bug 3 fix — replaces the incomplete `frozenForSort == null` guard in the flow observer.)
     */
    @Volatile private var activeFlowGeneration = 0L
    private val usedTags = linkedSetOf<String>()
    private val seenMangaUrls = linkedSetOf<String>()
    private var knownTags = emptyList<String>()
    var gridFirstVisibleItemIndex = 0
        private set
    var gridFirstVisibleItemScrollOffset = 0
        private set
    var sheetFirstVisibleItemIndex = 0
        private set
    var sheetFirstVisibleItemScrollOffset = 0
        private set
    @Volatile private var visibleSectionKey: String? = null
    private val getSuggestionQueries: GetUserSuggestionQueriesUseCase = Injekt.get()
    private val feedAggregator: FeedAggregator = Injekt.get()
    private val sectionLastFetchedAt = ConcurrentHashMap<String, Long>()
    private var currentV2RefreshId = 0L
    private var nextExpandedSourceOffset = 0
    private var nextExpandedSourcePage = 1
    private var expandedQuery: String? = null
    private var expandedSourceSortOrder: SuggestionSortOrder? = null
    private var expandedSectionKey: String? = null
    private val expandedBufferedSuggestions = linkedMapOf<Long, ArrayDeque<SuggestedManga>>()
    private val expandedPassSources = linkedSetOf<Long>()
    /** Random page offset (1–3) re-rolled on every user-initiated refresh so each
     *  refresh fetches a different page from each source, guaranteeing variety. */
    @Volatile private var currentPageOffset: Int = 1
    @Volatile private var pendingRefreshMessage: String? = null
    /**
     * Guards against [onCreate] running its setup logic more than once.
     * Conductor recreates the View (and calls onViewCreated → presenter.onCreate)
     * each time the tab is re-selected, but the presenter itself is retained via
     * `by lazy`. Without this guard every tab switch would register duplicate
     * flow collectors and potentially trigger a spurious refresh.
     */
    private var isInitialized = false

    override fun onDestroy() {
        // SuggestionsController is recreated on bottom-tab switches. Keep the
        // presenter scope alive so in-flight suggestion fetches continue.
        attachView(null)
    }

    /**
     * The sort order that was most recently *committed* by the user (i.e. the
     * latest call to [setSortOrder]). Written immediately on the calling thread
     * so the next [setSortOrder] call can guard against redundant rebuilds even
     * while a previous rebuild's network calls are still in-flight.
     */
    @Volatile private var committedSortOrder: SuggestionSortOrder =
        preferences.suggestionsSortOrder().get()

    override fun onCreate() {
        super.onCreate()
        if (isInitialized) return
        isInitialized = true

        // Load persistent shown history into the session seen-set so refreshes
        // never re-show manga that appeared in a previous session.
        presenterScope.launchIO {
            shownHistoryRepository.deleteOlderThan(
                System.currentTimeMillis() - HISTORY_TTL_MILLIS,
            )
            // Bug 4 fix: only load the last 24 hours of shown history into the in-memory
            // set on startup. The full 30-day DB TTL is unchanged, but seeding the set
            // with 30 days of data meant hundreds of manga were globally filtered out,
            // making soft refresh return almost no new content after a few days.
            val cutoff = System.currentTimeMillis() - RECENT_HISTORY_SEED_MILLIS
            val history = shownHistoryRepository.getKeysShownAfter(cutoff)
            seenMangaUrls.addAll(history)
        }

        suggestionsRepository.getSuggestionsAsFlow()
            .onEach { storedList ->
                val suggestedList = storedList.filterForCurrentResultVersion()
                // Bug 3 fix: reject emissions that belong to a previous feed generation.
                // Without this guard, in-flight network requests from the previous sort can
                // insert results into the DB whose flow emission then overwrites the new state.
                if (feedGeneration.get() != activeFlowGeneration) return@onEach
                if (suggestedList.isNotEmpty() && !storedResultsMatchCurrentVersion()) return@onEach
                // While showing a snapshot-restored sort, the DB may still hold the
                // previous sort's rows. Ignore only that exact snapshot; accept later
                // worker/refresh updates so the cached UI does not stay stale forever.
                if (frozenForSort == _state.value.sortOrder) {
                    if (suggestedList.dbSignature() == frozenDbSignature) return@onEach
                    frozenForSort = null
                    frozenDbSignature = null
                }
                if (shouldKeepCurrentSuggestions(suggestedList)) return@onEach
                // During a rebuild (V1/V2 toggle, sort switch, filter change), skip empty
                // emissions so old suggestions stay visible until the first batch inserts.
                if (isRebuilding && suggestedList.isEmpty() && _state.value.suggestions.isNotEmpty()) return@onEach
                if (isRebuilding && suggestedList.isNotEmpty()) {
                    isRebuilding = false
                }
                renderSuggestedList(suggestedList)
            }
            .launchIn(presenterScope)

        networkStatus.onlineChanges()
            .onEach { online ->
                if (online) {
                    resumePausedRefreshIfNeeded()
                } else if (isForegroundRefreshing.get() || isSectionBatchFetching.get()) {
                    _state.update { state ->
                        state.copy(refreshBannerMessage = waitingForNetworkMessage())
                    }
                }
            }
            .launchIn(presenterScope)

        getManga.subscribeAll()
            .onEach { mangaList ->
                knownTags = mangaList.extractKnownTags()
                syncTagFilterState(preferences.suggestionsTagsBlacklist().get())
            }
            .launchIn(presenterScope)

        preferences.suggestionsTagsBlacklist()
            .changes()
            .onEach { blacklistedTags ->
                syncTagFilterState(blacklistedTags)
            }
            .launchIn(presenterScope)

        SuggestionsWorker.isRunningFlow(context)
            .onEach { isRunning ->
                isWorkerRefreshing = isRunning
                updateLoadingState()
            }
            .launchIn(presenterScope)

        candidateRetriever.activeNetworkSourceIdsFlow()
            .onEach { sourceIds ->
                sourceChangeRefreshJob?.cancel()
                sourceChangeRefreshJob = presenterScope.launchIO {
                    delay(SOURCE_CHANGE_DEBOUNCE_MILLIS)
                    handleActiveNetworkSourcesChanged(sourceIds)
                }
            }
            .launchIn(presenterScope)

        presenterScope.launchIO {
            // If a refresh was killed mid-flight last session (Doze / OOM / force-stop), the
            // suggestions table may hold a partially-replaced result set. Clear the flag and
            // force a fresh hard refresh so the user does not see a half-populated feed that
            // never recovers (count > 0 prevents the auto-refresh path below from triggering).
            val crashedDuringRefresh = preferences.suggestionsRefreshInFlight().get()
            if (crashedDuringRefresh) {
                preferences.suggestionsRefreshInFlight().set(false)
            }
            clearStoredResultsThatDoNotMatchMode()
            if (preferences.suggestionsV2Enabled().get()) {
                restoreV2PlanState()
            }
            if (crashedDuringRefresh) {
                isInitialLoad.set(suggestionsRepository.count(currentSuggestionsResultVersion()) == 0L)
                if (preferences.suggestionsV2Enabled().get() && !candidateRetriever.hasActiveNetworkSources()) {
                    showWaitingForSources()
                    isInitialLoad.set(false)
                } else if (!isForegroundRefreshing.get()) {
                    refresh(hardRefresh = true)
                }
            } else if (suggestionsRepository.count(currentSuggestionsResultVersion()) == 0L) {
                isInitialLoad.set(true)
                if (preferences.suggestionsV2Enabled().get()) {
                    if (!candidateRetriever.hasActiveNetworkSources()) {
                        showWaitingForSources()
                        isInitialLoad.set(false)
                    } else if (!isForegroundRefreshing.get()) {
                        refresh(hardRefresh = true)
                    }
                } else {
                    refresh()
                }
            } else if (shouldAutoRefreshStoredSuggestions(now = System.currentTimeMillis())) {
                refresh(hardRefresh = true)
            }
        }
    }

    private suspend fun shouldAutoRefreshStoredSuggestions(now: Long): Boolean =
        shouldAutoRefreshStoredSuggestions(
            newestFetchedAt = getCurrentSuggestions()
                .maxOfOrNull { it.fetchedAt },
            now = now,
        ) && !isForegroundRefreshing.get()

    private suspend fun handleActiveNetworkSourcesChanged(sourceIds: Set<Long>) {
        val previous = observedActiveNetworkSourceIds
        observedActiveNetworkSourceIds = sourceIds
        val isV2 = preferences.suggestionsV2Enabled().get()

        if (sourceIds.isEmpty()) {
            if (currentSuggestionsCount() == 0L) {
                showWaitingForSources()
            }
            return
        }

        // If the UI is currently parked on the "waiting for sources" message, retrigger a
        // refresh as soon as sources actually come online — without this the message could
        // persist for the rest of the session because previous==sourceIds skips the refresh
        // below on the very first non-empty emission.
        val isStuckWaitingForSources = _state.value.emptyMessage == waitingForSourcesMessage()

        if (previous == null) {
            if ((currentSuggestionsCount() == 0L || isStuckWaitingForSources) && !isForegroundRefreshing.get()) {
                refresh(hardRefresh = isV2)
            }
            return
        }

        if ((previous != sourceIds || isStuckWaitingForSources) && !isForegroundRefreshing.get()) {
            refresh(hardRefresh = isV2)
        }
    }

    private fun showWaitingForSources() {
        _state.update { it.copy(
            plannedSections = emptyList(),
            sectionDisplayNames = emptyMap(),
            nextBatchStartIndex = 0,
            allSectionsLoaded = false,
            hasReachedEnd = false,
            endMessage = null,
            emptyMessage = waitingForSourcesMessage(),
            isLoading = false,
            isFetching = false,
            isFetchingBatch = false,
        )}
    }

    private fun currentSuggestionsResultVersion(): Int =
        if (preferences.suggestionsV2Enabled().get()) {
            SuggestionsConfig.RESULT_VERSION_V2
        } else {
            SuggestionsConfig.RESULT_VERSION_V1
        }

    private fun currentSuggestionMode(): SuggestionResultMode =
        if (preferences.suggestionsV2Enabled().get()) {
            SuggestionResultMode.V2
        } else {
            SuggestionResultMode.V1
        }

    private fun isCurrentRefresh(session: SuggestionRefreshSession, generation: Long): Boolean =
        generation == feedGeneration.get() &&
            refreshSessions.isCurrent(session) &&
            currentSuggestionMode() == session.mode

    private fun refreshBannerMessage(session: SuggestionRefreshSession): String =
        when (session.reason) {
            SuggestionRefreshReason.Toggle -> context.getString(
                if (session.mode == SuggestionResultMode.V2) {
                    MR.strings.suggestions_switching_to_v2
                } else {
                    MR.strings.suggestions_switching_to_v1
                },
            )
            else -> context.getString(MR.strings.suggestions_refreshing)
        }

    private fun waitingForNetworkMessage(): String =
        context.getString(MR.strings.suggestions_waiting_for_network)

    private fun resumePausedRefreshIfNeeded() {
        val paused = refreshSessions.paused() ?: return
        if (currentSuggestionMode() != paused.mode || refreshJob?.isActive == true) return
        refreshSessions.clearPaused(paused)
        refresh(
            hardRefresh = paused.hardRefresh,
            clearSortCache = false,
            reason = paused.reason,
        )
    }

    private fun storedResultsMatchCurrentVersion(): Boolean =
        preferences.suggestionsResultVersion().get() == currentSuggestionsResultVersion()

    private fun List<SuggestedManga>.filterForCurrentResultVersion(): List<SuggestedManga> {
        val version = currentSuggestionsResultVersion()
        val storedVersion = preferences.suggestionsResultVersion().get()
        return filter { suggestion ->
            suggestion.resultVersion == version ||
                (
                    suggestion.resultVersion == SuggestionsConfig.RESULT_VERSION_UNKNOWN &&
                        storedVersion == version
                    )
        }
    }

    private suspend fun getCurrentSuggestions(): List<SuggestedManga> =
        suggestionsRepository.getSuggestions(currentSuggestionsResultVersion())
            .ifEmpty {
                if (storedResultsMatchCurrentVersion()) {
                    suggestionsRepository.getSuggestions()
                        .filter { it.resultVersion == SuggestionsConfig.RESULT_VERSION_UNKNOWN }
                } else {
                    emptyList()
                }
            }

    private suspend fun currentSuggestionsCount(): Long =
        suggestionsRepository.count(currentSuggestionsResultVersion())
            .takeIf { it > 0L }
            ?: getCurrentSuggestions().size.toLong()

    private suspend fun clearStoredResultsThatDoNotMatchMode(
        session: SuggestionRefreshSession? = null,
        generation: Long? = null,
    ): Boolean {
        val resultVersion = currentSuggestionsResultVersion()
        val storedSuggestionCount = suggestionsRepository.count(resultVersion)
        val hasInvalidSectionKeys = storedSuggestionCount > 0L &&
            suggestionsRepository.getSuggestions(resultVersion).any { it.sectionKey.isBlank() }
        val plannedSectionCount = if (preferences.suggestionsV2Enabled().get()) {
            plannedSectionRepository.getPlannedSections(SuggestionsConfig.RESULT_VERSION_V2).size
        } else {
            0
        }
        val shouldClear = SuggestionsModeStoragePolicy.shouldClearStoredResults(
            isV2Enabled = preferences.suggestionsV2Enabled().get(),
            storedResultVersion = preferences.suggestionsResultVersion().get(),
            storedSuggestionCount = storedSuggestionCount,
            plannedSectionCount = plannedSectionCount,
            hasInvalidSectionKeys = hasInvalidSectionKeys,
        )
        if (!shouldClear) return false
        // Session guard: if a newer refresh has superseded this caller, skip the wipe.
        // Without this, two concurrent refreshes (foreground + worker, or two foreground
        // pulls racing through the now-lock-less refresh path) could both delete the same
        // result-version rows before either has committed its replacement batch.
        if (session != null && generation != null && !isCurrentRefresh(session, generation)) return false

        suggestionsRepository.deleteByResultVersion(resultVersion)
        if (resultVersion == SuggestionsConfig.RESULT_VERSION_V2) {
            plannedSectionRepository.deleteByResultVersion(resultVersion)
        }
        preferences.suggestionsResultVersion().set(SuggestionsConfig.RESULT_VERSION_UNKNOWN)
        _state.update { it.copy(
            suggestions = it.suggestions,
            sectionDisplayNames = it.sectionDisplayNames,
            selectedSectionKey = null,
            plannedSections = it.plannedSections,
            nextBatchStartIndex = it.nextBatchStartIndex,
            allSectionsLoaded = false,
            hasReachedEnd = false,
            endMessage = null,
            emptyMessage = null,
        )}
        return true
    }

    /**
     * Refresh the feed.
     *
     * - [hardRefresh] = false (default, pull-to-refresh): **soft refresh** — rebuild
     *   the interest profile from local DB, re-order sections by updated affinity scores,
     *   re-rank stored results, and only hit the network for sections whose cache has expired.
     *   This is fast (mostly local) and means the user sees re-ordered content immediately.
     *
     * - [hardRefresh] = true: full wipe + network fetch. Used on first load or when
     *   there are no stored suggestions.
     */
    /**
     * Returns true if the last refresh failed with a network-related error.
     * Used by [SuggestionsController]'s [ConnectivityManager.NetworkCallback]
     * to decide whether to auto-refresh when connectivity is restored.
     */
    fun hasNetworkError(): Boolean {
        val msg = _state.value.emptyMessage ?: return false
        return msg == refreshErrorMessage() ||
            msg == loadMoreErrorMessage() ||
            msg == noMatchesMessage() ||
            // waitingForSources is a transient state (no enabled sources reported during init).
            // Treat it as recoverable so the ConnectivityManager callback and source-change
            // flow can retrigger a refresh once sources actually come online.
            msg == waitingForSourcesMessage()
    }

    fun refresh(
        hardRefresh: Boolean = false,
        clearSortCache: Boolean = true,
        reason: SuggestionRefreshReason = SuggestionRefreshReason.Manual,
    ) {
        // User explicitly wants fresh data — discard all snapshots and unfreeze the
        // DB flow observer so it can write new results to state as they arrive.
        frozenForSort = null
        frozenDbSignature = null
        if (clearSortCache) {
            sortSnapshotCache.clear()
        }
        pendingRefreshMessage = null
        SuggestionsWorker.cancelManual(context)
        if (hardRefresh) {
            seenMangaUrls.clear()      // Bug 4 fix: allow full candidate re-evaluation on hard refresh
        }
        refreshJob?.cancel()
        refreshJob = null
        val generation = feedGeneration.incrementAndGet()
        activeFlowGeneration = generation  // Bug 3: record generation for DB observer guard
        isForegroundRefreshing.set(true)
        currentPageOffset = Random.nextInt(1, 8)   // randomize page 1–7 on each refresh
        val pageOffset = currentPageOffset
        val mode = currentSuggestionMode()
        isPageFetching.set(false)
        isSectionBatchFetching.set(false)
        usedTags.clear()
        // seenMangaUrls is NOT cleared — it survives refreshes via persistent history

        // Load persisted tag rotation so we pick up where we left off
        usedTags.addAll(preferences.usedSuggestionTags().get())
        val v2RefreshTargetSectionKey = if (!hardRefresh && preferences.suggestionsV2Enabled().get()) {
            manualRefreshTargetSectionKey()
        } else {
            null
        }
        val initialRefreshingSectionKeys = if (v2RefreshTargetSectionKey != null) {
            setOfNotNull("discovery", COLD_START_DISCOVERY_SECTION_KEY, v2RefreshTargetSectionKey)
        } else {
            emptySet()
        }
        val session = refreshSessions.start(
            mode = mode,
            sortOrder = _state.value.sortOrder,
            hardRefresh = hardRefresh,
            targetSectionKey = v2RefreshTargetSectionKey,
            pageOffset = pageOffset,
            reason = reason,
        )

        _state.update { state ->
            val hasRenderedSuggestions = state.suggestions.values.any { section -> section.isNotEmpty() }
            state.copy(
                emptyMessage = null,
                refreshBannerMessage = refreshBannerMessage(session).takeIf { hasRenderedSuggestions },
                staleSnapshotMode = session.mode.resultVersion.takeIf { hasRenderedSuggestions },
                isPausedForNetwork = false,
                plannedSections = state.plannedSections.takeIf { hasRenderedSuggestions } ?: emptyList(),
                sectionDisplayNames = state.sectionDisplayNames.takeIf { hasRenderedSuggestions } ?: emptyMap(),
                nextBatchStartIndex = state.nextBatchStartIndex.takeIf { hasRenderedSuggestions } ?: 0,
                isFetchingBatch = false,
                allSectionsLoaded = state.allSectionsLoaded.takeIf { hasRenderedSuggestions } ?: false,
                hasReachedEnd = state.hasReachedEnd.takeIf { hasRenderedSuggestions } ?: false,
                endMessage = state.endMessage.takeIf { hasRenderedSuggestions },
                refreshingSectionKeys = initialRefreshingSectionKeys,
                sheetSectionKey = null,
                sheetResults = emptyList(),
                sheetIsLoading = false,
                sheetError = null,
                sheetSuppressed = false,
            )
        }
        updateLoadingState()

        refreshJob = presenterScope.launchIO {
            var pausedForNetwork = false
            try {
                // Foreground refresh runs without the SuggestionsRefreshCoordinator mutex:
                // OkHttp source fetches are not cooperatively cancellable, so a stuck prior
                // refresh would otherwise hold the lock past `MAX_FOREGROUND_LOCK_WAIT_MS`
                // and surface a misleading "another refresh is running" message to the user.
                // Concurrent writes are still safe — every DB-mutating step below is gated
                // by `isCurrentRefresh(session, generation)`, so a superseded refresh exits
                // before committing. Background workers retain the mutex (see SuggestionsWorker).
                if (!isCurrentRefresh(session, generation)) return@launchIO
                // Persist an "in flight" marker covering the wipe-then-write window of both
                // V1 and V2 refresh paths (clearStoredResultsThatDoNotMatchMode + replaceAll).
                // If a process kill happens between wipe and write, onCreate sees this flag
                // on next launch and forces a recovery hard refresh.
                preferences.suggestionsRefreshInFlight().set(true)
                clearStoredResultsThatDoNotMatchMode(session = session, generation = generation)
                if (session.mode == SuggestionResultMode.V2) {
                    if (!candidateRetriever.hasActiveNetworkSources()) {
                        throw RefreshBlocked(waitingForSourcesMessage())
                    }
                    val hasExistingContent = currentSuggestionsCount() > 0L
                    if (!hardRefresh && hasExistingContent) {
                        // Soft path: re-rank locally, then refresh the currently visible section.
                        softRefreshV2(
                            generation = generation,
                            pageOffset = pageOffset,
                            session = session,
                            refreshTargetSectionKey = v2RefreshTargetSectionKey,
                        )
                    } else {
                        // Hard path: wipe DB and do a full network fetch.
                        sectionLastFetchedAt.clear()
                        refreshV2(generation = generation, pageOffset = pageOffset, session = session)
                    }
                } else {
                    sectionLastFetchedAt.clear()
                    val suggestions = buildFreshSuggestions(pageOffset)
                    if (!isCurrentRefresh(session, generation)) return@launchIO
                    suggestionsRepository.replaceAll(
                        suggestions,
                        resultVersion = session.mode.resultVersion,
                        refreshSessionId = session.sessionId,
                    )
                    // Generation re-check AFTER the DB write commits. Without this, a cancel
                    // arriving after the version write but before the in-flight prefs flush
                    // could repoison `suggestionsResultVersion` (set to UNKNOWN by a V2
                    // toggle) back to V1, leaving the next launch reading V1 rows under
                    // a V2 expectation.
                    if (!isCurrentRefresh(session, generation)) return@launchIO
                    preferences.suggestionsResultVersion().set(SuggestionsConfig.RESULT_VERSION_V1)
                    renderStoredSuggestions()
                    // Persist shown manga into the 30-day history
                    shownHistoryRepository.insertAll(
                        suggestions.map { it.source to it.url },
                    )
                    // Persist tag rotation — reset is handled inside buildFreshSuggestions
                    preferences.usedSuggestionTags().set(usedTags.toSet())
                    _state.update { it.copy(emptyMessage = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransientSuggestionNetworkException) {
                pausedForNetwork = refreshSessions.pauseIfCurrent(session)
                if (pausedForNetwork && isCurrentRefresh(session, generation)) {
                    isForegroundRefreshing.set(false)
                    isSectionBatchFetching.set(false)
                    isPageFetching.set(false)
                    _state.update { state ->
                        state.copy(
                            isPausedForNetwork = true,
                            refreshBannerMessage = waitingForNetworkMessage(),
                            refreshingSectionKeys = emptySet(),
                            isFetching = false,
                            isFetchingBatch = false,
                            isLoading = false,
                        )
                    }
                }
            } catch (e: RefreshBlocked) {
                if (isCurrentRefresh(session, generation)) {
                    val hasStoredSuggestions = currentSuggestionsCount() > 0L
                    if (!hasStoredSuggestions && session.mode == SuggestionResultMode.V2) {
                        plannedSectionRepository.deleteByResultVersion(session.mode.resultVersion)
                    }
                    if (!hasStoredSuggestions) {
                        preferences.suggestionsResultVersion().set(SuggestionsConfig.RESULT_VERSION_UNKNOWN)
                    }
                    _state.update { it.copy(
                        plannedSections = if (hasStoredSuggestions) it.plannedSections else emptyList(),
                        sectionDisplayNames = if (hasStoredSuggestions) it.sectionDisplayNames else emptyMap(),
                        nextBatchStartIndex = if (hasStoredSuggestions) it.nextBatchStartIndex else 0,
                        allSectionsLoaded = if (hasStoredSuggestions) it.allSectionsLoaded else false,
                        hasReachedEnd = false,
                        endMessage = null,
                        emptyMessage = e.userMessage,
                    isFetchingBatch = false,
                    isFetching = false,
                    refreshingSectionKeys = emptySet(),
                ) }
                }
            } catch (_: Exception) {
                if (isCurrentRefresh(session, generation)) {
                    // Only surface a refresh-failure banner when nothing landed in the cache.
                    // Per-section work now catches its own throwables; if some sections still
                    // inserted before an orchestration-level failure, the user sees those
                    // results instead of an error overlay that hides them.
                    val hasAnySuggestions = _state.value.suggestions.values.any { it.isNotEmpty() }
                    if (!hasAnySuggestions) {
                        _state.update { it.copy(
                            emptyMessage = refreshErrorMessage(),
                        )}
                    }
                }
            } finally {
                // Clear the in-flight marker unconditionally — even if the refresh was cancelled
                // by a newer generation, the wipe-then-write window has closed for THIS coroutine.
                preferences.suggestionsRefreshInFlight().set(false)
                if (!pausedForNetwork && isCurrentRefresh(session, generation)) {
                    refreshSessions.finishIfCurrent(session)
                    isInitialLoad.set(false)
                    isForegroundRefreshing.set(false)
                    isRebuilding = false
                    _state.update {
                        it.copy(
                            refreshingSectionKeys = emptySet(),
                            refreshBannerMessage = null,
                            staleSnapshotMode = null,
                            isPausedForNetwork = false,
                        )
                    }
                    updateLoadingState()
                    try {
                        syncSelectedSectionKeyWithStoredSuggestions()
                    } catch (_: Exception) {
                    }
                    val message = pendingRefreshMessage
                    pendingRefreshMessage = null
                    if (message != null && _state.value.suggestions.isEmpty()) {
                        _state.update { it.copy(emptyMessage = message) }
                        presenterScope.launchIO {
                            delay(2_000L)
                            _state.update { state ->
                                state.copy(emptyMessage = state.emptyMessage.takeUnless { it == message })
                            }
                        }
                    }
                }
            }
        }
    }

    fun loadNextPage() {
        // While showing a snapshot, pagination is disabled — the cached content is
        // complete for that sort and we don't want to mix it with live DB results.
        if (frozenForSort != null) return
        if (preferences.suggestionsV2Enabled().get()) {
            loadNextSectionBatch()
            return
        }
        loadNextPage(includeSourceSection = false)
    }

    private fun loadNextPage(includeSourceSection: Boolean, clearExisting: Boolean = false) {
        val currentState = _state.value
        if (currentState.hasReachedEnd || currentState.selectedSectionKey != null) return
        if (!isPageFetching.compareAndSet(false, true)) return
        val generation = feedGeneration.get()
        val pageOffset = currentPageOffset
        updateLoadingState()

        presenterScope.launchIO {
            try {
                // Pagination is best-effort; if the refresh coordinator is held longer than
                // MAX_FOREGROUND_LOCK_WAIT_MS we abort the page-load instead of piling on
                // the queue. The user can scroll again to retry.
                SuggestionsRefreshCoordinator.withLockOrSkip {
                    if (generation != feedGeneration.get()) return@withLockOrSkip
                    if (clearExisting) {
                        suggestionsRepository.deleteAll()
                    }
                    val page = feedAggregator.fetchPage(
                        suggestionQueries = getSuggestionQueries.execute(),
                        usedTags = usedTags.toSet(),
                        seenMangaUrls = seenMangaUrls.toSet(),
                        currentSortOrder = _state.value.sortOrder,
                        includeSourceSection = includeSourceSection,
                        pageOffset = pageOffset,
                    )
                    if (generation != feedGeneration.get()) return@withLockOrSkip
                    usedTags.addAll(page.usedTags)

                    val newSuggestions = page.suggestions
                        .filterNot { it.memoryKey() in seenMangaUrls }
                    val nextRank = currentSuggestionsCount()
                    val rankedSuggestions = newSuggestions.withDisplayRanks(nextRank)
                    if (rankedSuggestions.isNotEmpty()) {
                        suggestionsRepository.insertSuggestions(
                            rankedSuggestions,
                            resultVersion = currentSuggestionsResultVersion(),
                        )
                        renderStoredSuggestions()
                        seenMangaUrls.addAll(rankedSuggestions.map { it.memoryKey() })
                        // Persist shown manga into the 30-day history
                        shownHistoryRepository.insertAll(
                            rankedSuggestions.map { it.source to it.url },
                        )
                    }

                    _state.update { it.copy(
                        hasReachedEnd = page.hasReachedEnd,
                        endMessage = endOfFeedMessage().takeIf { page.hasReachedEnd },
                        emptyMessage = null,
                    )}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == feedGeneration.get()) {
                    _state.update { it.copy(
                        emptyMessage = loadMoreErrorMessage(),
                    )}
                }
            } finally {
                if (generation == feedGeneration.get()) {
                    isPageFetching.set(false)
                    updateLoadingState()
                }
            }
        }
    }

    fun setSelectedSectionKey(sectionKey: String?) {
        _state.update { state ->
            state.copy(selectedSectionKey = sectionKey?.takeIf { it in state.suggestions.keys })
        }
    }

    fun saveGridScrollPosition(index: Int, scrollOffset: Int) {
        gridFirstVisibleItemIndex = index.coerceAtLeast(0)
        gridFirstVisibleItemScrollOffset = scrollOffset.coerceAtLeast(0)
    }

    fun saveSheetScrollPosition(index: Int, scrollOffset: Int) {
        sheetFirstVisibleItemIndex = index.coerceAtLeast(0)
        sheetFirstVisibleItemScrollOffset = scrollOffset.coerceAtLeast(0)
    }

    fun setVisibleSectionKey(sectionKey: String?) {
        visibleSectionKey = sectionKey
    }

    private fun manualRefreshTargetSectionKey(): String? {
        val state = _state.value
        return state.selectedSectionKey
            ?.takeIf { selected -> selected in state.suggestions.keys || state.plannedSections.any { it.sectionKey == selected } }
            ?: visibleSectionKey
                ?.takeIf { visible -> visible in state.suggestions.keys || state.plannedSections.any { it.sectionKey == visible } }
            ?: state.suggestions.keys.firstOrNull()
            ?: state.plannedSections.firstOrNull()?.sectionKey
    }

    fun setSortOrder(sortOrder: SuggestionSortOrder) {
        // Guard against a stale state check during in-flight network requests:
        // compare against the committed var, not state.sortOrder, which may lag.
        if (committedSortOrder == sortOrder) return
        // Commit immediately so any concurrent call within the debounce window
        // sees the latest desired order and skips a redundant rebuild.
        committedSortOrder = sortOrder
        // Debounce: cancel the pending rebuild if the user taps again within 300 ms.
        // Only the last selection within the window fires a network request.
        sortDebounceJob?.cancel()
        sortDebounceJob = presenterScope.launchIO {
            delay(300)
            // ── Snapshot current results before switching away ─────────────────
            val currentState = _state.value
            if (currentState.suggestions.isNotEmpty()) {
                sortSnapshotCache[currentState.sortOrder] = SortSnapshot(
                    suggestions        = currentState.suggestions,
                    plannedSections    = currentState.plannedSections,
                    nextBatchStartIndex = currentState.nextBatchStartIndex,
                    allSectionsLoaded  = currentState.allSectionsLoaded,
                    hasReachedEnd      = currentState.hasReachedEnd,
                    endMessage         = currentState.endMessage,
                )
            }
            // ── Restore from cache if we've loaded this sort before ────────────
            val cached = sortSnapshotCache[sortOrder]
            if (cached != null) {
                val dbSignatureAtRestore = getCurrentSuggestions().dbSignature()
                // Cancel any in-flight requests from the previous rebuild so they
                // don't write stale results to state or the DB.
                refreshJob?.cancel()
                val generation = feedGeneration.incrementAndGet()
                activeFlowGeneration = generation
                // The cached restore cancels any active refresh above, so clear the
                // foreground flag here; the cancelled job's stale generation will skip cleanup.
                isForegroundRefreshing.set(false)
                isPageFetching.set(false)
                isSectionBatchFetching.set(false)
                // Freeze only against the DB snapshot that existed before this cached
                // restore. Any later DB change is treated as fresh and shown.
                frozenForSort = sortOrder
                frozenDbSignature = dbSignatureAtRestore
                preferences.suggestionsSortOrder().set(sortOrder)
                val selectedSectionKey = currentState.selectedSectionKey
                    ?.takeIf { selected -> selected in cached.suggestions.keys }
                _state.update { it.copy(
                    sortOrder          = sortOrder,
                    suggestions        = cached.suggestions,
                    selectedSectionKey = selectedSectionKey,
                    isLoading          = false,
                    isFetching         = false,
                    isFetchingBatch    = false,
                    plannedSections    = cached.plannedSections,
                    nextBatchStartIndex = cached.nextBatchStartIndex,
                    allSectionsLoaded  = cached.allSectionsLoaded,
                    hasReachedEnd      = cached.hasReachedEnd,
                    endMessage         = cached.endMessage,
                    emptyMessage       = null,
                    sheetSectionKey    = null,
                    sheetResults       = emptyList(),
                    sheetIsLoading     = false,
                    sheetError         = null,
                    sheetSuppressed    = false,
                )}
            } else {
                // No cache for this sort — rebuild from network (existing path).
                preferences.suggestionsSortOrder().set(sortOrder)
                rebuildFeed(sortOrder = sortOrder)
            }
        }
    }

    fun isSuggestionsV2Enabled(): Boolean =
        preferences.suggestionsV2Enabled().get()

    fun setSuggestionsV2Enabled(enabled: Boolean): Boolean {
        if (preferences.suggestionsV2Enabled().get() == enabled) return false
        preferences.suggestionsV2Enabled().set(enabled)
        preferences.suggestionsResultVersion().set(SuggestionsConfig.RESULT_VERSION_UNKNOWN)
        // Pin availability flips with V2; refresh the filter-sheet view-model so an open sheet
        // sees the new pin-enabled state on its next show.
        syncTagFilterState(
            blacklistedTags = preferences.suggestionsTagsBlacklist().get(),
            pinnedTags = preferences.suggestionsPinnedTags().get(),
        )
        rebuildFeed(
            sortOrder = _state.value.sortOrder,
            clearRenderedResults = true,
            reason = SuggestionRefreshReason.Toggle,
        )
        return true
    }

    fun toggleSuggestionsV2Enabled(): Boolean {
        return setSuggestionsV2Enabled(!preferences.suggestionsV2Enabled().get())
    }

    fun showTagFilterSheet() {
        syncTagFilterState(
            blacklistedTags = preferences.suggestionsTagsBlacklist().get(),
            pinnedTags = preferences.suggestionsPinnedTags().get(),
        )
        _state.update { it.copy(isTagFilterSheetVisible = true) }
    }

    fun dismissTagFilterSheet() {
        _state.update { it.copy(isTagFilterSheetVisible = false) }
    }

    fun applyTagFilters(pendingBlacklist: Set<String>, pendingPinned: Set<String>) {
        val currentBlacklist = preferences.suggestionsTagsBlacklist().get()
        // Pin is V1-only; ignore submitted pins when V2 is active so the UI cannot accidentally
        // write a pin set the V2 path would silently ignore.
        val effectivePinned = if (isSuggestionsV2Enabled()) emptySet() else pendingPinned
        val currentPinned = preferences.suggestionsPinnedTags().get()
        val blacklistChanged = currentBlacklist != pendingBlacklist
        val pinnedChanged = currentPinned != effectivePinned
        if (!blacklistChanged && !pinnedChanged) {
            _state.update { it.copy(isTagFilterSheetVisible = false) }
            return
        }
        if (blacklistChanged) preferences.suggestionsTagsBlacklist().set(pendingBlacklist)
        if (pinnedChanged) preferences.suggestionsPinnedTags().set(effectivePinned)
        syncTagFilterState(pendingBlacklist, effectivePinned)
        _state.update { it.copy(isTagFilterSheetVisible = false) }
        if (blacklistChanged && isSuggestionsV2Enabled()) {
            applyBlacklistIncrementally(pendingBlacklist)
        } else {
            rebuildFeed(sortOrder = _state.value.sortOrder)
        }
    }

    private fun applyBlacklistIncrementally(pendingBlacklist: Set<String>) {
        refreshJob?.cancel()
        val generation = feedGeneration.incrementAndGet()
        activeFlowGeneration = generation
        val session = refreshSessions.start(
            mode = SuggestionResultMode.V2,
            sortOrder = _state.value.sortOrder,
            hardRefresh = false,
            targetSectionKey = manualRefreshTargetSectionKey(),
            pageOffset = currentPageOffset,
            reason = SuggestionRefreshReason.Manual,
        )
        isForegroundRefreshing.set(true)
        isPageFetching.set(false)
        isSectionBatchFetching.set(false)
        updateLoadingState()

        refreshJob = presenterScope.launchIO {
            var pausedForNetwork = false
            try {
                val now = System.currentTimeMillis()
                syncLegacyTagStateForV2(now)
                if (!isCurrentRefresh(session, generation)) return@launchIO

                val profiles = tagProfileRepository.getAllProfiles()
                val plannedSections = sectionPlanner.plan(
                    profiles = profiles,
                    sortOrder = _state.value.sortOrder,
                    now = now,
                )
                plannedSectionRepository.replaceAll(
                    plannedSections,
                    resultVersion = session.mode.resultVersion,
                    refreshSessionId = session.sessionId,
                )

                val plannedKeys = plannedSections.map { it.sectionKey }.toSet()
                val canonicalBlacklist = metadataVerifier.canonicalizeBlacklist(pendingBlacklist)
                val existingSuggestions = getCurrentSuggestions()
                    .filter { it.sectionKey in plannedKeys }
                val allowedSuggestions = metadataVerifier.filterSuggestions(
                    suggestions = existingSuggestions,
                    blacklistedTags = canonicalBlacklist,
                )
                val allowedKeys = allowedSuggestions.map { it.source to it.url }.toSet()
                val affectedSectionKeys = existingSuggestions
                    .filterNot { it.source to it.url in allowedKeys }
                    .map { it.sectionKey }
                    .toSet()

                preferences.suggestionsResultVersion().set(SuggestionsConfig.RESULT_VERSION_V2)
                suggestionsRepository.replaceAll(
                    allowedSuggestions,
                    resultVersion = session.mode.resultVersion,
                    refreshSessionId = session.sessionId,
                )
                renderStoredSuggestions()
                sectionLastFetchedAt.clear()

                val displayNames = plannedSections.associate { it.sectionKey to it.displayReason }
                val loadedSectionKeys = allowedSuggestions.map { it.sectionKey }.toSet()
                val loadedPrefixSize = SectionBatcher.contiguousLoadedPrefixSize(
                    plannedSections = plannedSections,
                    loadedSectionKeys = loadedSectionKeys,
                )
                _state.update { state ->
                    state.copy(
                        plannedSections = plannedSections,
                        sectionDisplayNames = displayNames,
                        nextBatchStartIndex = loadedPrefixSize,
                        allSectionsLoaded = loadedPrefixSize >= plannedSections.size,
                        hasReachedEnd = false,
                        endMessage = null,
                        emptyMessage = null,
                    )
                }

                val targetKey = manualRefreshTargetSectionKey()
                val targetSections = plannedSections
                    .filter { section ->
                        section.sectionKey in affectedSectionKeys ||
                            section.sectionKey == targetKey ||
                            section.type == SectionType.DISCOVERY
                    }
                    .take(2)
                if (targetSections.isEmpty()) return@launchIO

                _state.update { it.copy(refreshingSectionKeys = targetSections.map { section -> section.sectionKey }.toSet()) }
                val rankingContext = suggestionRanker.buildRankingContext()
                val refreshId = preferences.suggestionsTotalRefreshCount().get().toLong()
                for (section in targetSections) {
                    if (!isCurrentRefresh(session, generation)) return@launchIO
                    refreshV2SingleSection(
                        section = section,
                        generation = generation,
                        pageOffset = currentPageOffset,
                        refreshId = refreshId,
                        rankingContext = rankingContext,
                        now = now,
                        session = session,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransientSuggestionNetworkException) {
                pausedForNetwork = refreshSessions.pauseIfCurrent(session)
                if (isCurrentRefresh(session, generation)) {
                    _state.update { it.copy(refreshBannerMessage = waitingForNetworkMessage(), isPausedForNetwork = true) }
                }
            } catch (_: Exception) {
                if (isCurrentRefresh(session, generation) && _state.value.suggestions.isEmpty()) {
                    _state.update { it.copy(emptyMessage = refreshErrorMessage()) }
                }
            } finally {
                if (!pausedForNetwork && isCurrentRefresh(session, generation)) {
                    refreshSessions.finishIfCurrent(session)
                    isForegroundRefreshing.set(false)
                    isSectionBatchFetching.set(false)
                    _state.update {
                        it.copy(
                            refreshingSectionKeys = emptySet(),
                            refreshBannerMessage = null,
                            isPausedForNetwork = false,
                        )
                    }
                    updateLoadingState()
                }
            }
        }
    }

    fun expandSection(sectionKey: String) {
        val query = extractQueryFromSection(sectionKey)
        val sourceSortOrder = sourceSortOrderForExpandableSection(sectionKey, _state.value.sortOrder)
        if (query == null && sourceSortOrder == null) return
        if (_state.value.sheetIsLoading) return

        expandedQuery = query
        expandedSourceSortOrder = sourceSortOrder
        expandedSectionKey = sectionKey
        resetExpandedPaging()
        saveSheetScrollPosition(0, 0)

        _state.update { it.copy(
            sheetSectionKey = sectionKey,
            sheetResults = emptyList(),
            sheetIsLoading = true,
            sheetHasMore = false,
            sheetError = null,
        )}

        val currentSeenUrls = seenMangaUrls.toSet()

        presenterScope.launchIO {
            try {
                val page = if (sourceSortOrder != null) {
                    feedAggregator.fetchExpandedSourceSectionProgressively(
                        sortOrder = sourceSortOrder,
                        seenMangaUrls = currentSeenUrls,
                        sourceOffset = 0,
                        sourcePage = nextExpandedSourcePage,
                        sourceLimit = SuggestionsConfig.EXPANDED_SOURCE_BATCH_SIZE,
                    ) { page ->
                        if (expandedSectionKey == sectionKey) {
                            nextExpandedSourceOffset = page.nextSourceOffset
                            nextExpandedSourcePage = page.nextSourcePage
                            appendExpandedPage(page)
                        }
                    }
                } else {
                    feedAggregator.fetchExpandedSectionProgressively(
                        query = query ?: return@launchIO,
                        seenMangaUrls = currentSeenUrls,
                        sourceOffset = 0,
                        sourcePage = nextExpandedSourcePage,
                        sourceLimit = SuggestionsConfig.EXPANDED_SOURCE_BATCH_SIZE,
                    ) { page ->
                        if (expandedSectionKey == sectionKey) {
                            nextExpandedSourceOffset = page.nextSourceOffset
                            nextExpandedSourcePage = page.nextSourcePage
                            appendExpandedPage(page)
                        }
                    }
                }
                nextExpandedSourceOffset = page.nextSourceOffset
                nextExpandedSourcePage = page.nextSourcePage
                _state.update {
                    if (expandedSectionKey != sectionKey) {
                        it
                    } else {
                        it.copy(
                            sheetIsLoading = false,
                            sheetHasMore = page.hasMoreSources || hasExpandedBufferedSuggestions(),
                        )
                    }
                }
            } catch (_: Exception) {
                _state.update {
                    if (expandedSectionKey != sectionKey) {
                        it
                    } else {
                        it.copy(
                            sheetIsLoading = false,
                            sheetError = context.getString(MR.strings.suggestions_expand_error),
                        )
                    }
                }
            }
        }
    }

    fun loadMoreExpandedSection() {
        val query = expandedQuery
        val sourceSortOrder = expandedSourceSortOrder
        if (query == null && sourceSortOrder == null) return
        val sectionKey = expandedSectionKey ?: return
        if (_state.value.sheetIsLoadingMore) return

        _state.update { it.copy(sheetIsLoadingMore = true) }

        val currentSeenUrls = seenMangaUrls.toSet()

        presenterScope.launchIO {
            if (drainExpandedBuffers()) {
                _state.update { it.copy(
                    sheetIsLoadingMore = false,
                    sheetHasMore = true,
                ) }
                return@launchIO
            }
            try {
                val page = if (sourceSortOrder != null) {
                    feedAggregator.fetchExpandedSourceSectionProgressively(
                        sortOrder = sourceSortOrder,
                        seenMangaUrls = currentSeenUrls,
                        sourceOffset = nextExpandedSourceOffset,
                        sourcePage = nextExpandedSourcePage,
                        sourceLimit = SuggestionsConfig.EXPANDED_SOURCE_BATCH_SIZE,
                    ) { page ->
                        if (expandedSectionKey == sectionKey) {
                            nextExpandedSourceOffset = page.nextSourceOffset
                            nextExpandedSourcePage = page.nextSourcePage
                            appendExpandedPage(page)
                        }
                    }
                } else {
                    feedAggregator.fetchExpandedSectionProgressively(
                        query = query ?: return@launchIO,
                        seenMangaUrls = currentSeenUrls,
                        sourceOffset = nextExpandedSourceOffset,
                        sourcePage = nextExpandedSourcePage,
                        sourceLimit = SuggestionsConfig.EXPANDED_SOURCE_BATCH_SIZE,
                    ) { page ->
                        if (expandedSectionKey == sectionKey) {
                            nextExpandedSourceOffset = page.nextSourceOffset
                            nextExpandedSourcePage = page.nextSourcePage
                            appendExpandedPage(page)
                        }
                    }
                }
                nextExpandedSourceOffset = page.nextSourceOffset
                nextExpandedSourcePage = page.nextSourcePage
                _state.update {
                    if (expandedSectionKey != sectionKey) {
                        it
                    } else {
                        it.copy(
                            sheetIsLoadingMore = false,
                            sheetHasMore = page.hasMoreSources || hasExpandedBufferedSuggestions(),
                        )
                    }
                }
            } catch (_: Exception) {
                _state.update {
                    if (expandedSectionKey != sectionKey) {
                        it
                    } else {
                        it.copy(sheetIsLoadingMore = false)
                    }
                }
            }
        }
    }

    fun dismissExpandSheet() {
        if (!shouldCloseExpandedSheetOnDismiss(_state.value.sheetSuppressed)) return
        expandedQuery = null
        expandedSourceSortOrder = null
        expandedSectionKey = null
        resetExpandedPaging()
        saveSheetScrollPosition(0, 0)
        _state.update { it.copy(
            sheetSectionKey = null,
            sheetResults = emptyList(),
            sheetIsLoading = false,
            sheetError = null,
            sheetSuppressed = false,
        )}
    }

    /**
     * Hides the sheet from the Compose tree without clearing its data.
     * Called when navigating forward to MangaDetailsController so the sheet's
     * internal BackHandler doesn't intercept the return back-press.
     */
    fun suppressExpandSheet() {
        if (_state.value.sheetSectionKey == null) return
        _state.update { it.copy(sheetSuppressed = true) }
    }

    /**
     * Makes the sheet visible again. Called when SuggestionsController re-enters
     * the foreground (onChangeStarted isEnter).
     */
    fun restoreExpandSheet() {
        if (_state.value.sheetSectionKey == null) return
        _state.update { it.copy(sheetSuppressed = false) }
    }

    private fun rebuildFeed(
        sortOrder: SuggestionSortOrder,
        clearRenderedResults: Boolean = false,
        reason: SuggestionRefreshReason = SuggestionRefreshReason.Manual,
    ) {
        // A non-sort rebuild (filter/source/V2-toggle change) invalidates all snapshots
        // because the result set will differ regardless of sort order.  Also unfreeze
        // the DB flow observer so new results are written to state as they arrive.
        frozenForSort = null
        frozenDbSignature = null
        sortSnapshotCache.clear()
        refreshJob?.cancel()
        isForegroundRefreshing.set(false)
        isPageFetching.set(false)
        isSectionBatchFetching.set(false)
        // Keep old suggestions visible during rebuild — the DB flow observer will skip
        // empty emissions while isRebuilding is true, preventing a blank-screen flash.
        isRebuilding = true
        val shouldClearSelection = clearRenderedResults || !storedResultsMatchCurrentVersion()
        _state.update { it.copy(
            suggestions = it.suggestions,
            selectedSectionKey = if (shouldClearSelection) null else it.selectedSectionKey,
            isLoading = false,
            isFetching = false,
            isFetchingBatch = false,
            endMessage = null,
            emptyMessage = null,
            sortOrder = sortOrder,
            // Clear V2 planned-layout state so a V1<->V2 toggle (or filter/source change)
            // doesn't leave stale V2 section headers/skeletons rendering above V1 results.
            plannedSections = emptyList(),
            sectionDisplayNames = emptyMap(),
            nextBatchStartIndex = 0,
            allSectionsLoaded = false,
            hasReachedEnd = false,
            refreshingSectionKeys = emptySet(),
            sheetSectionKey = null,
            sheetResults = emptyList(),
            sheetIsLoading = false,
            sheetError = null,
            sheetSuppressed = false,
        )}
        refresh(hardRefresh = true, clearSortCache = false, reason = reason)
    }

    suspend fun getOrCreateLocalManga(manga: Manga): Manga? {
        manga.id?.let { return manga }

        val localManga = getManga.awaitByUrlAndSource(manga.url, manga.source)
        if (localManga != null) {
            if (localManga.title.isBlank() || localManga.thumbnail_url == null && manga.thumbnail_url != null) {
                updateManga.await(
                    MangaUpdate(
                        id = localManga.id ?: return localManga,
                        title = manga.title.takeIf { localManga.title.isBlank() },
                        thumbnailUrl = manga.thumbnail_url.takeIf { localManga.thumbnail_url == null },
                    ),
                )
                localManga.title = manga.title.takeIf { localManga.title.isBlank() } ?: localManga.title
                localManga.thumbnail_url = localManga.thumbnail_url ?: manga.thumbnail_url
            }
            return localManga
        }

        return Manga.create(manga.url, manga.title, manga.source).apply {
            thumbnail_url = manga.thumbnail_url
            initialized = manga.initialized
            id = insertManga.await(this)
        }.takeIf { it.id != null }
    }

    private suspend fun buildFreshSuggestions(pageOffset: Int = 1): List<SuggestedManga> {
        val now = System.currentTimeMillis()
        interestProfileBuilder.buildProfile(now)
        syncLegacyTagStateForV2(now)
        val suggestionQueries = getSuggestionQueries.execute()

        // Tag rotation reset: if usedTags has covered every available tag query,
        // the rotation is exhausted → clear it so all tags become eligible again.
        // This is tag-count-aware: works correctly whether the user has 5 or 500 tags.
        val availableTagKeys = suggestionQueries
            .map { it.query.trim().lowercase() }
            .toSet()
        val unusedTagKeys = availableTagKeys - usedTags.map { it.trim().lowercase() }.toSet()
        if (availableTagKeys.isNotEmpty() && unusedTagKeys.isEmpty()) {
            usedTags.clear()
            preferences.usedSuggestionTags().set(emptySet())
        }

        val page = feedAggregator.fetchPage(
            suggestionQueries = suggestionQueries,
            usedTags = usedTags.toSet(),
            seenMangaUrls = seenMangaUrls.toSet(),
            currentSortOrder = _state.value.sortOrder,
            includeSourceSection = true,
            pageOffset = pageOffset,
        )
        usedTags.addAll(page.usedTags)

        val suggestions = page.suggestions.withDisplayRanks(startRank = 0L)
        seenMangaUrls.addAll(suggestions.map { it.memoryKey() })
        rememberDisplayedSuggestionSources(suggestions)
        _state.update { it.copy(
            hasReachedEnd = page.hasReachedEnd,
            endMessage = endOfFeedMessage().takeIf { page.hasReachedEnd },
        )}
        if (suggestions.isEmpty()) {
            throw RefreshBlocked(
                when {
                    page.hasReachedEnd ->
                        endOfFeedMessage()
                    else ->
                        noMatchesMessage()
                },
            )
        }
        return suggestions
    }

    /**
     * **Soft refresh** — re-rank existing suggestions by updated affinity scores without
     * a full network fetch. Called by [refresh] when the DB already has content.
     *
     * 1. Rebuild the interest profile from local DB (fast, offline).
     * 2. Re-plan sections in new affinity order (e.g. Romance surpassed Isekai → it moves up).
     * 3. Re-rank the existing stored suggestions using the new section order.
     * 4. Persist the re-ordered list and update state so the UI reorders instantly.
     * 5. For pull refresh, re-fetch the selected/visible section. If no target is
     *    known, fetch any sections whose cache has expired.
     */
    private suspend fun softRefreshV2(
        generation: Long,
        pageOffset: Int,
        session: SuggestionRefreshSession,
        refreshTargetSectionKey: String?,
    ) {
        val now = System.currentTimeMillis()
        debugLog.add(
            LogType.REFRESH_MODE,
            "Soft refresh - re-ranking locally, target=${refreshTargetSectionKey ?: "stale-sections"}",
        )

        // Step 1: Rebuild profile from local DB (no network call).
        interestProfileBuilder.buildProfile(now)
        if (!isCurrentRefresh(session, generation)) return
        syncLegacyTagStateForV2(now)
        val profiles = tagProfileRepository.getAllProfiles()
        if (!isCurrentRefresh(session, generation)) return

        // Step 2: Re-plan sections in new affinity order.
        val plannedSections = sectionPlanner.plan(
            profiles = profiles,
            sortOrder = _state.value.sortOrder,
            now = now,
        )
        if (!isCurrentRefresh(session, generation)) return
        plannedSectionRepository.replaceAll(
            plannedSections,
            resultVersion = session.mode.resultVersion,
            refreshSessionId = session.sessionId,
        )
        // Prune any leftover rows whose section_key isn't in the new plan. Without this, a user
        // who blacklists a previously-managed tag would keep seeing rows from that section
        // until the section_key happened to collide with a future plan entry.
        suggestionsRepository.deleteOrphanedByPlan(session.mode.resultVersion)

        // Step 3: Re-rank existing stored suggestions using new section order.
        val existing = getCurrentSuggestions()
        if (!isCurrentRefresh(session, generation)) return
        var renderedSectionKeys = existing.map { it.sectionKey }.toSet()
        val shouldReRankStoredRows = refreshTargetSectionKey == null
        if (existing.isNotEmpty() && shouldReRankStoredRows) {
            // Build a map of sectionKey → list of existing candidates for that section.
            val bySectionKey = existing.groupBy { it.sectionKey }
            val reRanked = plannedSections.flatMapIndexed { sectionIndex, section ->
                val sectionSuggestions = bySectionKey[section.sectionKey].orEmpty()
                sectionSuggestions.mapIndexed { itemIndex, suggestion ->
                    suggestion.copy(
                        displayRank = (sectionIndex * SECTION_DISPLAY_RANK_STRIDE) + itemIndex.toLong(),
                    )
                }
            }
            if (reRanked.isNotEmpty()) {
                if (!isCurrentRefresh(session, generation)) return
                preferences.suggestionsResultVersion().set(SuggestionsConfig.RESULT_VERSION_V2)
                suggestionsRepository.replaceAll(
                    reRanked,
                    resultVersion = session.mode.resultVersion,
                    refreshSessionId = session.sessionId,
                )
                renderedSectionKeys = reRanked.map { it.sectionKey }.toSet()
                renderStoredSuggestions()
                debugLog.add(LogType.REFRESH_MODE, "Soft refresh: re-ranked ${reRanked.size} items across ${plannedSections.size} sections")
            }
        }

        // Step 4: Update planned-section state so scroll and batch logic is correct.
        val refreshId = preferences.suggestionsTotalRefreshCount().get().toLong()
        currentV2RefreshId = refreshId

        // Step 4: Re-rank-driven feed reshuffle. The previously-loaded section count
        // anchors how many sections the pull-to-refresh will touch. We take the top
        // N planned sections in the freshly-ranked order — N stays equal to whatever
        // the user had on screen, but WHICH N can change (e.g. a tag whose affinity
        // climbed during this session replaces one that fell out of the top set).
        val previouslyLoadedKeys = _state.value.suggestions
            .filterValues { it.isNotEmpty() }
            .keys
        val previouslyLoadedCount = previouslyLoadedKeys.size

        // NOTE: we intentionally do NOT add the currently-shown manga to `seenMangaUrls`
        // here. They already landed in the set when their original section committed
        // (see `appendSectionResult`), and adding them again would shrink the candidate
        // pool below the per-section target — empirically dropping sections from 9 to
        // ~6 results. Variety between refreshes already comes from the randomised page
        // offset (`currentPageOffset = Random.nextInt(1, 8)`) plus the persistent
        // seen-log TTL.

        val refreshTopN = when {
            previouslyLoadedCount > 0 -> plannedSections.take(previouslyLoadedCount)
            // First-time refresh with nothing loaded yet falls back to whatever the
            // explicit target requested (visible / selected section), preserving the
            // old single-section behaviour for callers that pass a target key.
            refreshTargetSectionKey != null -> plannedSections
                .firstOrNull { it.sectionKey == refreshTargetSectionKey }
                ?.let(::listOf)
                .orEmpty()
            else -> emptyList()
        }
        val refreshTopNKeys = refreshTopN.map { it.sectionKey }.toSet()

        // Step 5: Evict any previously-loaded section that fell out of the top N so
        // the UI doesn't keep a stale tag visible after the user explicitly asked
        // for fresh suggestions. The DB rows are removed too, otherwise the next
        // DB-observer emission would resurrect them.
        val evictedKeys = previouslyLoadedKeys - refreshTopNKeys
        if (evictedKeys.isNotEmpty()) {
            evictedKeys.forEach { key ->
                suggestionsRepository.deleteBySectionKey(key, session.mode.resultVersion)
            }
        }

        // Step 6: Mark every section in the new top N as stale so the next batch
        // fetch will hit the network. Sections beyond the top N keep their cache —
        // they remain visible in the feed but won't refresh unless the user scrolls
        // to them and the existing scroll-triggered batch loader picks them up.
        refreshTopN.forEach { section ->
            sectionLastFetchedAt.remove(section.sectionKey)
        }

        val staleSections = if (previouslyLoadedCount == 0 && refreshTargetSectionKey == null) {
            plannedSections.filter { !isSectionCacheFresh(it, now) }
        } else {
            emptyList()
        }
        val loadedPrefixSize = SectionBatcher.contiguousLoadedPrefixSize(
            plannedSections = plannedSections,
            loadedSectionKeys = renderedSectionKeys,
        )
        if (!isCurrentRefresh(session, generation)) return
        val displayNames = plannedSections.associate { it.sectionKey to it.displayReason }
        _state.update { state ->
            val keptSuggestions = if (evictedKeys.isNotEmpty()) {
                state.suggestions.filterKeys { it !in evictedKeys }
            } else {
                state.suggestions
            }
            state.copy(
                suggestions = keptSuggestions,
                plannedSections = plannedSections,
                sectionDisplayNames = displayNames,
                // Start the scroll-triggered batch loader at the first not-yet-fetched
                // top-N section. Discovery sits at index 0 and is visible at the top of
                // the feed, so the first batch kick-off below covers it; subsequent
                // sections fire when the user scrolls them into view.
                nextBatchStartIndex = if (refreshTopN.isNotEmpty()) 0 else loadedPrefixSize,
                isFetchingBatch = false,
                allSectionsLoaded = if (refreshTopN.isNotEmpty()) {
                    false
                } else {
                    staleSections.isEmpty()
                },
                hasReachedEnd = false,
                endMessage = null,
                emptyMessage = null,
                refreshingSectionKeys = refreshTopNKeys,
            )
        }

        // Step 7: Kick the first section's fetch immediately — the discovery row sits
        // at the top of the feed after a pull, so the user expects to see it move
        // first. The rest of the top N intentionally wait for scroll: the existing
        // `loadNextSectionBatch` path picks them up as the user reaches each header.
        if (refreshTopN.isNotEmpty() && isCurrentRefresh(session, generation)) {
            val inserted = loadNextSectionBatchInternal(
                generation = generation,
                pageOffset = pageOffset,
                refreshId = refreshId,
                session = session,
            )
            if (inserted == 0 && currentSuggestionsCount() > 0L) {
                pendingRefreshMessage = context.getString(MR.strings.suggestions_up_to_date)
            }
        } else if (staleSections.isNotEmpty() && isCurrentRefresh(session, generation)) {
            var inserted = loadNextSectionBatchInternal(generation = generation, pageOffset = pageOffset, refreshId = refreshId, session = session)
            var retries = 0
            while (isCurrentRefresh(session, generation) && inserted == 0 && !_state.value.allSectionsLoaded && retries < MAX_ZERO_INSERT_RETRIES) {
                delay(ZERO_INSERT_RETRY_DELAY_MS)
                inserted += loadNextSectionBatchInternal(generation = generation, pageOffset = pageOffset, refreshId = refreshId, session = session)
                retries++
            }
        } else if (staleSections.isEmpty() && refreshTopN.isEmpty()) {
            // Pull fired but nothing was loaded and no stale sections exist — give
            // brief feedback so the user knows the gesture was acknowledged.
            pendingRefreshMessage = context.getString(MR.strings.suggestions_up_to_date)
        }

        if (currentSuggestionsCount() == 0L) {
            throw RefreshBlocked(
                noMatchesMessage(),
            )
        }
    }

    private suspend fun refreshV2SingleSection(
        section: PlannedSection,
        generation: Long,
        pageOffset: Int,
        refreshId: Long,
        rankingContext: RankingContext,
        now: Long,
        session: SuggestionRefreshSession? = null,
    ): Int {
        if (!isSectionBatchFetching.compareAndSet(false, true)) return 0

        _state.update { it.copy(
            isFetchingBatch = true,
            isFetching = !isForegroundRefreshing.get(),
            emptyMessage = null,
        )}

        try {
            val sectionSeenKeys = suggestionSeenLogRepository.recentKeysForSections(
                sectionKeys = listOf(section.sectionKey),
                cutoff = now - SuggestionsConfig.SEEN_LOG_TTL_MS,
            )
            val shallowResult = retrieveSingleSectionForRefresh(
                section = section,
                pageOffset = pageOffset,
                maxPerSourceFetch = SuggestionsConfig.MANUAL_REFRESH_MAX_PER_SOURCE_FETCH,
                sectionSeenKeys = sectionSeenKeys,
            )
            val shallowPreview = rankSectionPreview(
                result = shallowResult,
                rankingContext = rankingContext,
                sectionSeenKeys = sectionSeenKeys,
            )
            var resultToCommit = shallowResult
            if (
                shallowResult.candidates.isNotEmpty() &&
                shallowPreview.size < SuggestionsConfig.MAX_RESULTS_PER_SECTION
            ) {
                val fullResult = retrieveSingleSectionForRefresh(
                    section = section,
                    pageOffset = pageOffset,
                    maxPerSourceFetch = null,
                    sectionSeenKeys = sectionSeenKeys,
                )
                val fullPreview = rankSectionPreview(
                    result = fullResult,
                    rankingContext = rankingContext,
                    sectionSeenKeys = sectionSeenKeys,
                )
                if (fullResult.candidates.isNotEmpty() && fullPreview.size >= shallowPreview.size) {
                    resultToCommit = fullResult
                }
            }

            return appendSectionResult(
                result = resultToCommit,
                refreshId = refreshId,
                rankingContext = rankingContext,
                sectionSeenKeys = sectionSeenKeys,
                now = now,
                pageOffset = pageOffset,
                session = session,
                generation = generation,
            )
        } finally {
            isSectionBatchFetching.set(false)
            if (session?.let { isCurrentRefresh(it, generation) } ?: (generation == feedGeneration.get())) {
                _state.update { it.copy(
                    isFetchingBatch = false,
                    isFetching = false,
                    refreshingSectionKeys = it.refreshingSectionKeys - section.sectionKey,
                )}
            } else {
                updateLoadingState()
            }
        }
    }

    private suspend fun retrieveSingleSectionForRefresh(
        section: PlannedSection,
        pageOffset: Int,
        maxPerSourceFetch: Int?,
        sectionSeenKeys: Map<String, Set<String>>,
    ): CandidateRetrievalResult {
        val partialCandidates = mutableListOf<SuggestionCandidate>()
        var finalResult: CandidateRetrievalResult? = null
        candidateRetriever.retrieveProgressively(
            sections = listOf(section),
            pageOffset = pageOffset,
            maxPerSourceFetch = maxPerSourceFetch,
            globalSeenKeys = seenMangaUrls.toSet(),
            sectionSeenKeys = sectionSeenKeys,
        ) { result ->
            if (result.isSectionComplete) {
                val candidates = result.candidates
                    .ifEmpty { partialCandidates }
                    .distinctBy { it.sourceId to it.manga.url }
                finalResult = result.copy(
                    candidates = candidates,
                    isSectionComplete = true,
                )
            } else {
                partialCandidates.addAll(result.candidates)
            }
        }
        return finalResult ?: CandidateRetrievalResult(
            section = section,
            candidates = partialCandidates.distinctBy { it.sourceId to it.manga.url },
            isSectionComplete = true,
        )
    }

    private suspend fun rankSectionPreview(
        result: CandidateRetrievalResult,
        rankingContext: RankingContext,
        sectionSeenKeys: Map<String, Set<String>>,
    ): List<SuggestedManga> {
        val verifiedResult = verifyCandidateResult(result, rankingContext)
        return suggestionRanker.rankWithContext(
            retrievalResults = listOf(verifiedResult),
            context = rankingContext,
            globalSeenKeys = seenMangaUrls.toSet(),
            sectionSeenKeys = sectionSeenKeys,
            sessionContext = sessionContext,
        ).withSectionDisplayRanks(result.section)
    }

    private suspend fun verifyCandidateResult(
        result: CandidateRetrievalResult,
        rankingContext: RankingContext,
    ): CandidateRetrievalResult {
        if (rankingContext.blacklistedTags.isEmpty()) return result
        val verifiedCandidates = metadataVerifier.filterCandidates(
            candidates = result.candidates,
            blacklistedTags = rankingContext.blacklistedTags,
        )
        return result.copy(candidates = verifiedCandidates)
    }

    private suspend fun rankVerifiedSection(
        result: CandidateRetrievalResult,
        rankingContext: RankingContext,
        sectionSeenKeys: Map<String, Set<String>>,
    ): List<SuggestedManga> =
        suggestionRanker.rankWithContext(
            retrievalResults = listOf(result),
            context = rankingContext,
            globalSeenKeys = seenMangaUrls.toSet(),
            sectionSeenKeys = sectionSeenKeys,
            sessionContext = sessionContext,
        ).withSectionDisplayRanks(result.section)

    private suspend fun refreshV2(
        generation: Long,
        pageOffset: Int,
        session: SuggestionRefreshSession,
    ) {
        val now = System.currentTimeMillis()
        debugLog.add(LogType.REFRESH_MODE, "Hard refresh - rebuilding profile, resetting seen log")
        if (!isCurrentRefresh(session, generation)) return
        // Trim the seen-log to a rolling retention window. Earlier this used Long.MAX_VALUE
        // which wiped every row on every hard refresh, destroying the dedupe signal across
        // sessions. SEEN_LOG_HARD_REFRESH_RETENTION_MS keeps a few days of history so popular
        // titles can resurface while back-to-back refreshes don't re-show the same manga.
        suggestionSeenLogRepository.deleteOlderThan(now - SuggestionsConfig.SEEN_LOG_HARD_REFRESH_RETENTION_MS)
        if (!isCurrentRefresh(session, generation)) return
        sectionLastFetchedAt.clear()

        interestProfileBuilder.buildProfile(now)
        if (!isCurrentRefresh(session, generation)) return
        syncLegacyTagStateForV2(now)
        val profiles = tagProfileRepository.getAllProfiles()
        if (!isCurrentRefresh(session, generation)) return

        val plannedSections = sectionPlanner.plan(
            profiles = profiles,
            sortOrder = _state.value.sortOrder,
            now = now,
        )
        if (!isCurrentRefresh(session, generation)) return
        plannedSectionRepository.replaceAll(
            plannedSections,
            resultVersion = session.mode.resultVersion,
            refreshSessionId = session.sessionId,
        )
        // Prune any orphaned suggestion rows so a blacklisted-tag-section's old manga don't
        // leak into the hard-refresh result through accumulation in the suggestions table.
        suggestionsRepository.deleteOrphanedByPlan(session.mode.resultVersion)

        val totalRefreshCount = preferences.suggestionsTotalRefreshCount()
        val refreshId = totalRefreshCount.get() + 1L
        totalRefreshCount.set(refreshId.toInt())
        currentV2RefreshId = refreshId
        preferences.suggestionsLastHardRefreshAt().set(now)

        if (!isCurrentRefresh(session, generation)) return
        val displayNames = plannedSections.associate { it.sectionKey to it.displayReason }
        _state.update { it.copy(
            plannedSections = plannedSections,
            sectionDisplayNames = displayNames,
            nextBatchStartIndex = 0,
            isFetchingBatch = false,
            allSectionsLoaded = plannedSections.isEmpty(),
            hasReachedEnd = plannedSections.isEmpty(),
            endMessage = null,
            emptyMessage = null,
        )}

        var inserted = 0
        pendingV2HardRefreshReplace.set(true)
        try {
            inserted = loadNextSectionBatchInternal(
                generation = generation,
                pageOffset = pageOffset,
                refreshId = refreshId,
                session = session,
            )
            var retries = 0
            while (isCurrentRefresh(session, generation) && inserted == 0 && !_state.value.allSectionsLoaded && retries < MAX_ZERO_INSERT_RETRIES) {
                delay(ZERO_INSERT_RETRY_DELAY_MS)
                inserted += loadNextSectionBatchInternal(
                    generation = generation,
                    pageOffset = pageOffset,
                    refreshId = refreshId,
                    session = session,
                )
                retries++
            }
        } finally {
            pendingV2HardRefreshReplace.set(false)
        }

        if (inserted == 0) {
            throw RefreshBlocked(
                if (!candidateRetriever.hasActiveNetworkSources()) {
                    waitingForSourcesMessage()
                } else {
                    noMatchesMessage()
                },
            )
        }
    }

    private suspend fun restoreV2PlanState() {
        val plannedSections = plannedSectionRepository.getPlannedSections(SuggestionsConfig.RESULT_VERSION_V2)
        if (plannedSections.isEmpty()) return

        val loadedSectionKeys = getCurrentSuggestions()
            .map { it.sectionKey }
            .toSet()
        val nextIndex = SectionBatcher.contiguousLoadedPrefixSize(
            plannedSections = plannedSections,
            loadedSectionKeys = loadedSectionKeys,
        )
        val allLoaded = nextIndex >= plannedSections.size
        currentV2RefreshId = preferences.suggestionsTotalRefreshCount().get().toLong()
        val displayNames = plannedSections.associate { it.sectionKey to it.displayReason }
        _state.update { it.copy(
            plannedSections = plannedSections,
            sectionDisplayNames = displayNames,
            nextBatchStartIndex = nextIndex,
            allSectionsLoaded = allLoaded,
            hasReachedEnd = allLoaded,
            endMessage = sectionEndMessage(nextIndex, plannedSections.size).takeIf { allLoaded },
        )}
    }

    private fun loadNextSectionBatch() {
        // While showing a snapshot, do not load more — the cached state is complete
        // for that sort and live DB results must not be mixed into it.
        if (frozenForSort != null) return
        val generation = feedGeneration.get()
        val pageOffset = currentPageOffset
        val refreshId = currentV2RefreshId.takeIf { it > 0L }
            ?: preferences.suggestionsTotalRefreshCount().get().toLong()
        presenterScope.launchIO {
            if (generation != feedGeneration.get()) return@launchIO
            try {
                loadNextSectionBatchInternal(
                    generation = generation,
                    pageOffset = pageOffset,
                    refreshId = refreshId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransientSuggestionNetworkException) {
                // Scroll-triggered section loads must not crash the app when the
                // network drops mid-fetch. Mirror the foreground refresh handler:
                // pause the active session, surface the waiting-for-network banner,
                // and let the connectivity callback resume it once we're back online.
                val session = refreshSessions.activeSessionOrNull()
                val pausedForNetwork = session?.let { refreshSessions.pauseIfCurrent(it) } == true
                if (pausedForNetwork) {
                    isSectionBatchFetching.set(false)
                    isPageFetching.set(false)
                    _state.update { state ->
                        state.copy(
                            isPausedForNetwork = true,
                            refreshBannerMessage = waitingForNetworkMessage(),
                            isFetchingBatch = false,
                            isFetching = false,
                        )
                    }
                } else {
                    _state.update { state ->
                        state.copy(
                            isPausedForNetwork = true,
                            refreshBannerMessage = waitingForNetworkMessage(),
                            isFetchingBatch = false,
                            isFetching = false,
                        )
                    }
                }
            } catch (_: Exception) {
                // Pagination is best-effort: any other failure (parser error,
                // source extension fault) should not crash the process. The
                // affected section will simply remain in `refreshingSectionKeys`
                // until the next manual refresh resets it.
                _state.update { state ->
                    state.copy(isFetchingBatch = false, isFetching = false)
                }
            }
        }
    }

    private suspend fun loadNextSectionBatchInternal(
        generation: Long,
        pageOffset: Int,
        refreshId: Long,
        session: SuggestionRefreshSession? = null,
    ): Int {
        val state = _state.value
        if (state.allSectionsLoaded || state.nextBatchStartIndex >= state.plannedSections.size) {
            markAllV2SectionsLoaded()
            return 0
        }
        if (!isSectionBatchFetching.compareAndSet(false, true)) return 0

        val batchStartIndex = state.nextBatchStartIndex
        val batch = SectionBatcher.nextBatch(
            plannedSections = state.plannedSections,
            startIndex = batchStartIndex,
        )
        if (batch.isEmpty()) {
            isSectionBatchFetching.set(false)
            markAllV2SectionsLoaded()
            return 0
        }

        _state.update { it.copy(
            isFetchingBatch = true,
            isFetching = !isForegroundRefreshing.get(),
            emptyMessage = null,
        )}

        var insertedCount = 0
        var interruptedByNetwork = false
        try {
            val now = System.currentTimeMillis()
            val sectionsToFetch = batch.filterNot { section -> isSectionCacheFresh(section, now) }

            // ── Pre-fetch batch-stable data ONCE ──────────────────────────────────────
            // Fetching localManga + profiles from DB is expensive. Do it once per batch
            // rather than once per section (was 2 full-table reads × batch size = 10 reads
            // for a 5-section batch; now it's 2 reads total).
            val rankingContext = suggestionRanker.buildRankingContext()

            // Pre-fetch all section seen-keys in parallel instead of sequentially inside
            // each appendSectionResult call.
            val seenCutoff = now - SuggestionsConfig.SEEN_LOG_TTL_MS
            val allSectionSeenKeys = suggestionSeenLogRepository.recentKeysForSections(
                sectionKeys = sectionsToFetch.map { it.sectionKey },
                cutoff = seenCutoff,
            )
            // ─────────────────────────────────────────────────────────────────────────

            val accumulatedCandidates = mutableMapOf<String, MutableList<SuggestionCandidate>>()

            candidateRetriever.retrieveProgressively(
                sections = sectionsToFetch,
                pageOffset = pageOffset,
                globalSeenKeys = seenMangaUrls.toSet(),
                sectionSeenKeys = allSectionSeenKeys,
            ) { result ->
                if (session?.let { isCurrentRefresh(it, generation) } ?: (generation == feedGeneration.get())) {
                    val sectionKey = result.section.sectionKey
                    val acc = accumulatedCandidates.getOrPut(sectionKey) { mutableListOf() }
                    acc.addAll(result.candidates)

                    // Per-section try/catch: a single failing section (rank/persist) should not
                    // abort the whole refresh and bubble out to the outer catch as a generic
                    // "refresh error". Log it via debugLog (Bug 8a) and continue with the
                    // next section.
                    try {
                        if (result.isSectionComplete) {
                            val fullResult = CandidateRetrievalResult(
                                section = result.section,
                                candidates = acc,
                                isSectionComplete = true,
                            )
                            insertedCount += appendSectionResult(
                                result = fullResult,
                                refreshId = refreshId,
                                rankingContext = rankingContext,
                                sectionSeenKeys = allSectionSeenKeys,
                                now = now,
                                pageOffset = pageOffset,
                                session = session,
                                generation = generation,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TransientSuggestionNetworkException) {
                        throw e
                    } catch (t: Throwable) {
                        debugLog.add(
                            LogType.SECTION_DROPPED,
                            "Section '$sectionKey' processing failed: ${t.javaClass.simpleName}: ${t.message}",
                        )
                    }
                }
            }
        } catch (e: TransientSuggestionNetworkException) {
            interruptedByNetwork = true
            throw e
        } finally {
            val nextIndex = if (interruptedByNetwork) {
                batchStartIndex
            } else {
                (batchStartIndex + batch.size).coerceAtMost(state.plannedSections.size)
            }
            val allLoaded = nextIndex >= state.plannedSections.size
            isSectionBatchFetching.set(false)
            if (session?.let { isCurrentRefresh(it, generation) } ?: (generation == feedGeneration.get())) {
                _state.update { current ->
                    val hasAnySuggestions = insertedCount > 0 ||
                        current.suggestions.values.any { section -> section.isNotEmpty() }
                    current.copy(
                        nextBatchStartIndex = nextIndex,
                        isFetchingBatch = false,
                        isFetching = false,
                        allSectionsLoaded = allLoaded,
                        hasReachedEnd = allLoaded && hasAnySuggestions,
                        endMessage = sectionEndMessage(nextIndex, current.plannedSections.size)
                            .takeIf { allLoaded && hasAnySuggestions },
                        emptyMessage = if (allLoaded && !hasAnySuggestions) {
                            noMatchesMessage()
                        } else {
                            current.emptyMessage
                        },
                    )
                }
            } else {
                updateLoadingState()
            }
        }

        return insertedCount
    }

    private fun isSectionCacheFresh(section: PlannedSection, now: Long): Boolean {
        val fetchedAt = sectionLastFetchedAt[section.sectionKey] ?: return false
        val ttl = when (section.type) {
            SectionType.DISCOVERY -> SuggestionsConfig.DISCOVERY_CACHE_TTL_MS
            else -> SuggestionsConfig.TAG_SECTION_CACHE_TTL_MS
        }
        return now - fetchedAt <= ttl
    }

    private suspend fun appendSectionResult(
        result: CandidateRetrievalResult,
        refreshId: Long,
        rankingContext: RankingContext,
        sectionSeenKeys: Map<String, Set<String>>,
        now: Long,
        pageOffset: Int,
        session: SuggestionRefreshSession? = null,
        generation: Long? = null,
    ): Int {
        var resultToRank = verifyCandidateResult(result, rankingContext)
        var suggestions = rankVerifiedSection(resultToRank, rankingContext, sectionSeenKeys)

        sectionLastFetchedAt[result.section.sectionKey] = now

        var topUpPage = pageOffset + 2
        var topUpAttempts = 0
        while (
            suggestions.size < SuggestionsConfig.MAX_RESULTS_PER_SECTION &&
            topUpAttempts < SuggestionsConfig.SECTION_FILL_EXTRA_PAGE_LIMIT
        ) {
            val topUpResult = candidateRetriever.retrieve(
                sections = listOf(result.section),
                pageOffset = topUpPage,
                globalSeenKeys = seenMangaUrls.toSet(),
                sectionSeenKeys = sectionSeenKeys,
            ).singleOrNull() ?: break
            val verifiedTopUp = verifyCandidateResult(topUpResult, rankingContext)
            val combinedCandidates = (resultToRank.candidates + verifiedTopUp.candidates)
                .distinctBy { it.sourceId to it.manga.url }
            if (combinedCandidates.size <= resultToRank.candidates.size) break
            resultToRank = resultToRank.copy(candidates = combinedCandidates)
            val toppedUp = rankVerifiedSection(resultToRank, rankingContext, sectionSeenKeys)
            if (toppedUp.size > suggestions.size) {
                suggestions = toppedUp
            }
            topUpPage++
            topUpAttempts++
        }

        if (suggestions.isEmpty()) {
            debugLog.add(LogType.SECTION_DROPPED, "Section '${result.section.sectionKey}' dropped - 0 results after filters")
            return 0
        }

        // ── Seen-log widening (v3 plan §"Minimum Results Per Section") ──────────
        // If the normal pass yielded fewer than the minimum, re-rank the same
        // candidates with the per-section seen-log cleared so manga seen > 12 h
        // ago in this section becomes eligible again. Never drop a section just
        // because it's thin — accept whatever is available after the retry.
        if (suggestions.size < SuggestionsConfig.MIN_RESULTS_PER_SECTION) {
            debugLog.add(
                LogType.SECTION_THIN,
                "Section '${result.section.sectionKey}' returned only ${suggestions.size} results " +
                    "after all sources (minimum is ${SuggestionsConfig.MIN_RESULTS_PER_SECTION}) — retrying with relaxed seen-log",
            )
            val relaxedSuggestions = suggestionRanker.rankWithContext(
                retrievalResults = listOf(resultToRank),
                context = rankingContext,
                globalSeenKeys = seenMangaUrls.toSet(),
                // Pass empty seen keys for this section so items seen > 12 h ago are allowed.
                sectionSeenKeys = sectionSeenKeys - result.section.sectionKey,
                sessionContext = sessionContext,
            ).withSectionDisplayRanks(result.section)
            if (relaxedSuggestions.size > suggestions.size) {
                suggestions = relaxedSuggestions
            }
            // Second-pass: if per-section relaxation still yields nothing, also clear
            // globalSeenKeys. This is the last-resort so a section never returns 0 results
            // when candidates exist (they just happened to be seen recently).
            if (suggestions.isEmpty() && result.candidates.isNotEmpty()) {
                debugLog.add(
                    LogType.SECTION_THIN,
                    "Section '${result.section.sectionKey}' still 0 after per-section relaxation — clearing global seen keys",
                )
                val fullyRelaxed = suggestionRanker.rankWithContext(
                    retrievalResults = listOf(resultToRank),
                    context = rankingContext,
                    globalSeenKeys = emptySet(),
                    sectionSeenKeys = emptyMap(),
                    sessionContext = sessionContext,
                ).withSectionDisplayRanks(result.section)
                if (fullyRelaxed.isNotEmpty()) {
                    suggestions = fullyRelaxed
                }
            }
            // Log final thin count so it's visible even if relaxation helped.
            if (suggestions.size < SuggestionsConfig.MIN_RESULTS_PER_SECTION) {
                debugLog.add(
                    LogType.SECTION_THIN,
                    "Section '${result.section.sectionKey}' returned only ${suggestions.size} results " +
                        "after all sources (minimum is ${SuggestionsConfig.MIN_RESULTS_PER_SECTION})",
                )
            }
        }
        // ─────────────────────────────────────────────────────────────────────────

        // Session guard: a superseded refresh must not commit its section to the DB.
        // Without this, an in-flight V1 batch could write V1 rows after the user has
        // toggled to V2 and a newer session has started; the next read filters by
        // current mode so the UI is safe, but the DB then carries dead rows that
        // confuse seen-log accounting and slow subsequent reads.
        if (session != null && generation != null && !isCurrentRefresh(session, generation)) {
            return 0
        }
        preferences.suggestionsResultVersion().set(SuggestionsConfig.RESULT_VERSION_V2)
        val resultVersion = session?.mode?.resultVersion ?: currentSuggestionsResultVersion()
        val refreshSessionId = session?.sessionId
        // `pendingV2HardRefreshReplace` is consumed here to reset the in-flight marker
        // exactly once per hard refresh; the write itself is identical for both branches.
        pendingV2HardRefreshReplace.compareAndSet(true, false)
        suggestionsRepository.replaceSection(
            result.section.sectionKey,
            suggestions,
            resultVersion = resultVersion,
            refreshSessionId = refreshSessionId,
        )
        renderStoredSuggestions()
        shownHistoryRepository.insertAll(suggestions.map { it.source to it.url })
        seenMangaUrls.addAll(suggestions.map { it.memoryKey() })
        rememberDisplayedSuggestionSources(suggestions)
        // Batch insert all seen-log entries in one transaction instead of N individual writes.
        suggestionSeenLogRepository.insertSeenBatch(
            suggestions.map { suggestion ->
                SeenEntry(
                    sectionKey = result.section.sectionKey,
                    mangaKey = suggestion.memoryKey(),
                    shownAt = now,
                    refreshId = refreshId,
                )
            },
        )
        return suggestions.size
    }

    private fun markAllV2SectionsLoaded() {
        _state.update { it.copy(
            allSectionsLoaded = true,
            hasReachedEnd = it.suggestions.values.any { section -> section.isNotEmpty() },
            isFetchingBatch = false,
            isFetching = false,
            endMessage = sectionEndMessage(it.nextBatchStartIndex, it.plannedSections.size)
                .takeIf { _ -> it.suggestions.values.any { section -> section.isNotEmpty() } },
            emptyMessage = if (it.suggestions.values.any { section -> section.isNotEmpty() }) {
                it.emptyMessage
            } else {
                noMatchesMessage()
            },
        )}
    }

    private suspend fun syncLegacyTagStateForV2(now: Long) {
        // Clear all previously-blacklisted DB states first so un-checking a tag in
        // the filter sheet actually takes effect (without this, the BLACKLISTED state
        // only ever accumulates and can never be removed from the DB).
        tagProfileRepository.resetBlacklistedToManaged(now)

        // Pins are V1-only and not synced to the V2 tag_profile table.
        preferences.suggestionsTagsBlacklist().get().forEach { rawTag ->
            val canonicalTag = tagCanonicalizer.canonicalize(rawTag).canonicalKey
            if (canonicalTag.isNotBlank()) {
                tagProfileRepository.setTagState(canonicalTag, TagState.BLACKLISTED, now)
            }
        }
    }

    private fun updateLoadingState() {
        val hasRenderedSuggestions = _state.value.suggestions.values.any { it.isNotEmpty() }
        _state.update { it.copy(
            isLoading = shouldShowFullPageRefreshLoading(
                isForegroundRefreshing = isForegroundRefreshing.get(),
                hasRenderedSuggestions = hasRenderedSuggestions,
            ),
            isForegroundRefresh = isForegroundRefreshing.get(),
            // isFetching drives the pagination spinner only — not the full-refresh spinner
            isFetching = (isPageFetching.get() || isSectionBatchFetching.get()) && !isForegroundRefreshing.get(),
            isFetchingBatch = isSectionBatchFetching.get(),
        )}
    }

    private fun shouldKeepCurrentSuggestions(suggestedList: List<SuggestedManga>): Boolean {
        val currentState = _state.value
        if (currentState.suggestions.isEmpty() || isInitialLoad.get()) return false
        val isBusy = isForegroundRefreshing.get() ||
            isPageFetching.get() ||
            isSectionBatchFetching.get() ||
            isWorkerRefreshing
        if (suggestedList.isEmpty()) return true
        val selectedSectionKey = currentState.selectedSectionKey
        return selectedSectionKey != null &&
            isBusy &&
            suggestedList.none { it.sectionKey == selectedSectionKey }
    }

    private suspend fun renderStoredSuggestions(warmSession: Boolean = false) {
        renderSuggestedList(getCurrentSuggestions(), warmSession = warmSession)
    }

    private fun renderSuggestedList(
        suggestedList: List<SuggestedManga>,
        warmSession: Boolean = true,
    ) {
        if (warmSession) {
            warmSessionMemory(suggestedList)
        }
        val grouped = suggestedList.groupBy { it.sectionKey }.mapValues { entry ->
            entry.value
                .distinctBy { it.source to it.url }
                // Defensive cap at the render boundary: if anything upstream ever
                // writes more than MAX_RESULTS_PER_SECTION rows for a section (an
                // old DB row, a worker race, etc.), the UI still respects the
                // per-section target instead of showing a 6×3 grid of 18 items.
                .take(SuggestionsConfig.MAX_RESULTS_PER_SECTION)
                .map { suggested ->
                    MangaImpl(source = suggested.source, url = suggested.url).apply {
                        title = suggested.title
                        thumbnail_url = suggested.thumbnailUrl
                    }
                }
        }
        // V2 populates plannedSections with displayReason. V1 doesn't, so synthesize friendly
        // labels from the section_key prefix ("pinned:mecha" -> "Pinned: Mecha") to avoid the
        // raw key leaking into the UI header.
        val plannedDisplayNames = _state.value.plannedSections.associate { it.sectionKey to it.displayReason }
        val displayNames = grouped.keys.associateWith { sectionKey ->
            plannedDisplayNames[sectionKey] ?: sectionKeyToV1DisplayName(sectionKey)
        }
        val selectedSectionKey = _state.value.selectedSectionKey?.takeIf { it in grouped.keys }
        _state.update { state ->
            state.copy(
                suggestions = grouped,
                sectionDisplayNames = displayNames,
                selectedSectionKey = selectedSectionKey,
                emptyMessage = state.emptyMessage.takeIf { grouped.isEmpty() },
            )
        }
    }

    private suspend fun syncSelectedSectionKeyWithStoredSuggestions() {
        val selectedSectionKey = _state.value.selectedSectionKey ?: return
        val storedSectionKeys = getCurrentSuggestions()
            .map { it.sectionKey }
            .toSet()
        if (storedSectionKeys.isNotEmpty() && selectedSectionKey !in storedSectionKeys) {
            _state.update { it.copy(selectedSectionKey = null) }
        }
    }

    private fun warmSessionMemory(suggestions: List<SuggestedManga>) {
        if (suggestions.isEmpty()) return
        if (seenMangaUrls.isEmpty()) {
            seenMangaUrls.addAll(suggestions.map { it.memoryKey() })
        }
        if (usedTags.isEmpty()) {
            usedTags.addAll(suggestions.mapNotNull { it.sectionKey.toTagFromSectionKey() })
        }
    }

    private fun List<SuggestedManga>.withDisplayRanks(startRank: Long): List<SuggestedManga> =
        mapIndexed { index, suggestion ->
            suggestion.copy(displayRank = startRank + index.toLong())
        }

    private fun List<SuggestedManga>.withSectionDisplayRanks(section: PlannedSection): List<SuggestedManga> =
        mapIndexed { index, suggestion ->
            suggestion.copy(displayRank = section.rank * SECTION_DISPLAY_RANK_STRIDE + index.toLong())
        }

    private fun SuggestedManga.memoryKey(): String =
        "$source:$url"

    private suspend fun appendExpandedPage(page: ExpandedSectionPage) {
        val displayNow = mutableListOf<SuggestedManga>()
        val accepted = page.suggestions
            .filterNotExpandedDuplicate()
            .distinctBy { suggested -> suggested.source to suggested.url }
            .distinctBy { suggested -> suggested.title.normalizedExpandedTitle() }

        for (suggestion in accepted) {
            if (suggestion.source in expandedPassSources) {
                expandedBufferedSuggestions
                    .getOrPut(suggestion.source) { ArrayDeque() }
                    .addLast(suggestion)
            } else {
                expandedPassSources += suggestion.source
                displayNow += suggestion
            }
        }

        if (displayNow.isNotEmpty()) {
            appendExpandedSuggestions(displayNow)
        }
        if (page.batchComplete) {
            drainExpandedBuffers()
            expandedPassSources.clear()
        }
        _state.update { it.copy(
            sheetIsLoading = false,
            sheetIsLoadingMore = false,
            sheetHasMore = page.hasMoreSources || hasExpandedBufferedSuggestions(),
        )}
    }

    private suspend fun appendExpandedSuggestions(displaySuggestions: List<SuggestedManga>) {
        if (displaySuggestions.isEmpty()) return
        val uniqueSuggestions = displaySuggestions
            .filterNotExpandedDuplicate()
            .distinctBy { suggested -> suggested.source to suggested.url }
            .distinctBy { suggested -> suggested.title.normalizedExpandedTitle() }
        if (uniqueSuggestions.isEmpty()) return

        rememberDisplayedSuggestionSources(uniqueSuggestions)
        shownHistoryRepository.insertAll(uniqueSuggestions.map { it.source to it.url })
        seenMangaUrls.addAll(uniqueSuggestions.map { it.memoryKey() })

        val newManga = uniqueSuggestions.map { suggested ->
            MangaImpl(source = suggested.source, url = suggested.url).apply {
                title = suggested.title
                thumbnail_url = suggested.thumbnailUrl
            }
        }
        _state.update { currentState ->
            val combined = (currentState.sheetResults + newManga)
                .distinctBy { manga -> manga.source to manga.url }
                .distinctBy { manga -> manga.title.normalizedExpandedTitle() }
            currentState.copy(
                sheetResults = combined,
                sheetIsLoading = false,
                sheetIsLoadingMore = false,
                sheetHasMore = hasExpandedBufferedSuggestions() || currentState.sheetHasMore,
            )
        }
    }

    private suspend fun drainExpandedBuffers(): Boolean {
        val drained = mutableListOf<SuggestedManga>()
        val sourceIds = expandedBufferedSuggestions.keys.toList()
        sourceIds.forEach { sourceId ->
            val queue = expandedBufferedSuggestions[sourceId] ?: return@forEach
            while (queue.isNotEmpty()) {
                val suggestion = queue.removeFirst()
                if (!suggestion.isExpandedDuplicate()) {
                    drained += suggestion
                    break
                }
            }
            if (queue.isEmpty()) {
                expandedBufferedSuggestions.remove(sourceId)
            }
        }
        appendExpandedSuggestions(drained)
        return drained.isNotEmpty()
    }

    private fun resetExpandedPaging() {
        nextExpandedSourceOffset = 0
        nextExpandedSourcePage = 1
        expandedBufferedSuggestions.clear()
        expandedPassSources.clear()
    }

    private fun hasExpandedBufferedSuggestions(): Boolean =
        expandedBufferedSuggestions.values.any { it.isNotEmpty() }

    private fun List<SuggestedManga>.filterNotExpandedDuplicate(): List<SuggestedManga> =
        filterNot { it.isExpandedDuplicate() }

    private fun SuggestedManga.isExpandedDuplicate(): Boolean {
        val current = _state.value.sheetResults
        val existingKeys = current.map { it.source to it.url }.toSet()
        val existingTitles = current.map { it.title.normalizedExpandedTitle() }.toSet()
        val bufferedKeys = expandedBufferedSuggestions.values
            .flatMap { queue -> queue.map { it.source to it.url } }
            .toSet()
        val bufferedTitles = expandedBufferedSuggestions.values
            .flatMap { queue -> queue.map { it.title.normalizedExpandedTitle() } }
            .toSet()
        return source to url in existingKeys ||
            title.normalizedExpandedTitle() in existingTitles ||
            source to url in bufferedKeys ||
            title.normalizedExpandedTitle() in bufferedTitles
    }

    private fun rememberDisplayedSuggestionSources(suggestions: List<SuggestedManga>) {
        val displayedSourceIds = suggestions
            .map { it.source.toString() }
            .toSet()
        if (displayedSourceIds.isNotEmpty()) {
            preferences.lastFetchedSuggestionsSourceIds().set(displayedSourceIds)
        }
    }

    private fun List<SuggestedManga>.dbSignature(): Long =
        fold(size.toLong()) { hash, suggestion ->
            31L * hash +
                suggestion.source +
                31L * suggestion.url.hashCode() +
                31L * suggestion.sectionKey.hashCode() +
                31L * suggestion.displayRank +
                31L * suggestion.fetchedAt
        }

    private fun sectionEndMessage(loadedCount: Int, plannedCount: Int): String =
        if (loadedCount.coerceAtMost(plannedCount) <= 0) {
            endOfFeedMessage()
        } else {
            context.getString(MR.strings.suggestions_caught_up)
        }

    fun extractQueryFromSection(sectionKey: String): String? {
        return when {
            sectionKey.startsWith("pinned:") -> sectionKey.removePrefix("pinned:")
            sectionKey.startsWith("tag:") -> sectionKey.removePrefix("tag:")
            sectionKey.startsWith("search:") -> sectionKey.removePrefix("search:")
            sectionKey.startsWith("expanded:") -> sectionKey.removePrefix("expanded:")
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    fun canExpandSection(sectionKey: String): Boolean =
        extractQueryFromSection(sectionKey) != null ||
            sourceSortOrderForExpandableSection(sectionKey, _state.value.sortOrder) != null

    private fun String.toTagFromSectionKey(): String? {
        return when {
            startsWith("pinned:") -> removePrefix("pinned:")
            startsWith("tag:") -> removePrefix("tag:")
            startsWith("search:") -> removePrefix("search:")
            else -> null
        }
            ?.normalizedQuery()
            ?.takeIf { it.isNotBlank() }
    }

    private fun syncTagFilterState(
        blacklistedTags: Set<String>,
        pinnedTags: Set<String> = preferences.suggestionsPinnedTags().get(),
    ) {
        // Include both filter sets in the available list so user-typed custom tags survive
        // round-trips through the filter sheet even when no library manga carries them.
        val availableTags = (knownTags + blacklistedTags + pinnedTags)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizedQuery() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        _state.update { it.copy(
            availableTags = availableTags,
            blacklistedTags = blacklistedTags,
            pinnedTags = pinnedTags,
            pinFilterEnabled = !isSuggestionsV2Enabled(),
        )}
    }

    private fun List<Manga>.extractKnownTags(): List<String> {
        return asSequence()
            .flatMap { manga -> manga.getOriginalGenres().orEmpty().asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizedQuery() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
    }

    private fun String.normalizedQuery(): String =
        lowercase().trim().replace(WHITESPACE, " ")

    private fun String.normalizedExpandedTitle(): String =
        lowercase()
            .replace(EXPANDED_TITLE_PUNCTUATION, " ")
            .replace(WHITESPACE, " ")
            .trim()

    private class RefreshBlocked(val userMessage: String) : Exception(userMessage)

    private fun endOfFeedMessage(): String =
        context.getString(MR.strings.suggestions_end_of_feed)

    private fun noMatchesMessage(): String =
        context.getString(MR.strings.suggestions_no_matches)

    private fun refreshErrorMessage(): String =
        context.getString(MR.strings.suggestions_refresh_error)

    private fun loadMoreErrorMessage(): String =
        context.getString(MR.strings.suggestions_load_more_error)

    private fun waitingForSourcesMessage(): String =
        context.getString(MR.strings.suggestions_waiting_for_sources)

    internal companion object {
        private const val MAX_ZERO_INSERT_RETRIES = 10
        private const val ZERO_INSERT_RETRY_DELAY_MS = 300L
        private const val SECTION_DISPLAY_RANK_STRIDE = 1_000L
        private const val SOURCE_CHANGE_DEBOUNCE_MILLIS = 500L
        private val EXPANDED_TITLE_PUNCTUATION = Regex("[\\p{Punct}]")
        private val WHITESPACE = Regex("\\s+")
        /** Shown manga older than 30 days become eligible to appear again. */
        private const val HISTORY_TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000
        /**
         * Bug 4 fix: only seed [seenMangaUrls] from the last 24 hours of shown history
         * on startup. Seeding from 30 days caused hundreds of manga to be globally filtered
         * out, making soft refreshes return almost no new content over time.
         */
        private const val RECENT_HISTORY_SEED_MILLIS = 24L * 60 * 60 * 1_000
    }
}
