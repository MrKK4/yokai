package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SuggestionSourceSelectorTest {

    @Test
    fun `selector excludes local hidden and disabled language sources`() {
        val sources = listOf(
            source(LocalSource.ID, "Local", "other"),
            source(1L, "Enabled", "en"),
            source(2L, "Disabled language", "ja"),
            source(3L, "Hidden", "en"),
        )

        val selected = SuggestionSourceSelector.activeNetworkSources(
            sources = sources,
            selection = selection(
                enabledLanguages = setOf("en"),
                hiddenSourceIds = setOf("3"),
            ),
            discovery = false,
        )

        assertEquals(listOf(1L), selected.map { it.id })
        assertFalse(selected.any { it.id == LocalSource.ID })
    }

    @Test
    fun `discovery uses pinned sources first when pins are available`() {
        val sources = listOf(
            source(1L, "Alpha", "en"),
            source(2L, "Beta", "en"),
            source(3L, "Gamma", "en"),
            source(4L, "Delta", "en"),
        )

        val selected = SuggestionSourceSelector.activeNetworkSources(
            sources = sources,
            selection = selection(
                pinnedSourceIds = setOf("2", "4"),
                recentSourceIds = setOf("4"),
            ),
            discovery = true,
        )

        assertEquals(listOf(4L, 2L), selected.map { it.id })
    }

    @Test
    fun `selector caps fetch sources but readiness sees all active source ids`() {
        val sources = (1L..15L).map { id -> source(id, "Source $id", "en") }
        val selected = SuggestionSourceSelector.activeNetworkSources(
            sources = sources,
            selection = selection(),
            discovery = false,
        )
        val activeIds = SuggestionSourceSelector.activeNetworkSourceIds(
            sources = sources,
            selection = selection(),
        )

        assertEquals(SuggestionsConfig.MAX_ACTIVE_SOURCES, selected.size)
        assertEquals((1L..15L).toSet(), activeIds)
    }

    private fun selection(
        enabledLanguages: Set<String> = setOf("en"),
        hiddenSourceIds: Set<String> = emptySet(),
        pinnedSourceIds: Set<String> = emptySet(),
        recentSourceIds: Set<String> = emptySet(),
    ): SuggestionSourceSelection =
        SuggestionSourceSelection(
            enabledLanguages = enabledLanguages,
            hiddenSourceIds = hiddenSourceIds,
            pinnedSourceIds = pinnedSourceIds,
            recentSourceIds = recentSourceIds,
        )

    private fun source(id: Long, name: String, lang: String): CatalogueSource =
        object : CatalogueSource {
            override val id: Long = id
            override val name: String = name
            override val lang: String = lang
            override val supportsLatest: Boolean = true

            override fun getFilterList(): FilterList = FilterList()
        }
}
