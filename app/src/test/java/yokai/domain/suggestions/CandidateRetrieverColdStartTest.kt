package yokai.domain.suggestions

import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
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
    fun `sections under display target try one page two top up after filtering`() = runBlocking {
        val source = FakeColdStartSource(id = 6L, titlePrefix = "SeenFiltered", resultCount = 8)
        val retriever = retrieverWith(source)

        val results = retriever.retrieve(
            sections = listOf(normalDiscoverySection()),
            globalSeenKeys = setOf("6:/6/1/0", "6:/6/1/1", "6:/6/1/2"),
        )

        assertEquals(
            listOf("/6/1/3", "/6/1/4", "/6/1/5", "/6/1/6", "/6/1/7", "/6/2/0"),
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

    private fun retrieverWith(
        vararg sources: CatalogueSource,
        lastFetchedSourceIds: Set<String> = emptySet(),
    ): CandidateRetriever {
        return CandidateRetriever(
            sourceManager = mockk(),
            preferences = suggestionsPreferences(lastFetchedSourceIds),
            debugLog = SuggestionsDebugLog(),
            tagCanonicalizer = mockk(relaxed = true),
            tagProfileRepository = FakeTagProfileRepository(),
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
}

private class FakeColdStartSource(
    override val id: Long,
    private val titlePrefix: String,
    private val delayMs: Long = 0L,
    private val resultCount: Int = 3,
) : CatalogueSource {
    override val name: String = "Source ${id.toString().padStart(3, '0')}"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true

    override suspend fun getPopularManga(page: Int): MangasPage {
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

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getPopularManga(page)

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
