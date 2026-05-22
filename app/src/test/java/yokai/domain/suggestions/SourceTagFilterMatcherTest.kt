package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SourceTagFilterMatcherTest {

    @Test
    fun `matcher canonicalizes tristate tag filters`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)
        val shounen = object : Filter.TriState("Shounen") {}
        val source = sourceWithFilters(
            FilterList(
                object : Filter.Group<Filter<*>>("Tags", listOf(shounen)) {},
            ),
        )

        val filters = source.tryIncludeTagFilter("shonen", canonicalizer)

        assertNotNull(filters)
        assertEquals(Filter.TriState.STATE_INCLUDE, shounen.state)
    }

    @Test
    fun `matcher canonicalizes select genre filters`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)
        val genre = object : Filter.Select<String>("Genre", arrayOf("Any", "Sci-Fi", "Romance")) {}
        val source = sourceWithFilters(FilterList(genre))

        val filters = source.tryIncludeTagFilter("science fiction", canonicalizer)

        assertNotNull(filters)
        assertEquals(1, genre.state)
    }

    @Test
    fun `sort matcher applies latest sort without another filter pass`() {
        val sort = object : Filter.Sort("Sort", arrayOf("Popular", "Latest Update")) {}
        val filters = FilterList(sort)

        val applied = filters.tryApplySuggestionSort(SuggestionSortOrder.Latest)

        assertEquals(true, applied)
        assertEquals(Filter.Sort.Selection(1, ascending = false), sort.state)
    }

    @Test
    fun `sort matcher applies only obvious select sort filters`() {
        val genre = object : Filter.Select<String>("Genre", arrayOf("Any", "Popular")) {}
        val order = object : Filter.Select<String>("Order by", arrayOf("Title", "Popularity")) {}
        val filters = FilterList(genre, order)

        val applied = filters.tryApplySuggestionSort(SuggestionSortOrder.Popular)

        assertEquals(true, applied)
        assertEquals(0, genre.state)
        assertEquals(1, order.state)
    }

    private fun sourceWithFilters(filters: FilterList): CatalogueSource =
        object : CatalogueSource {
            override val id: Long = 1L
            override val name: String = "Test Source"
            override val lang: String = "en"
            override val supportsLatest: Boolean = true

            override fun getFilterList(): FilterList = filters
        }
}
