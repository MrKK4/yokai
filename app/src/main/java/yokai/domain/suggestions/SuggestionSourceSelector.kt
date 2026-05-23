package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource

data class SuggestionSourceSelection(
    val enabledLanguages: Set<String>,
    val hiddenSourceIds: Set<String>,
    val pinnedSourceIds: Set<String>,
    val recentSourceIds: Set<String>,
    val lastFetchedSourceIds: Set<String> = emptySet(),
)

object SuggestionSourceSelector {
    fun activeNetworkSources(
        sources: List<CatalogueSource>,
        selection: SuggestionSourceSelection,
        discovery: Boolean,
        maxSources: Int = SuggestionsConfig.MAX_ACTIVE_SOURCES,
        freshSourceFirst: Boolean = false,
    ): List<CatalogueSource> {
        val enabledSources = sources
            .filterNot { source -> source.id == LocalSource.ID }
            .filterNot { source -> source.id.toString() in selection.hiddenSourceIds }

        return enabledSources
            .sortedWith(
                if (freshSourceFirst) {
                    compareBy<CatalogueSource>(
                        { it.id.toString() in selection.lastFetchedSourceIds },
                        { it.id.toString() !in selection.pinnedSourceIds },
                        { it.id.toString() in selection.recentSourceIds },
                        { "(${it.lang}) ${it.name}" },
                    )
                } else {
                    compareBy<CatalogueSource>(
                        { it.id.toString() !in selection.pinnedSourceIds },
                        { it.id.toString() in selection.recentSourceIds },
                        { "(${it.lang}) ${it.name}" },
                    )
                },
            )
            .take(maxSources)
    }

    fun activeNetworkSourceIds(
        sources: List<CatalogueSource>,
        selection: SuggestionSourceSelection,
    ): Set<Long> =
        activeNetworkSources(
            sources = sources,
            selection = selection,
            discovery = false,
            maxSources = Int.MAX_VALUE,
        )
            .map { it.id }
            .toSet()
}
