package yokai.domain.suggestions

import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.every
import io.mockk.mockk
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

class CandidateRetrieverColdStartTest {

    @Test
    fun `cold-start discovery emits fast source batches before slow sources finish`() = runBlocking {
        val fastSource = FakeColdStartSource(id = 1L, titlePrefix = "Fast")
        val slowSource = FakeColdStartSource(id = 2L, titlePrefix = "Slow", delayMs = 200L)
        val retriever = retrieverWith(fastSource, slowSource)
        val emitted = mutableListOf<CandidateRetrievalResult>()

        retriever.retrieveProgressively(
            sections = listOf(coldStartSection()),
            onResult = { result -> emitted.add(result) },
        )

        assertFalse(emitted.first().isSectionComplete)
        assertEquals(setOf(1L), emitted.first().candidates.map { it.sourceId }.toSet())
        assertEquals(COLD_START_DISCOVERY_SECTION_KEY, emitted.first().section.sectionKey)
    }

    @Test
    fun `keeps a full page of usable results instead of discarding a productive source's extras below target`() = runBlocking {
        // Regression guard for thin sections: a source returns 8 UNSEEN results on page 1,
        // but page 2 is entirely already-seen, so the deeper-page backstop can add nothing.
        // The retriever must surface all 8 page-1 results the section needs instead of
        // stranding the section below target through an over-tight cap/backfill change.
        val source = FakeColdStartSource(id = 30L, titlePrefix = "Rich", resultCount = 8)
        val pageTwoSeen = (0 until 8).map { "30:/30/2/$it" }.toSet()
        val retriever = retrieverWith(source)

        val results = retriever.retrieve(
            sections = listOf(normalDiscoverySection()),
            globalSeenKeys = pageTwoSeen,
        )

        assertEquals(8, results.single().candidates.size)
    }

    @Test
    fun `retrieval keeps source headroom above display target for later ranker filtering`() = runBlocking {
        val source = FakeColdStartSource(id = 32L, titlePrefix = "Rich", resultCount = 24)
        val retriever = retrieverWith(source)

        val results = retriever.retrieve(
            sections = listOf(normalDiscoverySection()),
            allowPageBackstop = false,
        )

        assertEquals(24, results.single().candidates.size)
        assertTrue(results.single().candidates.size > SuggestionsConfig.MAX_RESULTS_PER_SECTION)
    }

    @Test
    fun `chronically empty text source is cooled down and stops being queried`() = runBlocking {
        // A text-only source that returns 0 for every query across consecutive section fetches
        // (e.g. a broken HentaiHand text search) gets benched so it stops burning request slots.
        val source = FakeSearchSource(id = 40L, pageOneCount = 0, otherPageCount = 0)
        val retriever = retrieverWith(source)

        repeat(SuggestionsConfig.CHRONIC_EMPTY_TEXT_THRESHOLD) {
            retriever.retrieve(sections = listOf(tagSection()), allowPageBackstop = false)
        }
        val callsWhileActive = source.searchQueries.size

        // Threshold reached → the next fetch must skip this source's text search entirely.
        retriever.retrieve(sections = listOf(tagSection()), allowPageBackstop = false)

        assertEquals(callsWhileActive, source.searchQueries.size)
    }

    @Test
    fun `tag sections query every source instead of early-breaking after the first chunk`() = runBlocking {
        // 9 sources, each returns 3 on a text query → chunk 1 (8 sources) already exceeds the raw
        // early-break threshold. A tag section must still query the 9th source (chunk 2): which
        // sources support a niche tag rotates, and skipping them is the inconsistent-fill bug.
        val sources = (1L..9L)
            .map { id -> FakeSearchSource(id = id, pageOneCount = 3, otherPageCount = 0) }
            .toTypedArray()
        val retriever = retrieverWith(*sources)

        val results = retriever.retrieve(
            sections = listOf(tagSection()),
            allowPageBackstop = false,
        )

        val sourceIds = results.single().candidates.map { it.sourceId }.toSet()
        assertEquals(9, sourceIds.size)
    }

    @Test
    fun `manual section refresh can cap normal source candidates shallowly`() = runBlocking {
        val source = FakeColdStartSource(id = 3L, titlePrefix = "Wide", resultCount = 10)
        val retriever = retrieverWith(source)

        val results = retriever.retrieve(
            sections = listOf(normalDiscoverySection()),
            maxPerSourceFetch = 2,
        )

        assertEquals(2, results.single().candidates.size)
        assertEquals(setOf(3L), results.single().candidates.map { it.sourceId }.toSet())
    }

