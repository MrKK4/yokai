package eu.kanade.tachiyomi.ui.search

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
            coroutineScope {
                // 1. Library Results
                val libraryJob = async {
                    val allLibraryManga = mangaRepository.getLibraryManga()
                    allLibraryManga.map { it.manga }.filter { 
                        it.title.contains(searchQuery, ignoreCase = true) 
                    }.take(10) // Limit to top 10
                }

                // 2. History Results
                val historyJob = async {
                    historyRepository.getRecentsUngrouped(false, searchQuery).map {
                        it.manga
                    }.take(10)
                }

                // 3. Remote Sources (Pinned only for performance/spam reduction)
                val sourceJob = async {
                    val pinnedCatalogues = preferences.pinnedCatalogues().get()
                    val sourcesToSearch = sourceManager.getCatalogueSources().filter { it.id.toString() in pinnedCatalogues }       

                    val remoteResults = mutableMapOf<CatalogueSource, List<Manga>>()

                    val deferreds = sourcesToSearch.map { source ->
                        async {
                            try {
                                val page = source.fetchSearchManga(1, searchQuery, source.getFilterList()).toBlocking().first()
                                val mapped = page.mangas.map { sManga ->
                                    val m = Manga.create(sManga.url, sManga.title, source.id)
                                    m.thumbnail_url = sManga.thumbnail_url
                                    m
                                }
                                source to mapped
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                    deferreds.forEach { deferred ->
                        deferred.await()?.let { (source, mapped) ->
                            remoteResults[source] = mapped
                            _state.value = _state.value.copy(sourceResults = remoteResults)
                        }
                    }
                    remoteResults
                }

                val libraryMatches = libraryJob.await()
                _state.value = _state.value.copy(libraryResults = libraryMatches)

                val historyMatches = historyJob.await()
                _state.value = _state.value.copy(historyResults = historyMatches)

                val remoteResults = sourceJob.await()
                _state.value = _state.value.copy(sourceResults = remoteResults, isLoading = false)
            }
        }
    }
}
