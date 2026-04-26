package eu.kanade.tachiyomi.ui.suggestions

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.manga.MangaRepository

data class SuggestionsState(
    val suggestions: Map<String, List<Manga>> = emptyMap(),
    val isLoading: Boolean = true
)

class SuggestionsPresenter(
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get()
) : BaseCoroutinePresenter<SuggestionsController>() {

    private val _state = MutableStateFlow(SuggestionsState())
    val state: StateFlow<SuggestionsState> = _state.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        fetchSuggestions()
    }

    private fun fetchSuggestions() {
        presenterScope.launchIO {
            _state.value = SuggestionsState(isLoading = true)

            // 1. Fetch library manga where favorite == true
            val libraryManga = mangaRepository.getLibraryManga().map { it.manga }.filter { it.favorite }

            // 2. Extract genres, find top 3
            val genreCounts = mutableMapOf<String, Int>()
            libraryManga.forEach { manga ->
                manga.genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach { genre ->
                    genreCounts[genre] = genreCounts.getOrDefault(genre, 0) + 1
                }
            }

            val topTags = genreCounts.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }

            if (topTags.isEmpty()) {
                _state.value = SuggestionsState(isLoading = false)
                return@launchIO
            }

            // 3. Query pinned sources using these tags
            val pinnedCatalogues = preferences.pinnedCatalogues().get()
            val sourcesToSearch = sourceManager.getCatalogueSources().filter { it.id.toString() in pinnedCatalogues }
            
            val suggestionsMap = mutableMapOf<String, List<Manga>>()

            for (tag in topTags) {
                val tagResults = mutableListOf<Manga>()
                for (source in sourcesToSearch) {
                    try {
                        val page = source.fetchSearchManga(1, tag, source.getFilterList()).toBlocking().first()
                        val mapped = page.mangas.map { sManga ->
                            val m = Manga.create(sManga.url, sManga.title, source.id)
                            m.thumbnail_url = sManga.thumbnail_url
                            m
                        }
                        tagResults.addAll(mapped)
                    } catch (e: Exception) {
                        // ignore errors
                    }
                }
                if (tagResults.isNotEmpty()) {
                    suggestionsMap[tag] = tagResults.distinctBy { it.url }.take(20)
                }
            }

            _state.value = SuggestionsState(suggestions = suggestionsMap, isLoading = false)
        }
    }
}