    @Test
    fun `latest discovery does not silently fall back to popular results`() = runBlocking {
        val source = FakeColdStartSource(
            id = 25L,
            titlePrefix = "Popular",
            resultCount = 6,
            latestResultCount = 0,
        )
        val retriever = retrieverWith(source)

        val results = retriever.retrieve(
            sections = listOf(latestDiscoverySection()),
            pageOffset = 2,
            allowPageBackstop = false,
        )

        assertTrue(results.single().candidates.isEmpty())
        assertEquals(listOf(2, 1), source.latestPages)
        assertEquals(emptyList<Int>(), source.popularPages)
    }

    @Test
    fun `seen candidates are filtered before per source cap`() = runBlocking {
        val source = FakeColdStartSource(id = 4L, titlePrefix = "SeenFiltered", resultCount = 6)
        val retriever = retrieverWith(source)

        val results = retriever.retrieve(
            sections = listOf(normalDiscoverySection()),
            maxPerSourceFetch = 2,
            globalSeenKeys = setOf("4:/4/1/0", "4:/4/1/1"),
        )

        assertEquals(listOf("/4/1/2", "/4/1/3"), results.single().candidates.map { it.manga.url })
    }

    @Test
    fun `seen-only first page does not stop source from page two top up`() = runBlocking {
        val source = FakeColdStartSource(id = 5L, titlePrefix = "SeenFiltered", resultCount = 3)
        val retriever = retrieverWith(source)

        val results = retriever.retrieve(
            sections = listOf(normalDiscoverySection()),
            maxPerSourceFetch = 2,
            globalSeenKeys = setOf("5:/5/1/0", "5:/5/1/1", "5:/5/1/2"),
        )

        assertEquals(listOf("/5/2/0", "/5/2/1"), results.single().candidates.map { it.manga.url })
    }

    @Test
    fun `sections under display target top up across pages keeping ranker headroom`() = runBlocking {
        // Page 1 has 3 seen + 5 unseen; with the per-source cap raised well above the 9 target for
        // ranker headroom, a single productive source keeps every unseen result from page 1 AND the
        // page-2 top-up (5 + 8 = 13) rather than stranding the section. The ranker later trims the
        // visible 9 from this pool, so retaining the surplus is what makes the fill reliable.
        val source = FakeColdStartSource(id = 6L, titlePrefix = "SeenFiltered", resultCount = 8)
        val retriever = retrieverWith(source)

        val results = retriever.retrieve(
            sections = listOf(normalDiscoverySection()),
            globalSeenKeys = setOf("6:/6/1/0", "6:/6/1/1", "6:/6/1/2"),
        )

        assertEquals(
            listOf(
                "/6/1/3", "/6/1/4", "/6/1/5", "/6/1/6", "/6/1/7",
                "/6/2/0", "/6/2/1", "/6/2/2", "/6/2/3", "/6/2/4", "/6/2/5", "/6/2/6", "/6/2/7",
            ),
            results.single().candidates.map { it.manga.url },
        )
    }

    @Test
    fun `manual refresh uses a capped fresh source cohort before recently displayed sources`() = runBlocking {
        val sources = (1L..18L)
            .map { id -> FakeColdStartSource(id = id, titlePrefix = "Source$id", resultCount = 3) }
            .toTypedArray()
        val retriever = retrieverWith(
            *sources,
            lastFetchedSourceIds = (1L..8L).map { it.toString() }.toSet(),
        )

        val results = retriever.retrieve(
            sections = listOf(normalDiscoverySection()),
            maxPerSourceFetch = 2,
            sourceCohortSeed = 1,
        )

        val selectedSourceIds = results.single().candidates.map { it.sourceId }.toSet()
        assertEquals(SuggestionsConfig.MAIN_FEED_SOURCE_COHORT_SIZE, selectedSourceIds.size)
        assertEquals(8, results.single().candidates.size)
        assertTrue(selectedSourceIds.all { it !in 1L..8L })
    }

    @Test
    fun `connection reset interrupts section instead of becoming dry source results`() {
        val retriever = retrieverWith(
            ThrowingColdStartSource(id = 19L, throwable = SocketException("Connection reset")),
        )

        assertThrows<TransientSuggestionNetworkException> {
            runBlocking {
                retriever.retrieve(sections = listOf(normalDiscoverySection()))
            }
        }
    }

