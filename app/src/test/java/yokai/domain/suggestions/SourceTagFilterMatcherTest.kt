package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `matcher reports real tag filter label diagnostics`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)
        val label = object : Filter.CheckBox("Big Breasts") {}
        val source = sourceWithFilters(
            FilterList(
                object : Filter.Group<Filter<*>>("Tags", listOf(label)) {},
            ),
        )

        val match = source.tryIncludeTagFilterWithDiagnostics("big breasts", canonicalizer)

        assertNotNull(match.filters)
        assertEquals("Big Breasts", match.matchedLabel)
        assertEquals("CHECKBOX", match.matchedKind)
        assertEquals(1, match.scannedLabels)
    }

    @Test
    fun `matcher selects only one equivalent native tag filter`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)
        val bigBreasts = object : Filter.CheckBox("Big Breasts") {}
        val hugeBreasts = object : Filter.CheckBox("Huge Breasts") {}
        val source = sourceWithFilters(
            FilterList(
                object : Filter.Group<Filter<*>>("Tags", listOf(bigBreasts, hugeBreasts)) {},
            ),
        )

        val match = source.tryIncludeTagFilterWithDiagnostics("big breasts", canonicalizer)

        assertNotNull(match.filters)
        assertEquals(true, bigBreasts.state)
        assertEquals(false, hugeBreasts.state)
        assertEquals("Big Breasts", match.matchedLabel)
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

    @Test
    fun `matcher skips broken hentaihand text tag resolver`() = runBlocking {
        // HentaiHand's /api/tags?q= resolver throws JsonDecodingException in practice (its Tags
        // field injection yields only errors, never results), so the matcher must not inject it —
        // it falls to text search and the chronic-empty cooldown benches it quietly.
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)
        val tagText = object : Filter.Text("Tags") {}
        val source = sourceWithFilters(
            filters = FilterList(tagText),
            name = "HentaiHand",
        )

        val filters = source.tryIncludeTagFilter("big breasts", canonicalizer)

        assertNull(filters)
        assertEquals("", tagText.state)
    }

    @Test
    fun `matcher skips broken nhentai variants with suffixes`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)
        val tagText = object : Filter.Text("Tags") {}
        val source = sourceWithFilters(
            filters = FilterList(tagText),
            name = "nHentai.com (unoriginal)",
        )

        val filters = source.tryIncludeTagFilter("milf", canonicalizer)

        assertNull(filters)
        assertEquals("", tagText.state)
    }

    @Test
    fun `matcher reports denied broken text tag field diagnostics`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)
        val tagText = object : Filter.Text("Tags") {}
        val source = sourceWithFilters(
            filters = FilterList(tagText),
            name = "nHentai.com (unoriginal)",
        )

        val match = source.tryIncludeTagFilterWithDiagnostics("milf", canonicalizer)

        assertNull(match.filters)
        assertEquals("Tags", match.textTagFieldName)
        assertEquals(true, match.textTagFieldDenied)
    }

    @Test
    fun `seed provides source specific tag terms`() {
        assertEquals("big breasts", SourceVocabularySeed.seedFor("nHentai.com")?.get("big breasts"))
        assertEquals("big breasts", SourceVocabularySeed.seedFor("nHentai.com (unoriginal)")?.get("big breasts"))
        assertEquals("milf", SourceVocabularySeed.seedFor("Hentai Hand")?.get("milf"))
        assertEquals("big breasts", SourceVocabularySeed.seedFor("Hentai Hand")?.get("big breasts"))
    }

    @Test
    fun `matcher retries getFilterList until async genres load then injects`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)
        val bigBreasts = object : Filter.CheckBox("Big Breasts") {}
        // First read returns only a Sort filter (genres not fetched yet — nothing to match);
        // the genre group appears on the second read, mirroring GalleryAdults' async requestTags().
        val sortOnly = FilterList(object : Filter.Sort("Sort", arrayOf("Popular", "Latest")) {})
        val withGenres = FilterList(object : Filter.Group<Filter<*>>("Tags", listOf(bigBreasts)) {})
        val source = sequencedSource(sortOnly, withGenres)

        val match = source.tryIncludeTagFilterWithDiagnostics(
            "big breasts",
            canonicalizer,
            maxFilterReloads = 2,
            reloadDelayMs = 0L,
        )

        assertNotNull(match.filters)
        assertEquals("Big Breasts", match.matchedLabel)
        assertEquals(true, bigBreasts.state)
    }

    @Test
    fun `matcher does not retry when genres are present but the tag is absent`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)
        val onlyOther = FilterList(
            object : Filter.Group<Filter<*>>("Tags", listOf(object : Filter.CheckBox("Romance") {})) {},
        )
        // Every read already has a populated genre group; the tag just isn't there. No reload needed.
        val source = sequencedSource(onlyOther, FilterList(object : Filter.CheckBox("Should Not Be Read") {}))

        val match = source.tryIncludeTagFilterWithDiagnostics(
            "big breasts",
            canonicalizer,
            maxFilterReloads = 2,
            reloadDelayMs = 0L,
        )

        assertNull(match.filters)
        assertEquals(1, (source as SequencedSource).reads, "scanned a populated group → must not reload")
    }

    @Test
    fun `awaitLoadedFilterList polls until genre labels appear`() = runBlocking {
        val sortOnly = FilterList(object : Filter.Sort("Sort", arrayOf("Popular")) {})
        val withGenres = FilterList(
            object : Filter.Group<Filter<*>>("Tags", listOf(object : Filter.CheckBox("Anal") {})) {},
        )
        val source = sequencedSource(sortOnly, sortOnly, withGenres)

        val loaded = source.awaitLoadedFilterList(attempts = 5, intervalMs = 0L)

        assertTrue(loaded.hasInjectableTagLabels())
        assertEquals(3, (source as SequencedSource).reads)
    }

    @Test
    fun `awaitLoadedFilterList stops immediately when labels already present`() = runBlocking {
        val withGenres = FilterList(
            object : Filter.Group<Filter<*>>("Tags", listOf(object : Filter.CheckBox("Anal") {})) {},
        )
        val source = sequencedSource(withGenres, FilterList())

        source.awaitLoadedFilterList(attempts = 5, intervalMs = 0L)

        assertEquals(1, (source as SequencedSource).reads)
    }

    private fun sourceWithFilters(
        filters: FilterList,
        name: String = "Test Source",
    ): CatalogueSource =
        object : CatalogueSource {
            override val id: Long = 1L
            override val name: String = name
            override val lang: String = "en"
            override val supportsLatest: Boolean = true

            override fun getFilterList(): FilterList = filters
        }

    /** Returns each supplied filter list on successive [getFilterList] calls (last repeats). */
    private class SequencedSource(private val lists: List<FilterList>) : CatalogueSource {
        var reads = 0
            private set
        override val id: Long = 1L
        override val name: String = "Sequenced"
        override val lang: String = "en"
        override val supportsLatest: Boolean = true
        override fun getFilterList(): FilterList = lists[minOf(reads++, lists.size - 1)]
    }

    private fun sequencedSource(vararg lists: FilterList): CatalogueSource = SequencedSource(lists.toList())
}
