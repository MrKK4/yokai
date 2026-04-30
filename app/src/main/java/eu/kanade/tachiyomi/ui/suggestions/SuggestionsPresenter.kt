package eu.kanade.tachiyomi.ui.suggestions

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.suggestions.SuggestionsWorker
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
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
import yokai.domain.suggestions.SuggestedManga
import yokai.domain.suggestions.SuggestionSortOrder
import yokai.domain.suggestions.SuggestionsRepository

data class SuggestionsState(
    val suggestions: Map<String, List<Manga>> = emptyMap(),
    val selectedReason: String? = null,
    val availableTags: List<String> = emptyList(),
    val blacklistedTags: Set<String> = emptySet(),
    val isTagFilterSheetVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isFetching: Boolean = false,
    val hasReachedEnd: Boolean = false,
    val endMessage: String? = null,
    val emptyMessage: String? = null,
    val sortOrder: SuggestionSortOrder = SuggestionSortOrder.Popular,
    val sheetReason: String? = null,
    val sheetResults: List<Manga> = emptyList(),
    val sheetIsLoading: Boolean = false,
    val sheetError: String? = null,
    val sheetSuppressed: Boolean = false,
)

class SuggestionsPresenter(
    private val context: Context,
    private val suggestionsRepository: SuggestionsRepository = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val insertManga: InsertManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
) : BaseCoroutinePresenter<SuggestionsController>() {

    private val _state = MutableStateFlow(
        SuggestionsState(sortOrder = preferences.suggestionsSortOrder().get()),
    )
    val state: StateFlow<SuggestionsState> = _state.asStateFlow()
    private val isForegroundRefreshing = AtomicBoolean(false)
    private val isPageFetching = AtomicBoolean(false)
    private val feedGeneration = AtomicLong(0L)
    private var isWorkerRefreshing = false
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
    /** Random page offset (1–3) re-rolled on every user-initiated refresh so each
     *  refresh fetches a different page from each source, guaranteeing variety. */
    @Volatile private var currentPageOffset: Int = 1

    override fun onCreate() {
        super.onCreate()

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
            if (suggestionsRepository.count() == 0L) {
                refresh()
            }
        }
    }

    fun refresh() {
        if (!isForegroundRefreshing.compareAndSet(false, true)) return
        feedGeneration.incrementAndGet()
        currentPageOffset = Random.nextInt(1, 4)   // randomize page 1–3 on each refresh
        isPageFetching.set(false)
        saveGridScrollPosition(index = 0, scrollOffset = 0)
        usedTags.clear()
        seenMangaUrls.clear()
        _state.update { it.copy(
            emptyMessage = null,
            hasReachedEnd = false,
            endMessage = null,
            sheetReason = null,
            sheetResults = emptyList(),
            sheetIsLoading = false,
            sheetError = null,
            sheetSuppressed = false,
        )}
        updateLoadingState()

        presenterScope.launchIO {
            try {
                val suggestions = buildFreshSuggestions(currentPageOffset)
                suggestionsRepository.replaceAll(suggestions)
                _state.update { it.copy(emptyMessage = null) }
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
        _state.update { it.copy(
            selectedReason = reason?.takeIf { it in _state.value.suggestions.keys },
        )}
    }

    fun saveGridScrollPosition(index: Int, scrollOffset: Int) {
        gridFirstVisibleItemIndex = index.coerceAtLeast(0)
        gridFirstVisibleItemScrollOffset = scrollOffset.coerceAtLeast(0)
    }

    fun setSortOrder(sortOrder: SuggestionSortOrder) {
        if (_state.value.sortOrder == sortOrder) return
        preferences.suggestionsSortOrder().set(sortOrder)
        rebuildFeed(sortOrder = sortOrder)
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

        presenterScope.launchIO {
            try {
                val results = feedAggregator.fetchExpandedSection(
                    query = query,
                    reason = reason,
                    sortOrder = _state.value.sortOrder,
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
        feedGeneration.incrementAndGet()
        currentPageOffset = Random.nextInt(1, 4)   // re-roll page offset on sort/filter rebuild
        saveGridScrollPosition(index = 0, scrollOffset = 0)
        usedTags.clear()
        seenMangaUrls.clear()
        isForegroundRefreshing.set(false)
        isPageFetching.set(false)
        _state.update { it.copy(
            suggestions = emptyMap(),
            selectedReason = null,
            isLoading = false,
            isFetching = true,
            hasReachedEnd = false,
            endMessage = null,
            emptyMessage = null,
            sortOrder = sortOrder,
            sheetReason = null,
            sheetResults = emptyList(),
            sheetIsLoading = false,
            sheetError = null,
            sheetSuppressed = false,
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
        val page = feedAggregator.fetchPage(
            suggestionQueries = getSuggestionQueries.execute(),
            usedTags = emptySet(),
            seenMangaUrls = emptySet(),
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
                if (page.hasReachedEnd) {
                    END_OF_FEED_MESSAGE
                } else {
                    "No latest updates or personalized matches came back from your active sources."
                },
            )
        }
        return suggestions
    }

    private fun updateLoadingState() {
        _state.update { it.copy(
            isLoading = isForegroundRefreshing.get() || isWorkerRefreshing,
            isFetching = isForegroundRefreshing.get() || isPageFetching.get(),
        )}
    }

    private fun shouldKeepCurrentSuggestions(suggestedList: List<SuggestedManga>): Boolean {
        return suggestedList.isEmpty() &&
            _state.value.suggestions.isNotEmpty() &&
            !isForegroundRefreshing.get() &&
            !isPageFetching.get() &&
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

    private fun SuggestedManga.memoryKey(): String =
        "$source:$url"

    fun extractQueryFromReason(reason: String): String? {
        return when {
            reason.startsWith(READ_REASON_PREFIX) ->
                reason.removePrefix(READ_REASON_PREFIX)
            reason.startsWith(SEARCH_REASON_PREFIX) ->
                reason.removePrefix(SEARCH_REASON_PREFIX).removeSuffix("\"")
            else -> null
        }?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun String.toSuggestionQueryKey(): String? {
        return when {
            startsWith(READ_REASON_PREFIX) -> removePrefix(READ_REASON_PREFIX)
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
        internal const val READ_REASON_PREFIX = "Because you read "
        internal const val SEARCH_REASON_PREFIX = "Because you searched \""
        private val WHITESPACE = Regex("\\s+")
    }
}
