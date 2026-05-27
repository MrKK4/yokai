package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import kotlin.random.Random

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
        freshSourceSeed: Int? = null,
    ): List<CatalogueSource> {
        val enabledSources = sources
            .filterNot { source -> source.id == LocalSource.ID }
            .filterNot { source -> source.id.toString() in selection.hiddenSourceIds }

        val sortedSources = enabledSources
            .sortedWith(
                if (freshSourceFirst) {
                    compareBy<CatalogueSource>(
                        { it.id.toString() !in selection.pinnedSourceIds },
                        { it.id.toString() in selection.lastFetchedSourceIds },
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

        return sortedSources
            .maybeShuffleSourceTiers(selection, freshSourceFirst, freshSourceSeed)
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

    private fun List<CatalogueSource>.maybeShuffleSourceTiers(
        selection: SuggestionSourceSelection,
        freshSourceFirst: Boolean,
        freshSourceSeed: Int?,
    ): List<CatalogueSource> {
        if (freshSourceSeed == null) return this

        return groupBy { source -> source.tier(selection, freshSourceFirst) }
            .toSortedMap()
            .flatMap { (tier, sources) ->
                if (tier == 0) {
                    sources
                } else {
                    sources.shuffled(Random(freshSourceSeed + tier))
                }
            }
    }

    private fun CatalogueSource.tier(selection: SuggestionSourceSelection, freshSourceFirst: Boolean): Int {
        val id = id.toString()
        val pinned = id in selection.pinnedSourceIds
        val recent = id in selection.recentSourceIds
        val lastFetched = id in selection.lastFetchedSourceIds

        return when {
            pinned -> 0
            freshSourceFirst && !lastFetched && !recent -> 1
            freshSourceFirst && !lastFetched -> 2
            freshSourceFirst && !recent -> 3
            freshSourceFirst -> 4
            !recent -> 1
            else -> 2
        }
    }
}
