package eu.kanade.tachiyomi.ui.suggestions

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.suggestions.SuggestionsWorker
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate
import yokai.domain.suggestions.FeedAggregator
import yokai.domain.suggestions.GetUserSuggestionQueriesUseCase
import yokai.domain.suggestions.SuggestedManga
import yokai.domain.suggestions.SuggestionsRepository

data class SuggestionsState(
    val suggestions: Map<String, List<Manga>> = emptyMap(),
    val selectedReason: String? = null,
    val isLoading: Boolean = false,
    val emptyMessage: String? = null,
)

class SuggestionsPresenter(
    private val context: Context,
    private val suggestionsRepository: SuggestionsRepository = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val insertManga: InsertManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
) : BaseCoroutinePresenter<SuggestionsController>() {

    private val _state = MutableStateFlow(SuggestionsState())
    val state: StateFlow<SuggestionsState> = _state.asStateFlow()
    private val isForegroundRefreshing = AtomicBoolean(false)
    private var isWorkerRefreshing = false
    private val getSuggestionQueries: GetUserSuggestionQueriesUseCase = Injekt.get()
    private val feedAggregator: FeedAggregator = Injekt.get()

    override fun onCreate() {
        super.onCreate()

        suggestionsRepository.getSuggestionsAsFlow()
            .onEach { suggestedList ->
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
                _state.value = _state.value.copy(
                    suggestions = grouped,
                    selectedReason = selectedReason,
                    emptyMessage = _state.value.emptyMessage.takeIf { grouped.isEmpty() },
                )
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
        _state.value = _state.value.copy(emptyMessage = null)
        updateLoadingState()

        presenterScope.launchIO {
            try {
                val suggestions = buildFreshSuggestions()
                suggestionsRepository.replaceAll(suggestions)
                _state.value = _state.value.copy(emptyMessage = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RefreshBlocked) {
                _state.value = _state.value.copy(emptyMessage = e.userMessage)
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    emptyMessage = "Couldn't refresh suggestions. Check your connection and source extensions.",
                )
            } finally {
                isForegroundRefreshing.set(false)
                updateLoadingState()
            }
        }
    }

    fun setSelectedReason(reason: String?) {
        _state.value = _state.value.copy(
            selectedReason = reason?.takeIf { it in _state.value.suggestions.keys },
        )
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

    private suspend fun buildFreshSuggestions(): List<SuggestedManga> {
        val suggestionQueries = getSuggestionQueries.execute()
        val suggestions = feedAggregator.fetch(suggestionQueries)
        if (suggestions.isEmpty()) {
            throw RefreshBlocked("No latest updates or personalized matches came back from your active sources.")
        }
        return suggestions
    }

    private fun updateLoadingState() {
        _state.value = _state.value.copy(
            isLoading = isForegroundRefreshing.get() || isWorkerRefreshing,
        )
    }

    private class RefreshBlocked(val userMessage: String) : Exception(userMessage)
}
