package eu.kanade.tachiyomi.ui.suggestions

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.suggestions.SuggestionsWorker
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchIO
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
import yokai.domain.suggestions.GetUserSuggestionQueriesUseCase
import yokai.domain.suggestions.CandidateRetriever
import yokai.domain.suggestions.CandidateRetrievalResult
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
import yokai.domain.suggestions.SuggestionSeenLogRepository
import yokai.domain.suggestions.SuggestedManga
import yokai.domain.suggestions.SuggestionSortOrder
import yokai.domain.suggestions.SuggestionsRepository
import yokai.domain.suggestions.SuggestionsConfig
import yokai.domain.suggestions.SuggestionsDebugLog
import yokai.domain.suggestions.LogType

data class SuggestionsState(
    val suggestions: Map<String, List<Manga>> = emptyMap(),
    val selectedReason: String? = null,
    val availableTags: List<String> = emptyList(),
    val blacklistedTags: Set<String> = emptySet(),
    val isTagFilterSheetVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isFetching: Boolean = false,
    val plannedSections: List<PlannedSection> = emptyList(),
    val nextBatchStartIndex: Int = 0,
    val isFetchingBatch: Boolean = false,
    val allSectionsLoaded: Boolean = false,
    val hasReachedEnd: Boolean = false,
    val endMessage: String? = null,
    val emptyMessage: String? = null,
    val sortOrder: SuggestionSortOrder = SuggestionSortOrder.Popular,
    val sheetReason: String? = null,
    val sheetResults: List<Manga> = emptyList(),
    val sheetIsLoading: Boolean = false,
    val sheetError: String? = null,
    val sheetSuppressed: Boolean = false,
    /**
     * Monotonically increasing counter that increments on every hard refresh.
     * [SuggestionsScreen] observes this to call [LazyGridState.scrollToItem](0)
     * so the grid returns to the top after a refresh without going through the
     * presenter's saved-position fields.
     */
    val scrollToTopTrigger: Long = 0L,
)

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
) : BaseCoroutinePresenter<SuggestionsController>() {

    private val _state = MutableStateFlow(
        SuggestionsState(sortOrder = preferences.suggestionsSortOrder().get()),
    )
    val state: StateFlow<SuggestionsState> = _state.asStateFlow()
    private val isForegroundRefreshing = AtomicBoolean(false)
    private val isPageFetching = AtomicBoolean(false)
    private val isSectionBatchFetching = AtomicBoolean(false)
    private val feedGeneration = AtomicLong(0L)
    private var isWorkerRefreshing = false
    /**
     * Debounce job for [setSortOrder]. Cancels any pending rebuild when the user
     * changes sort mode again within 300 ms, so only the final selection fires a
     * network request (v3 plan: "300 ms debounce sort changes").
     */
    private var sortDebounceJob: Job? = null
    private val usedTags = linkedSetOf<String>()
    private val seenMangaUrls = linkedSetOf<String>()
    private var knownTags = emptyList<String>()
    private var blacklistChangedInSheet = false
    var gridFirstVisibleItemIndex = 0
        private set
    var gridFirstVisibleItemScrollOffset = 0
        private set
    private val getSuggestionQueries: GetUserSuggestionQueriesUseCase = Injekt.get()
    private val feedAggregator: FeedAggregator = Injekt.get()
    private val sectionLastFetchedAt = ConcurrentHashMap<String, Long>()
    private var currentV2RefreshId = 0L
    /** Random page offset (1–3) re-rolled on every user-initiated refresh so each
     *  refresh fetches a different page from each source, guaranteeing variety. */
    @Volatile private var currentPageOffset: Int = 1
    /**
     * Guards against [onCreate] running its setup logic more than once.
     * Conductor recreates the View (and calls onViewCreated → presenter.onCreate)
     * each time the tab is re-selected, but the presenter itself is retained via
     * `by lazy`. Without this guard every tab switch would register duplicate
     * flow collectors and potentially trigger a spurious refresh.
     */
    private var isInitialized = false

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
            val history = shownHistoryRepository.getAllKeys()
            seenMangaUrls.addAll(history)
        }

        suggestionsRepository.getSuggestionsAsFlow()
            .onEach { suggestedList ->
                if (shouldKeepCurrentSuggestions(suggestedList)) return@onEach
                warmSessionMemory(suggestedList)
                val grouped = suggestedList.groupBy { it.reason }.mapValues { entry ->
                    entry.value
                        .distinctBy { it.source to it.url }
                        .map { suggested ->
                            MangaImpl(source = suggested.source, url = suggested.url).apply {
                                title = suggested.title
                                thumbnail_url = suggested.thumbnailUrl
                            }
                        }
                }
                val selectedReason = _state.value.selectedReason?.takeIf { it in grouped.keys }
                _state.update { it.copy(
                    suggestions = grouped,
                    selectedReason = selectedReason,
                    emptyMessage = it.emptyMessage.takeIf { grouped.isEmpty() },
                )}
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

        presenterScope.launchIO {
            if (preferences.suggestionsV2Enabled().get()) {
                restoreV2PlanState()
            }
            if (suggestionsRepository.count() == 0L) {
                refresh()
            }
        }
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
    fun refresh(hardRefresh: Boolean = false) {
        if (!isForegroundRefreshing.compareAndSet(false, true)) return
        feedGeneration.incrementAndGet()
        currentPageOffset = Random.nextInt(1, 8)   // randomize page 1–7 on each refresh
        isPageFetching.set(false)
        isSectionBatchFetching.set(false)
        // Reset the saved scroll position so that if the composition is recreated
        // (e.g. low-memory view destroy) it doesn't restore to a stale position.
        saveGridScrollPosition(index = 0, scrollOffset = 0)
        usedTags.clear()
        // seenMangaUrls is NOT cleared — it survives refreshes via persistent history

        // Load persisted tag rotation so we pick up where we left off
        usedTags.addAll(preferences.usedSuggestionTags().get())

        _state.update { it.copy(
            emptyMessage = null,
            plannedSections = emptyList(),
            nextBatchStartIndex = 0,
            isFetchingBatch = false,
            allSectionsLoaded = false,
            hasReachedEnd = false,
            endMessage = null,
            sheetReason = null,
            sheetResults = emptyList(),
            sheetIsLoading = false,
            sheetError = null,
            sheetSuppressed = false,
            // Increment trigger so SuggestionsScreen scrolls the LazyGrid back to the top.
            scrollToTopTrigger = it.scrollToTopTrigger + 1L,
        )}
        updateLoadingState()

        val generation = feedGeneration.get()
        val pageOffset = currentPageOffset

        presenterScope.launchIO {
            try {
                if (preferences.suggestionsV2Enabled().get()) {
                    val hasExistingContent = suggestionsRepository.count() > 0L
                    if (!hardRefresh && hasExistingContent) {
                        // Soft path: re-rank locally, then fill any stale sections from network.
                        softRefreshV2(generation = generation, pageOffset = pageOffset)
                    } else {
                        // Hard path: wipe DB and do a full network fetch.
                        refreshV2(generation = generation, pageOffset = pageOffset)
                    }
                } else {
                    val suggestions = buildFreshSuggestions(pageOffset)
                    suggestionsRepository.replaceAll(suggestions)
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
            } catch (e: RefreshBlocked) {
                _state.update { it.copy(emptyMessage = e.userMessage) }
            } catch (_: Exception) {
                _state.update { it.copy(
                    emptyMessage = "Couldn't refresh suggestions. Check your connection and source extensions.",
                )}
            } finally {
                isForegroundRefreshing.set(false)
                updateLoadingState()
            }
        }
    }

    fun loadNextPage() {
        if (preferences.suggestionsV2Enabled().get()) {
            loadNextSectionBatch()
            return
        }
        loadNextPage(includeSourceSection = false)
    }

    private fun loadNextPage(includeSourceSection: Boolean) {
        val currentState = _state.value
        if (currentState.hasReachedEnd || currentState.selectedReason != null) return
        if (!isPageFetching.compareAndSet(false, true)) return
        val generation = feedGeneration.get()
        val pageOffset = currentPageOffset
        updateLoadingState()

        presenterScope.launchIO {
            try {
                val page = feedAggregator.fetchPage(
                    suggestionQueries = getSuggestionQueries.execute(),
                    usedTags = usedTags.toSet(),
                    seenMangaUrls = seenMangaUrls.toSet(),
                    currentSortOrder = _state.value.sortOrder,
                    includeSourceSection = includeSourceSection,
                    pageOffset = pageOffset,
                )
                if (generation != feedGeneration.get()) return@launchIO
                usedTags.addAll(page.usedTags)

                val newSuggestions = page.suggestions
                    .filterNot { it.memoryKey() in seenMangaUrls }
                val nextRank = suggestionsRepository.count()
                val rankedSuggestions = newSuggestions.withDisplayRanks(nextRank)
                if (rankedSuggestions.isNotEmpty()) {
                    suggestionsRepository.insertSuggestions(rankedSuggestions)
                    seenMangaUrls.addAll(rankedSuggestions.map { it.memoryKey() })
                    // Persist shown manga into the 30-day history
                    shownHistoryRepository.insertAll(
                        rankedSuggestions.map { it.source to it.url },
                    )
                }

                _state.update { it.copy(
                    hasReachedEnd = page.hasReachedEnd,
                    endMessage = END_OF_FEED_MESSAGE.takeIf { page.hasReachedEnd },
                    emptyMessage = null,
                )}
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(
                    emptyMessage = "Couldn't load more suggestions. Check your connection and source extensions.",
                )}
            } finally {
                if (generation == feedGeneration.get()) {
                    isPageFetching.set(false)
                    updateLoadingState()
                }
            }
        }
    }

    fun setSelectedReason(reason: String?) {
        _state.update { state ->
            state.copy(selectedReason = reason?.takeIf { it in state.suggestions.keys })
        }
    }

    fun saveGridScrollPosition(index: Int, scrollOffset: Int) {
        gridFirstVisibleItemIndex = index.coerceAtLeast(0)
        gridFirstVisibleItemScrollOffset = scrollOffset.coerceAtLeast(0)
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
            preferences.suggestionsSortOrder().set(sortOrder)
            rebuildFeed(sortOrder = sortOrder)
        }
    }

    fun isSuggestionsV2Enabled(): Boolean =
        preferences.suggestionsV2Enabled().get()

    fun toggleSuggestionsV2Enabled() {
        preferences.suggestionsV2Enabled().set(!preferences.suggestionsV2Enabled().get())
        rebuildFeed(sortOrder = _state.value.sortOrder)
    }

    fun showTagFilterSheet() {
        blacklistChangedInSheet = false
        syncTagFilterState(preferences.suggestionsTagsBlacklist().get())
        _state.update { it.copy(isTagFilterSheetVisible = true) }
    }

    fun dismissTagFilterSheet() {
        val shouldRefresh = blacklistChangedInSheet
        blacklistChangedInSheet = false
        _state.update { it.copy(isTagFilterSheetVisible = false) }
        if (shouldRefresh) {
            rebuildFeed(sortOrder = _state.value.sortOrder)
        }
    }

    fun toggleTagBlacklist(tag: String) {
        val isBlacklisted = _state.value.blacklistedTags.any { it.normalizedQuery() == tag.normalizedQuery() }
        setTagBlacklisted(tag = tag, isBlacklisted = !isBlacklisted)
    }

    fun setTagBlacklisted(tag: String, isBlacklisted: Boolean) {
        val normalizedTag = tag.normalizedQuery()
        if (normalizedTag.isBlank()) return

        val current = preferences.suggestionsTagsBlacklist().get()
        val containsTag = current.any { it.normalizedQuery() == normalizedTag }
        if (containsTag == isBlacklisted) return

        val next = if (isBlacklisted) {
            current.filterNot { it.normalizedQuery() == normalizedTag }.toSet() + tag.trim()
        } else {
            current.filterNot { it.normalizedQuery() == normalizedTag }.toSet()
        }
        blacklistChangedInSheet = true
        preferences.suggestionsTagsBlacklist().set(next)
        syncTagFilterState(next)
    }

    fun expandSection(reason: String) {
        val query = extractQueryFromReason(reason) ?: return
        if (_state.value.sheetIsLoading) return

        _state.update { it.copy(
            sheetReason = reason,
            sheetResults = emptyList(),
            sheetIsLoading = true,
            sheetError = null,
        )}

        val currentSeenUrls = seenMangaUrls.toSet() // capture snapshot before coroutine

        presenterScope.launchIO {
            try {
                val results = feedAggregator.fetchExpandedSection(
                    query = query,
                    reason = reason,
                    sortOrder = _state.value.sortOrder,
                    seenMangaUrls = currentSeenUrls,
                )
                val mangaList = results.map { suggested ->
                    MangaImpl(source = suggested.source, url = suggested.url).apply {
                        title = suggested.title
                        thumbnail_url = suggested.thumbnailUrl
                    }
                }
                _state.update { it.copy(
                    sheetResults = mangaList,
                    sheetIsLoading = false,
                )}
            } catch (_: Exception) {
                _state.update { it.copy(
                    sheetIsLoading = false,
                    sheetError = "Couldn't load results. Check your connection.",
                )}
            }
        }
    }

    fun dismissExpandSheet() {
        _state.update { it.copy(
            sheetReason = null,
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
        if (_state.value.sheetReason == null) return
        _state.update { it.copy(sheetSuppressed = true) }
    }

    /**
     * Makes the sheet visible again. Called when SuggestionsController re-enters
     * the foreground (onChangeStarted isEnter).
     */
    fun restoreExpandSheet() {
        if (_state.value.sheetReason == null) return
        _state.update { it.copy(sheetSuppressed = false) }
    }

    private fun rebuildFeed(sortOrder: SuggestionSortOrder) {
        if (preferences.suggestionsV2Enabled().get()) {
            feedGeneration.incrementAndGet()
            currentPageOffset = Random.nextInt(1, 8)
            saveGridScrollPosition(index = 0, scrollOffset = 0)
            isForegroundRefreshing.set(false)
            isPageFetching.set(false)
            isSectionBatchFetching.set(false)
            _state.update { it.copy(
                suggestions = emptyMap(),
                selectedReason = null,
                isLoading = false,
                isFetching = false,
                plannedSections = emptyList(),
                nextBatchStartIndex = 0,
                isFetchingBatch = false,
                allSectionsLoaded = false,
                hasReachedEnd = false,
                endMessage = null,
                emptyMessage = null,
                sortOrder = sortOrder,
                sheetReason = null,
                sheetResults = emptyList(),
                sheetIsLoading = false,
                sheetError = null,
                sheetSuppressed = false,
                // Note: scrollToTopTrigger is NOT incremented here because refresh() below
                // will increment it. Incrementing twice would cause a double scroll-to-top.
            )}
            presenterScope.launchIO {
                suggestionsRepository.deleteAll()
                // Sort change always requires a hard network fetch (different API endpoints).
                refresh(hardRefresh = true)
            }
            return
        }

        feedGeneration.incrementAndGet()
        currentPageOffset = Random.nextInt(1, 8)   // re-roll page offset on sort/filter rebuild
        saveGridScrollPosition(index = 0, scrollOffset = 0)
        usedTags.clear()
        // seenMangaUrls is NOT cleared — persistent history handles deduplication
        isForegroundRefreshing.set(false)
        isPageFetching.set(false)
        isSectionBatchFetching.set(false)
        _state.update { it.copy(
            suggestions = emptyMap(),
            selectedReason = null,
            isLoading = false,
            isFetching = true,
            plannedSections = emptyList(),
            nextBatchStartIndex = 0,
            isFetchingBatch = false,
            allSectionsLoaded = false,
            hasReachedEnd = false,
            endMessage = null,
            emptyMessage = null,
            sortOrder = sortOrder,
            sheetReason = null,
            sheetResults = emptyList(),
            sheetIsLoading = false,
            sheetError = null,
            sheetSuppressed = false,
            scrollToTopTrigger = it.scrollToTopTrigger + 1L,
        )}
        presenterScope.launchIO {
            suggestionsRepository.deleteAll()
            loadNextPage(includeSourceSection = true)
        }
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
        // Algorithm Issue 1: detect cold-start (no library) before fetching
        val librarySize = getManga.awaitAll().size
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
        _state.update { it.copy(
            hasReachedEnd = page.hasReachedEnd,
            endMessage = END_OF_FEED_MESSAGE.takeIf { page.hasReachedEnd },
        )}
        if (suggestions.isEmpty()) {
            throw RefreshBlocked(
                when {
                    librarySize == 0 ->
                        "Add some manga to your library to get personalized suggestions."
                    page.hasReachedEnd ->
                        END_OF_FEED_MESSAGE
                    else ->
                        "No latest updates or personalized matches came back from your active sources."
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
     * 5. In the background, fetch any sections whose cache has expired.
     */
    private suspend fun softRefreshV2(generation: Long, pageOffset: Int) {
        val now = System.currentTimeMillis()
        debugLog.add(LogType.REFRESH_MODE, "Soft refresh - re-ranking locally, fetching stale sections")

        // Step 1: Rebuild profile from local DB (no network call).
        interestProfileBuilder.buildProfile(now)
        syncLegacyTagStateForV2(now)
        val profiles = tagProfileRepository.getAllProfiles()

        // Step 2: Re-plan sections in new affinity order.
        val plannedSections = sectionPlanner.plan(
            profiles = profiles,
            sortOrder = _state.value.sortOrder,
            now = now,
        )
        plannedSectionRepository.replaceAll(plannedSections)

        // Step 3: Re-rank existing stored suggestions using new section order.
        val existing = suggestionsRepository.getSuggestions()
        if (existing.isNotEmpty()) {
            // Build a map of sectionKey → list of existing candidates for that section.
            val bySectionKey = existing.groupBy { suggestion ->
                plannedSections.firstOrNull { it.displayReason == suggestion.reason }?.sectionKey
                    ?: suggestion.reason.lowercase().trim()
            }
            val reRanked = plannedSections.flatMapIndexed { sectionIndex, section ->
                val sectionSuggestions = bySectionKey[section.sectionKey].orEmpty()
                sectionSuggestions.mapIndexed { itemIndex, suggestion ->
                    suggestion.copy(
                        displayRank = (sectionIndex * SECTION_DISPLAY_RANK_STRIDE) + itemIndex.toLong(),
                    )
                }
            }
            if (reRanked.isNotEmpty()) {
                suggestionsRepository.replaceAll(reRanked)
                debugLog.add(LogType.REFRESH_MODE, "Soft refresh: re-ranked ${reRanked.size} items across ${plannedSections.size} sections")
            }
        }

        // Step 4: Update planned-section state so scroll and batch logic is correct.
        val refreshId = preferences.suggestionsTotalRefreshCount().get().toLong()
        currentV2RefreshId = refreshId

        val staleSections = plannedSections.filter { !isSectionCacheFresh(it, now) }
        _state.update { it.copy(
            plannedSections = plannedSections,
            nextBatchStartIndex = 0,
            isFetchingBatch = false,
            allSectionsLoaded = staleSections.isEmpty(),
            hasReachedEnd = staleSections.isEmpty(),
            endMessage = if (staleSections.isEmpty()) sectionEndMessage(plannedSections.size, plannedSections.size) else null,
            emptyMessage = null,
        )}

        // Step 5: Kick off batch fetches for any stale sections.
        if (staleSections.isNotEmpty() && generation == feedGeneration.get()) {
            var inserted = loadNextSectionBatchInternal(generation = generation, pageOffset = pageOffset, refreshId = refreshId)
            while (generation == feedGeneration.get() && inserted == 0 && !_state.value.allSectionsLoaded) {
                inserted += loadNextSectionBatchInternal(generation = generation, pageOffset = pageOffset, refreshId = refreshId)
            }
        }

        if (suggestionsRepository.count() == 0L) {
            throw RefreshBlocked(
                if (getManga.awaitAll().isEmpty()) {
                    "Add some manga to your library to get personalized suggestions."
                } else {
                    "No latest updates or personalized matches came back from your active sources."
                },
            )
        }
    }

    private suspend fun refreshV2(generation: Long, pageOffset: Int) {
        val now = System.currentTimeMillis()
        debugLog.add(LogType.REFRESH_MODE, "Hard refresh - rebuilding profile, resetting seen log")
        suggestionSeenLogRepository.deleteOlderThan(now - SuggestionsConfig.SEEN_LOG_TTL_MS)
        suggestionsRepository.deleteAll()
        sectionLastFetchedAt.clear()

        interestProfileBuilder.buildProfile(now)
        syncLegacyTagStateForV2(now)
        val profiles = tagProfileRepository.getAllProfiles()

        val plannedSections = sectionPlanner.plan(
            profiles = profiles,
            sortOrder = _state.value.sortOrder,
            now = now,
        )
        plannedSectionRepository.replaceAll(plannedSections)

        val totalRefreshCount = preferences.suggestionsTotalRefreshCount()
        val refreshId = totalRefreshCount.get() + 1L
        totalRefreshCount.set(refreshId.toInt())
        currentV2RefreshId = refreshId
        preferences.suggestionsLastHardRefreshAt().set(now)

        _state.update { it.copy(
            plannedSections = plannedSections,
            nextBatchStartIndex = 0,
            isFetchingBatch = false,
            allSectionsLoaded = plannedSections.isEmpty(),
            hasReachedEnd = plannedSections.isEmpty(),
            endMessage = null,
            emptyMessage = null,
        )}

        var inserted = loadNextSectionBatchInternal(
            generation = generation,
            pageOffset = pageOffset,
            refreshId = refreshId,
        )
        while (generation == feedGeneration.get() && inserted == 0 && !_state.value.allSectionsLoaded) {
            inserted += loadNextSectionBatchInternal(
                generation = generation,
                pageOffset = pageOffset,
                refreshId = refreshId,
            )
        }

        if (suggestionsRepository.count() == 0L) {
            throw RefreshBlocked(
                if (getManga.awaitAll().isEmpty()) {
                    "Add some manga to your library to get personalized suggestions."
                } else {
                    "No latest updates or personalized matches came back from your active sources."
                },
            )
        }
    }

    private suspend fun restoreV2PlanState() {
        val plannedSections = plannedSectionRepository.getPlannedSections()
        if (plannedSections.isEmpty()) return

        val loadedReasons = suggestionsRepository.getSuggestions()
            .map { it.reason }
            .toSet()
        val nextIndex = plannedSections
            .indexOfLast { it.displayReason in loadedReasons }
            .plus(1)
            .coerceAtLeast(0)
        val allLoaded = nextIndex >= plannedSections.size
        currentV2RefreshId = preferences.suggestionsTotalRefreshCount().get().toLong()
        _state.update { it.copy(
            plannedSections = plannedSections,
            nextBatchStartIndex = nextIndex,
            allSectionsLoaded = allLoaded,
            hasReachedEnd = allLoaded,
            endMessage = sectionEndMessage(nextIndex, plannedSections.size).takeIf { allLoaded },
        )}
    }

    private fun loadNextSectionBatch() {
        val generation = feedGeneration.get()
        val pageOffset = currentPageOffset
        val refreshId = currentV2RefreshId.takeIf { it > 0L }
            ?: preferences.suggestionsTotalRefreshCount().get().toLong()
        presenterScope.launchIO {
            loadNextSectionBatchInternal(
                generation = generation,
                pageOffset = pageOffset,
                refreshId = refreshId,
            )
        }
    }

    private suspend fun loadNextSectionBatchInternal(
        generation: Long,
        pageOffset: Int,
        refreshId: Long,
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

            candidateRetriever.retrieveProgressively(sectionsToFetch, pageOffset) { result ->
                if (generation == feedGeneration.get()) {
                    insertedCount += appendSectionResult(
                        result = result,
                        refreshId = refreshId,
                        rankingContext = rankingContext,
                        sectionSeenKeys = allSectionSeenKeys,
                        now = now,
                    )
                }
            }
        } finally {
            val nextIndex = (batchStartIndex + batch.size).coerceAtMost(state.plannedSections.size)
            val allLoaded = nextIndex >= state.plannedSections.size
            isSectionBatchFetching.set(false)
            if (generation == feedGeneration.get()) {
                _state.update { current ->
                    current.copy(
                        nextBatchStartIndex = nextIndex,
                        isFetchingBatch = false,
                        isFetching = false,
                        allSectionsLoaded = allLoaded,
                        hasReachedEnd = allLoaded,
                        endMessage = sectionEndMessage(nextIndex, current.plannedSections.size).takeIf { allLoaded },
                    )
                }
                // Speculatively start the next batch immediately so it's ready before
                // the user scrolls to the bottom. The scroll threshold in ReportLoadMoreState
                // is a safety-net fallback only.
                if (!allLoaded) {
                    speculativelyPrefetchNextBatch(generation = generation, pageOffset = pageOffset, refreshId = refreshId)
                }
            } else {
                updateLoadingState()
            }
        }

        return insertedCount
    }

    /**
     * Fire-and-forget enqueue of the next section batch. This keeps the pipeline
     * flowing so the next batch is usually ready by the time the user scrolls
     * to the bottom of the current one.
     *
     * Uses [isSectionBatchFetching] as a guard so it's a no-op if a fetch is
     * already in-flight (e.g. triggered by both speculative pre-fetch and the
     * scroll threshold at the same time).
     */
    private fun speculativelyPrefetchNextBatch(generation: Long, pageOffset: Int, refreshId: Long) {
        if (isSectionBatchFetching.get()) return
        presenterScope.launchIO {
            loadNextSectionBatchInternal(
                generation = generation,
                pageOffset = pageOffset,
                refreshId = refreshId,
            )
        }
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
    ): Int {
        var suggestions = suggestionRanker.rankWithContext(
            retrievalResults = listOf(result),
            context = rankingContext,
            globalSeenKeys = seenMangaUrls.toSet(),
            sectionSeenKeys = sectionSeenKeys,
            sessionContext = sessionContext,
        ).withSectionDisplayRanks(result.section)

        sectionLastFetchedAt[result.section.sectionKey] = now
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
                retrievalResults = listOf(result),
                context = rankingContext,
                globalSeenKeys = seenMangaUrls.toSet(),
                // Pass empty seen keys for this section so items seen > 12 h ago are allowed.
                sectionSeenKeys = sectionSeenKeys - result.section.sectionKey,
                sessionContext = sessionContext,
            ).withSectionDisplayRanks(result.section)
            if (relaxedSuggestions.size > suggestions.size) {
                suggestions = relaxedSuggestions
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

        suggestionsRepository.insertSuggestions(suggestions)
        shownHistoryRepository.insertAll(suggestions.map { it.source to it.url })
        seenMangaUrls.addAll(suggestions.map { it.memoryKey() })
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
            hasReachedEnd = true,
            isFetchingBatch = false,
            isFetching = false,
            endMessage = sectionEndMessage(it.nextBatchStartIndex, it.plannedSections.size),
        )}
    }

    private suspend fun syncLegacyTagStateForV2(now: Long) {
        preferences.suggestionsPinnedTags().get().forEach { rawTag ->
            val canonicalTag = tagCanonicalizer.canonicalize(rawTag).canonicalKey
            if (canonicalTag.isNotBlank()) {
                tagProfileRepository.setTagState(canonicalTag, TagState.PINNED, now)
            }
        }
        preferences.suggestionsTagsBlacklist().get().forEach { rawTag ->
            val canonicalTag = tagCanonicalizer.canonicalize(rawTag).canonicalKey
            if (canonicalTag.isNotBlank()) {
                tagProfileRepository.setTagState(canonicalTag, TagState.BLACKLISTED, now)
            }
        }
    }

    private fun updateLoadingState() {
        _state.update { it.copy(
            isLoading = isForegroundRefreshing.get() || isWorkerRefreshing,
            // isFetching drives the pagination spinner only — not the full-refresh spinner
            isFetching = (isPageFetching.get() || isSectionBatchFetching.get()) && !isForegroundRefreshing.get(),
            isFetchingBatch = isSectionBatchFetching.get(),
        )}
    }

    private fun shouldKeepCurrentSuggestions(suggestedList: List<SuggestedManga>): Boolean {
        return suggestedList.isEmpty() &&
            _state.value.suggestions.isNotEmpty() &&
            !isForegroundRefreshing.get() &&
            !isPageFetching.get() &&
            !isSectionBatchFetching.get() &&
            !isWorkerRefreshing
    }

    private fun warmSessionMemory(suggestions: List<SuggestedManga>) {
        if (suggestions.isEmpty()) return
        if (seenMangaUrls.isEmpty()) {
            seenMangaUrls.addAll(suggestions.map { it.memoryKey() })
        }
        if (usedTags.isEmpty()) {
            usedTags.addAll(suggestions.mapNotNull { it.reason.toSuggestionQueryKey() })
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

    private fun sectionEndMessage(loadedCount: Int, plannedCount: Int): String =
        "All ${loadedCount.coerceAtMost(plannedCount)} interest sections loaded"

    fun extractQueryFromReason(reason: String): String? {
        // Match all three reason tiers: "Because you love X",
        // "Because you often read X", "Because you read X", and saved-search.
        val tagPrefix = READ_REASON_PREFIXES.firstOrNull { reason.startsWith(it) }
        return when {
            tagPrefix != null ->
                reason.removePrefix(tagPrefix)
            reason.startsWith(PINNED_REASON_PREFIX) ->
                reason.removePrefix(PINNED_REASON_PREFIX)
            reason.startsWith(SEARCH_REASON_PREFIX) ->
                reason.removePrefix(SEARCH_REASON_PREFIX).removeSuffix("\"")
            else -> null
        }?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun String.toSuggestionQueryKey(): String? {
        val tagPrefix = READ_REASON_PREFIXES.firstOrNull { startsWith(it) }
        return when {
            tagPrefix != null -> removePrefix(tagPrefix)
            startsWith(PINNED_REASON_PREFIX) -> removePrefix(PINNED_REASON_PREFIX)
            startsWith(SEARCH_REASON_PREFIX) -> removePrefix(SEARCH_REASON_PREFIX).substringBeforeLast("\"")
            else -> null
        }
            ?.normalizedQuery()
            ?.takeIf { it.isNotBlank() }
    }

    private fun syncTagFilterState(blacklistedTags: Set<String>) {
        val availableTags = (knownTags + blacklistedTags)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizedQuery() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        _state.update { it.copy(
            availableTags = availableTags,
            blacklistedTags = blacklistedTags,
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

    private class RefreshBlocked(val userMessage: String) : Exception(userMessage)

    internal companion object {
        private const val END_OF_FEED_MESSAGE = "Read more manga to get more suggestions."
        // Ordered longest-first so the more specific prefixes match before the shorter one.
        internal val READ_REASON_PREFIXES = listOf(
            "Because you love ",
            "Because you often read ",
            "Because you read ",
        )
        internal const val PINNED_REASON_PREFIX = "Pinned: "
        /** Legacy alias kept so call-sites that use READ_REASON_PREFIX still compile. */
        internal const val READ_REASON_PREFIX = "Because you read "
        internal const val SEARCH_REASON_PREFIX = "Because you searched \""
        private const val SECTION_DISPLAY_RANK_STRIDE = 1_000L
        private val WHITESPACE = Regex("\\s+")
        /** Shown manga older than 30 days become eligible to appear again. */
        private const val HISTORY_TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}
