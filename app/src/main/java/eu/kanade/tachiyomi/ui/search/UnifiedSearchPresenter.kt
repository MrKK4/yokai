package eu.kanade.tachiyomi.ui.search

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
import yokai.domain.history.HistoryRepository
import yokai.domain.manga.MangaRepository

data class UnifiedSearchState(
    val libraryResults: List<Manga> = emptyList(),
    val historyResults: List<Manga> = emptyList(),
    val sourceResults: Map<CatalogueSource, List<Manga>> = emptyMap(),
    val isLoading: Boolean = false
)

class UnifiedSearchPresenter(
    val query: String? = "",
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val historyRepository: HistoryRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get()
) : BaseCoroutinePresenter<UnifiedSearchController>() {

    private val _state = MutableStateFlow(UnifiedSearchState())
    val state: StateFlow<UnifiedSearchState> = _state.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        search(query.orEmpty())
    }

    fun search(searchQuery: String) {
        if (searchQuery.isBlank()) return
        
        _state.value = UnifiedSearchState(isLoading = true)
        
        presenterScope.launchIO {
            // 1. Library Results
            val allLibraryManga = mangaRepository.getLibraryManga()
            val libraryMatches = allLibraryManga.map { it.manga }.filter { 
                it.title.contains(searchQuery, ignoreCase = true) 
            }.take(10) // Limit to top 10

            _state.value = _state.value.copy(libraryResults = libraryMatches)
            
            // 2. History Results
            val historyMatches = historyRepository.getRecentsUngrouped(false, searchQuery).map {
                it.manga
            }.take(10)
            
            _state.value = _state.value.copy(historyResults = historyMatches)

            // 3. Remote Sources (Pinned only for performance/spam reduction)
            val pinnedCatalogues = preferences.pinnedCatalogues().get()
            val sourcesToSearch = sourceManager.getCatalogueSources().filter { it.id.toString() in pinnedCatalogues }
            
            val remoteResults = mutableMapOf<CatalogueSource, List<Manga>>()
            
            for (source in sourcesToSearch) {
                try {
                    // Try to fetch 1 page of results
                    val page = source.fetchSearchManga(1, searchQuery, source.getFilterList()).toBlocking().first()
                    remoteResults[source] = page.mangas.map { sManga ->
                        val m = Manga.create(sManga.url, sManga.title, source.id)
                        m.thumbnail_url = sManga.thumbnail_url
                        m
                    }
                    _state.value = _state.value.copy(sourceResults = remoteResults)
                } catch (e: Exception) {
                    // Ignore errors for individual sources
                }
            }
            
            _state.value = _state.value.copy(isLoading = false)
        }
    }
}