    @Test
    fun `search recovery page only asks requested page`() = runBlocking {
        val source = FakeSearchSource(id = 20L, pageOneCount = 3, otherPageCount = 0)
        val retriever = retrieverWith(source)

        retriever.retrieve(
            sections = listOf(tagSection()),
            pageOffset = 2,
            allowPageBackstop = false,
        )

        assertTrue(source.searchPages.isNotEmpty())
        assertTrue(source.searchPages.all { it == 2 }, "pages=${source.searchPages}")
    }

    @Test
    fun `malformed source filter list falls back to plain search`() = runBlocking {
        val source = FakeSearchSource(
            id = 21L,
            pageOneCount = 4,
            otherPageCount = 0,
            filterThrowable = IllegalStateException("Malformed tag JSON"),
        )
        val retriever = retrieverWith(source)

        val results = retriever.retrieve(
            sections = listOf(tagSection()),
            allowPageBackstop = false,
        )

        assertEquals(4, results.single().candidates.size)
        assertEquals(listOf(1), source.searchPages)
    }

    @Test
    fun `dry native tag pages switch source to text fallback page one`() = runBlocking {
        val source = NativeThenTextSearchSource(id = 22L)
        val retriever = retrieverWith(source)

        retriever.retrieve(
            sections = listOf(tagSection()),
            pageOffset = 1,
            allowPageBackstop = false,
        )
        val fallbackResults = retriever.retrieve(
            sections = listOf(tagSection()),
            pageOffset = 2,
            allowPageBackstop = false,
        )

        assertEquals(
            listOf("" to 1, "" to 2, "milf" to 1),
            source.searchCalls,
        )
        assertEquals(4, fallbackResults.single().candidates.size)
    }

    @Test
    fun `dry page memory never skips native page one`() = runBlocking {
        val source = NativeThenTextSearchSource(id = 23L)
        val retriever = retrieverWith(source)

        retriever.retrieve(
            sections = listOf(tagSection()),
            pageOffset = 1,
            allowPageBackstop = false,
        )
        retriever.retrieve(
            sections = listOf(tagSection()),
            pageOffset = 2,
            allowPageBackstop = false,
        )

        val callCountBeforePageOneRetry = source.searchCalls.size
        retriever.retrieve(
            sections = listOf(tagSection()),
            pageOffset = 1,
            allowPageBackstop = false,
        )

        assertEquals("" to 1, source.searchCalls[callCountBeforePageOneRetry])
    }

    @Test
    fun `text fallback does not repeat case-only query variants`() = runBlocking {
        val source = FakeSearchSource(id = 24L, pageOneCount = 0, otherPageCount = 0)
        val retriever = retrieverWith(source)

        retriever.retrieve(
            sections = listOf(
                tagSection(
                    canonicalTag = "solo male",
                    searchTerms = listOf("solo male", "Solo Male", "SOLO MALE"),
                ),
            ),
            allowPageBackstop = false,
        )

        assertEquals(listOf("solo male"), source.searchQueries)
    }

    @Test
    fun `text fallback splits learned comma aliases instead of sending combined query`() = runBlocking {
        val repository = FakeTagProfileRepository().apply {
            recordSourceVocabulary("big breasts,huge breasts", "big breasts", 26L)
        }
        val source = FakeSearchSource(
            id = 26L,
            pageOneCount = 0,
            otherPageCount = 0,
            queryCounts = mapOf(
                "big breasts,huge breasts" to 0,
                "big breasts" to 0,
                "huge breasts" to 3,
            ),
        )
        val retriever = retrieverWith(source, tagProfileRepository = repository)

        val results = retriever.retrieve(
            sections = listOf(
                tagSection(
                    canonicalTag = "big breasts",
                    searchTerms = listOf("big breasts", "huge breasts", "large breasts"),
                ),
            ),
            allowPageBackstop = false,
        )

        assertEquals(listOf("big breasts", "huge breasts"), source.searchQueries)
        assertEquals(3, results.single().candidates.size)
    }

