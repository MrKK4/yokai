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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `manual refresh tries fresh sources before recently displayed sources`() = runBlocking {
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
        )

        assertEquals((9L..16L).toSet(), results.single().candidates.map { it.sourceId }.toSet())
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
