package yokai.domain.suggestions

import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.manga.MangaRepository

class FeedAggregatorColdStartTest {

    @Test
    fun `empty query feed falls back to popular source discovery`() = runBlocking {
        val source = FakeCatalogueSource(
            id = 1L,
            titlePrefix = "Popular",
            supportsLatest = true,
        )
        val aggregator = aggregatorWith(source)

        val page = aggregator.fetchPage(
            suggestionQueries = emptyList(),
            usedTags = emptySet(),
            seenMangaUrls = setOf("1:/already-seen"),
            currentSortOrder = SuggestionSortOrder.Popular,
            includeSourceSection = true,
            pageOffset = 1,
        )

        assertTrue(page.suggestions.isNotEmpty())
        assertEquals(setOf("popular"), page.suggestions.map { it.sectionKey }.toSet())
        assertTrue(source.popularCalls > 0)
    }

    @Test
    fun `latest cold start uses popular fallback for sources without latest support`() = runBlocking {
        val source = FakeCatalogueSource(
            id = 2L,
            titlePrefix = "Fallback",
            supportsLatest = false,
        )
        val aggregator = aggregatorWith(source)

        val page = aggregator.fetchPage(
            suggestionQueries = emptyList(),
            usedTags = emptySet(),
            seenMangaUrls = emptySet(),
            currentSortOrder = SuggestionSortOrder.Latest,
            includeSourceSection = true,
            pageOffset = 1,
        )

        assertTrue(page.suggestions.isNotEmpty())
        assertEquals(setOf("latest"), page.suggestions.map { it.sectionKey }.toSet())
        assertTrue(source.popularCalls > 0)
        assertEquals(0, source.latestCalls)
    }

    @Test
    fun `latest cold start falls back to popular when latest is empty`() = runBlocking {
        val source = FakeCatalogueSource(
            id = 3L,
            titlePrefix = "Fallback",
            supportsLatest = true,
            latestIsEmpty = true,
        )
        val aggregator = aggregatorWith(source)

        val page = aggregator.fetchPage(
            suggestionQueries = emptyList(),
            usedTags = emptySet(),
            seenMangaUrls = emptySet(),
            currentSortOrder = SuggestionSortOrder.Latest,
            includeSourceSection = true,
            pageOffset = 1,
        )

        assertTrue(page.suggestions.isNotEmpty())
        assertEquals(setOf("latest"), page.suggestions.map { it.sectionKey }.toSet())
        assertTrue(source.latestCalls > 0)
        assertTrue(source.popularCalls > 0)
    }

    @Test
    fun `initial non cold page fetches source section before tag sections`() = runBlocking {
        val source = FakeCatalogueSource(
            id = 4L,
            titlePrefix = "Popular",
            supportsLatest = true,
            searchTitlePrefix = "Romance",
        )
        val aggregator = aggregatorWith(source)
        val query = SuggestionQuery(
            query = "Romance",
            sectionKey = "tag:romance",
            score = 10.0,
        )

        val firstPage = aggregator.fetchPage(
            suggestionQueries = listOf(query),
            usedTags = emptySet(),
            seenMangaUrls = emptySet(),
            currentSortOrder = SuggestionSortOrder.Popular,
            includeSourceSection = true,
            pageOffset = 1,
        )
        val nextPage = aggregator.fetchPage(
            suggestionQueries = listOf(query),
            usedTags = firstPage.usedTags,
            seenMangaUrls = emptySet(),
            currentSortOrder = SuggestionSortOrder.Popular,
            includeSourceSection = false,
            pageOffset = 1,
        )

        assertEquals(setOf("popular"), firstPage.suggestions.map { it.sectionKey }.toSet())
        assertTrue(firstPage.usedTags.isEmpty())
        assertEquals(setOf("tag:romance"), nextPage.suggestions.map { it.sectionKey }.toSet())
        assertEquals(setOf("romance"), nextPage.usedTags)
    }

    @Test
    fun `non cold pages fetch one tag section at a time`() = runBlocking {
        val source = FakeCatalogueSource(
            id = 5L,
            titlePrefix = "Popular",
            supportsLatest = true,
            searchTitlePrefix = "Search",
        )
        val aggregator = aggregatorWith(source)
        val queries = listOf(
            SuggestionQuery(query = "Romance", sectionKey = "tag:romance", score = 10.0),
            SuggestionQuery(query = "Action", sectionKey = "tag:action", score = 9.0),
        )

        val page = aggregator.fetchPage(
            suggestionQueries = queries,
            usedTags = emptySet(),
            seenMangaUrls = emptySet(),
            currentSortOrder = SuggestionSortOrder.Popular,
            includeSourceSection = false,
            pageOffset = 1,
        )

        assertEquals(1, page.suggestions.map { it.sectionKey }.toSet().size)
        assertEquals(1, page.usedTags.size)
    }

    @Test
    fun `expanded section load more walks the next source batch`() = runBlocking {
        val sources = (1L..12L)
            .map { id ->
                FakeCatalogueSource(
                    id = id,
                    titlePrefix = "Popular",
                    supportsLatest = true,
                    searchTitlePrefix = "Expanded",
                )
            }
            .toTypedArray()
        val aggregator = aggregatorWith(*sources)

        val firstPage = aggregator.fetchExpandedSection(
            query = "Romance",
            sourceOffset = 0,
            sourceLimit = 8,
        )
        val firstSourceIds = sources
            .filter { it.searchCalls > 0 }
            .map { it.id }
            .toSet()
        val secondPage = aggregator.fetchExpandedSection(
            query = "Romance",
            sourceOffset = firstPage.nextSourceOffset,
            sourceLimit = 8,
        )
        val secondSourceIds = sources
            .filter { it.searchCalls > 0 }
            .map { it.id }
            .toSet() - firstSourceIds

        assertEquals(8, firstSourceIds.size)
        assertEquals(4, secondSourceIds.size)
        assertEquals((1L..12L).toSet(), firstSourceIds + secondSourceIds)
        assertEquals(8, firstPage.nextSourceOffset)
        assertEquals(16, secondPage.nextSourceOffset)
    }

