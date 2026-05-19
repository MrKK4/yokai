package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SuggestionSourceSelectorTest {

    @Test
    fun `selector excludes local and hidden sources but keeps installed sources across languages`() {
        val sources = listOf(
            source(LocalSource.ID, "Local", "other"),
            source(1L, "Enabled", "en"),
            source(2L, "Different language", "ja"),
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

        assertEquals(listOf(1L, 2L), selected.map { it.id })
        assertFalse(selected.any { it.id == LocalSource.ID })
    }

    @Test
    fun `discovery prefers pinned sources without excluding other installed sources`() {
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

        assertEquals(listOf(2L, 4L, 1L, 3L), selected.map { it.id })
    }

    @Test
    fun `selector returns every installed active source id`() {
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

        assertEquals((1L..15L).toSet(), selected.map { it.id }.toSet())
        assertEquals((1L..15L).toSet(), activeIds)
    }

    @Test
    fun `fresh source first sorts unused sources before last fetched`() {
        val sources = listOf(
            source(1L, "s1", "en"),
            source(2L, "s2", "en"),
            source(3L, "s3", "en"),
            source(4L, "s4", "en"),
            source(5L, "s5", "en"),
        )

        val selected = SuggestionSourceSelector.activeNetworkSources(
            sources = sources,
            selection = selection(lastFetchedSourceIds = setOf("1", "2", "3")),
            discovery = false,
            freshSourceFirst = true,
        )

        // 4 and 5 are unused (fresh), then 1,2,3 (last fetched) by name
        assertEquals(listOf(4L, 5L, 1L, 2L, 3L), selected.map { it.id })
    }

    @Test
    fun `fresh source first off uses normal pinned-recent-name ordering`() {
        val sources = listOf(
            source(1L, "Alpha", "en"),
            source(2L, "Beta", "en"),
            source(3L, "Gamma", "en"),
            source(4L, "Delta", "en"),
        )

        val selected = SuggestionSourceSelector.activeNetworkSources(
            sources = sources,
            selection = selection(
                pinnedSourceIds = setOf("3"),
                lastFetchedSourceIds = setOf("1", "2"),
            ),
            discovery = false,
            freshSourceFirst = false,
        )

        assertEquals(listOf(3L, 1L, 2L, 4L), selected.map { it.id })
    }

    private fun selection(
        enabledLanguages: Set<String> = setOf("en"),
        hiddenSourceIds: Set<String> = emptySet(),
        pinnedSourceIds: Set<String> = emptySet(),
        recentSourceIds: Set<String> = emptySet(),
        lastFetchedSourceIds: Set<String> = emptySet(),
    ): SuggestionSourceSelection =
        SuggestionSourceSelection(
            enabledLanguages = enabledLanguages,
            hiddenSourceIds = hiddenSourceIds,
            pinnedSourceIds = pinnedSourceIds,
            recentSourceIds = recentSourceIds,
            lastFetchedSourceIds = lastFetchedSourceIds,
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
