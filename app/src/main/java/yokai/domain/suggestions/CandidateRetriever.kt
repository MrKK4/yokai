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
import kotlinx.coroutines.coroutineScope
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
) {
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
                    sources.mapIndexed { sourceIndex, source ->
                        async {
                            fetchDiscoverySource(section, source, sourceIndex, page, requestGate, countBySource)
                        }
                    }
                else ->
                    sources.flatMapIndexed { sourceIndex, source ->
                        section.searchTerms.map { searchTerm ->
                            async {
                                fetchSearchSource(section, source, sourceIndex, page, searchTerm, requestGate, countBySource)
                            }
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
        val pageResult = sourceResult {
            requestGate.withPermit {
                when (section.sortOrder) {
                    SuggestionSortOrder.Latest ->
                        if (source.supportsLatest) source.getLatestUpdates(page) else source.getPopularManga(page)
                    SuggestionSortOrder.Popular -> source.getPopularManga(page)
                }
            }
        } ?: return emptyList()

        return cappedCandidates(section, source.id, sourceIndex, null, pageResult.mangas, countBySource)
    }

    private suspend fun fetchSearchSource(
        section: PlannedSection,
        source: CatalogueSource,
        sourceIndex: Int,
        page: Int,
        searchTerm: String,
        requestGate: Semaphore,
        countBySource: ConcurrentHashMap<Long, AtomicInteger>,
    ): List<SuggestionCandidate> {
        val pageResult = sourceResult {
            requestGate.withPermit {
                source.getSearchManga(page, searchTerm, source.searchFiltersFor(section.sortOrder))
            }
        } ?: return emptyList()

        return cappedCandidates(section, source.id, sourceIndex, searchTerm, pageResult.mangas, countBySource)
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

    private fun CatalogueSource.searchFiltersFor(sortOrder: SuggestionSortOrder): FilterList {
        val filters = getFilterList()
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

    private suspend fun <T> sourceResult(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {
        private const val MAX_ACTIVE_SOURCES = 10
        private const val MAX_CONCURRENT_SOURCE_REQUESTS = 4
        private val LATEST_SORT_KEYWORDS = setOf("latest", "recent", "update", "updated", "uploaded", "date", "new")
        private val POPULAR_SORT_KEYWORDS = setOf("popular", "views", "view", "follow", "follows", "rating", "score", "trend", "hot")
    }
}