    @Test
    fun `expanded section skips dry source batches before showing empty results`() = runBlocking {
        val drySources = (1L..8L)
            .map { id ->
                FakeCatalogueSource(
                    id = id,
                    titlePrefix = "Popular",
                    supportsLatest = true,
                    searchTitlePrefix = null,
                )
            }
        val productiveSources = (9L..12L)
            .map { id ->
                FakeCatalogueSource(
                    id = id,
                    titlePrefix = "Popular",
                    supportsLatest = true,
                    searchTitlePrefix = "Expanded",
                )
            }
        val sources = (drySources + productiveSources).toTypedArray()
        val aggregator = aggregatorWith(*sources)

        val page = aggregator.fetchExpandedSection(
            query = "Romance",
            sourceOffset = 0,
            sourceLimit = 8,
        )

        assertTrue(page.suggestions.isNotEmpty())
        assertEquals(setOf(9L, 10L, 11L, 12L), page.suggestions.map { it.source }.toSet())
        assertEquals(16, page.nextSourceOffset)
        assertEquals(false, page.hasMoreSources)
    }

    @Test
    fun `tag section sort injection does not retry the same source`() = runBlocking {
        val sort = object : Filter.Sort("Sort", arrayOf("Popular", "Latest Update")) {}
        val source = FakeCatalogueSource(
            id = 13L,
            titlePrefix = "Popular",
            supportsLatest = true,
            searchTitlePrefix = "Sorted",
            filters = FilterList(sort),
        )
        val aggregator = aggregatorWith(source)

        aggregator.fetchPage(
            suggestionQueries = listOf(
                SuggestionQuery(query = "Romance", sectionKey = "tag:romance", score = 10.0),
            ),
            usedTags = emptySet(),
            seenMangaUrls = emptySet(),
            currentSortOrder = SuggestionSortOrder.Latest,
            includeSourceSection = false,
            pageOffset = 1,
        )

        assertEquals(1, source.searchCalls)
        assertEquals(Filter.Sort.Selection(1, ascending = false), sort.state)
    }

    private fun aggregatorWith(vararg sources: CatalogueSource): FeedAggregator {
        val mangaRepository = mockk<MangaRepository>()
        coEvery { mangaRepository.getMangaList() } returns emptyList()

        return FeedAggregator(
            sourceManager = mockk(),
            mangaRepository = mangaRepository,
            preferences = suggestionsPreferences(),
            tagCanonicalizer = mockk(relaxed = true),
            tagProfileRepository = FakeTagProfileRepository(),
            catalogueSourcesProvider = { sources.toList() },
        )
    }

    private fun suggestionsPreferences(): PreferencesHelper {
        val preferences = mockk<PreferencesHelper>()
        every { preferences.suggestionsSortOrder() } returns FakePreference(SuggestionSortOrder.Popular)
        every { preferences.suggestionsTagsBlacklist() } returns FakePreference(emptySet())
        every { preferences.enabledLanguages() } returns FakePreference(setOf("all", "en"))
        every { preferences.hiddenSources() } returns FakePreference(emptySet())
        every { preferences.pinnedCatalogues() } returns FakePreference(emptySet())
        every { preferences.recentlyUsedSourceIds() } returns FakePreference(emptySet())
        every { preferences.lastFetchedSuggestionsSourceIds() } returns FakePreference(emptySet())
        return preferences
    }
}

private class FakeCatalogueSource(
    override val id: Long,
    private val titlePrefix: String,
    override val supportsLatest: Boolean,
    private val latestIsEmpty: Boolean = false,
    private val searchTitlePrefix: String? = null,
    private val filters: FilterList = FilterList(),
) : CatalogueSource {
    var popularCalls = 0
        private set
    var latestCalls = 0
        private set
    var searchCalls = 0
        private set

    override val name: String = "Source ${id.toString().padStart(3, '0')}"
    override val lang: String = "en"

    override suspend fun getPopularManga(page: Int): MangasPage {
        popularCalls++
        return MangasPage(mangaPage(page, titlePrefix), hasNextPage = true)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        latestCalls++
        if (latestIsEmpty) return MangasPage(emptyList(), hasNextPage = false)
        return MangasPage(mangaPage(page, "Latest"), hasNextPage = true)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        searchCalls++
        return searchTitlePrefix
            ?.let { MangasPage(mangaPage(page, it), hasNextPage = true) }
            ?: MangasPage(emptyList(), hasNextPage = false)
    }

    override fun getFilterList(): FilterList = filters

    private fun mangaPage(page: Int, prefix: String): List<SManga> =
        (0 until 6).map { index ->
            SManga.create().apply {
                url = "/$id/$page/$index"
                title = "$prefix $id-$page-$index"
                thumbnail_url = null
                initialized = true
            }
        }
}

private class FakePreference<T>(initialValue: T) : Preference<T> {
    private val state = MutableStateFlow(initialValue)

    override fun key(): String = "fake"
    override fun get(): T = state.value
    override fun set(value: T) {
        state.value = value
    }
    override fun isSet(): Boolean = true
    override fun delete() = Unit
    override fun defaultValue(): T = state.value
    override fun changes(): Flow<T> = state
    override fun stateIn(scope: CoroutineScope): StateFlow<T> = state
}