    @Test
    fun `text fallback keeps trying split aliases when first alias has no usable cards`() = runBlocking {
        val repository = FakeTagProfileRepository().apply {
            recordSourceVocabulary("big breasts,huge breasts", "big breasts", 27L)
        }
        val source = FakeSearchSource(
            id = 27L,
            pageOneCount = 0,
            otherPageCount = 0,
            queryCounts = mapOf(
                "big breasts" to 2,
                "huge breasts" to 3,
            ),
        )
        val retriever = retrieverWith(source, tagProfileRepository = repository)

        val results = retriever.retrieve(
            sections = listOf(
                tagSection(
                    canonicalTag = "big breasts",
                    searchTerms = listOf("big breasts", "huge breasts", "large breasts"),
                ),
            ),
            globalSeenKeys = setOf("27:/27/search/big breasts/1/0", "27:/27/search/big breasts/1/1"),
            allowPageBackstop = false,
        )

        assertEquals(listOf("big breasts", "huge breasts"), source.searchQueries)
        assertEquals(3, results.single().candidates.size)
    }

    @Test
    fun `text fallback starts with canonical tag before code-like aliases`() = runBlocking {
        val source = FakeSearchSource(id = 28L, pageOneCount = 0, otherPageCount = 0)
        val retriever = retrieverWith(source)

        retriever.retrieve(
            sections = listOf(
                tagSection(
                    canonicalTag = "sole female",
                    searchTerms = listOf("1girl", "female solo", "sole female"),
                ),
            ),
            allowPageBackstop = false,
        )

        assertEquals(listOf("sole female", "female solo", "1girl"), source.searchQueries)
    }

    @Test
    fun `text fallback uses common learned terms when a source has no vocabulary yet`() = runBlocking {
        val repository = FakeTagProfileRepository().apply {
            recordSourceVocabulary("female solo", "sole female", 99L)
        }
        val source = FakeSearchSource(
            id = 29L,
            pageOneCount = 0,
            otherPageCount = 0,
            queryCounts = mapOf(
                "sole female" to 0,
                "female solo" to 4,
            ),
        )
        val retriever = retrieverWith(source, tagProfileRepository = repository)

        val results = retriever.retrieve(
            sections = listOf(tagSection(canonicalTag = "sole female", searchTerms = listOf("sole female"))),
            allowPageBackstop = false,
        )

        assertEquals(listOf("sole female", "female solo"), source.searchQueries)
        assertEquals(4, results.single().candidates.size)
    }

    @Test
    fun `successful text fallback query is promoted on the next fetch`() = runBlocking {
        val source = FakeSearchSource(
            id = 31L,
            pageOneCount = 0,
            otherPageCount = 0,
            queryCounts = mapOf(
                "sole female" to 0,
                "female solo" to 4,
            ),
        )
        val retriever = retrieverWith(source)
        val section = tagSection(
            canonicalTag = "sole female",
            searchTerms = listOf("1girl", "female solo", "sole female"),
        )

        retriever.retrieve(
            sections = listOf(section),
            allowPageBackstop = false,
        )
        source.searchQueries.clear()

        val results = retriever.retrieve(
            sections = listOf(section),
            allowPageBackstop = false,
        )

        assertEquals(listOf("female solo"), source.searchQueries)
        assertEquals(4, results.single().candidates.size)
    }


    private fun retrieverWith(
        vararg sources: CatalogueSource,
        lastFetchedSourceIds: Set<String> = emptySet(),
        tagProfileRepository: TagProfileRepository = FakeTagProfileRepository(),
    ): CandidateRetriever {
        return CandidateRetriever(
            sourceManager = mockk(),
            preferences = suggestionsPreferences(lastFetchedSourceIds),
            debugLog = SuggestionsDebugLog(),
            tagCanonicalizer = mockk(relaxed = true),
            tagProfileRepository = tagProfileRepository,
            catalogueSourcesProvider = { sources.toList() },
            catalogueSourcesFlowProvider = { MutableStateFlow(sources.toList()) },
        )
    }

    private fun suggestionsPreferences(lastFetchedSourceIds: Set<String> = emptySet()): PreferencesHelper {
        val preferences = mockk<PreferencesHelper>()
        every { preferences.enabledLanguages() } returns CandidateRetrieverPreference(setOf("all", "en"))
        every { preferences.hiddenSources() } returns CandidateRetrieverPreference(emptySet())
        every { preferences.pinnedCatalogues() } returns CandidateRetrieverPreference(emptySet())
        every { preferences.recentlyUsedSourceIds() } returns CandidateRetrieverPreference(emptySet())
        every { preferences.lastFetchedSuggestionsSourceIds() } returns CandidateRetrieverPreference(lastFetchedSourceIds)
        return preferences
    }

    private fun coldStartSection(): PlannedSection =
        PlannedSection(
            sectionKey = COLD_START_DISCOVERY_SECTION_KEY,
            type = SectionType.DISCOVERY,
            canonicalTag = null,
            displayReason = "Popular from your sources",
            searchTerms = emptyList(),
            sortOrder = SuggestionSortOrder.Popular,
        )

    private fun normalDiscoverySection(): PlannedSection =
        PlannedSection(
            sectionKey = "discovery",
            type = SectionType.DISCOVERY,
            canonicalTag = null,
            displayReason = "Popular from your sources",
            searchTerms = emptyList(),
            sortOrder = SuggestionSortOrder.Popular,
        )

    private fun latestDiscoverySection(): PlannedSection =
        normalDiscoverySection().copy(
            displayReason = "Latest from your sources",
            sortOrder = SuggestionSortOrder.Latest,
        )

    private fun tagSection(
        canonicalTag: String = "milf",
        searchTerms: List<String> = listOf(canonicalTag),
    ): PlannedSection =
        PlannedSection(
            sectionKey = "tag:$canonicalTag",
            type = SectionType.MANAGED_TAG,
            canonicalTag = canonicalTag,
            displayReason = canonicalTag,
            searchTerms = searchTerms,
            sortOrder = SuggestionSortOrder.Popular,
        )
}

private class FakeColdStartSource(
    override val id: Long,
    private val titlePrefix: String,
    private val delayMs: Long = 0L,
    private val resultCount: Int = 3,
    private val latestResultCount: Int = resultCount,
) : CatalogueSource {
    val popularPages = mutableListOf<Int>()
    val latestPages = mutableListOf<Int>()

    override val name: String = "Source ${id.toString().padStart(3, '0')}"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true

    override suspend fun getPopularManga(page: Int): MangasPage {
        popularPages += page
        if (delayMs > 0L) delay(delayMs)
        return MangasPage(
            (0 until resultCount).map { index ->
                SManga.create().apply {
                    url = "/$id/$page/$index"
                    title = "$titlePrefix $page-$index"
                    initialized = true
                }
            },
            hasNextPage = true,
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        latestPages += page
        if (delayMs > 0L) delay(delayMs)
        return MangasPage(
            (0 until latestResultCount).map { index ->
                SManga.create().apply {
                    url = "/$id/latest/$page/$index"
                    title = "$titlePrefix Latest $page-$index"
                    initialized = true
                }
            },
            hasNextPage = true,
        )
    }

    override fun getFilterList(): FilterList = FilterList()
}

private class ThrowingColdStartSource(
    override val id: Long,
    private val throwable: Throwable,
) : CatalogueSource {
    override val name: String = "Throwing $id"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true

    override suspend fun getPopularManga(page: Int): MangasPage {
        throw throwable
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getPopularManga(page)

    override fun getFilterList(): FilterList = FilterList()
}

private class FakeSearchSource(
    override val id: Long,
    private val pageOneCount: Int,
    private val otherPageCount: Int,
    private val filterThrowable: Throwable? = null,
    private val queryCounts: Map<String, Int> = emptyMap(),
) : CatalogueSource {
    val searchPages = mutableListOf<Int>()
    val searchQueries = mutableListOf<String>()

    override val name: String = "Search $id"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        searchPages += page
        searchQueries += query
        val count = queryCounts[query] ?: if (page == 1) pageOneCount else otherPageCount
        return MangasPage(
            (0 until count).map { index ->
                SManga.create().apply {
                    url = "/$id/search/$query/$page/$index"
                    title = "Search $page-$index"
                    initialized = true
                }
            },
            hasNextPage = true,
        )
    }

    override fun getFilterList(): FilterList {
        filterThrowable?.let { throw it }
        return FilterList()
    }
}

private class NativeThenTextSearchSource(
    override val id: Long,
) : CatalogueSource {
    val searchCalls = mutableListOf<Pair<String, Int>>()

    override val name: String = "Native Then Text $id"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        searchCalls += query to page
        val count = when {
            query.isBlank() -> 0
            query == "milf" && page == 1 -> 4
            else -> 0
        }
        return MangasPage(
            (0 until count).map { index ->
                SManga.create().apply {
                    url = "/$id/$query/$page/$index"
                    title = "$query $page-$index"
                    initialized = true
                }
            },
            hasNextPage = true,
        )
    }

    override fun getFilterList(): FilterList =
        FilterList(object : Filter.Text("Tags") {})
}

private class CandidateRetrieverPreference<T>(initialValue: T) : Preference<T> {
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
