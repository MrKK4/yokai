package yokai.domain.suggestions

import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.source.browse.filter.SavedSearchRepository
import yokai.domain.source.browse.filter.models.RawSavedSearch

class GetUserSuggestionQueriesUseCaseTest {

    @Test
    fun `tag section key uses canonical tag not display name`() = runBlocking {
        val affinityTags = mockk<GetUserAffinityTagsUseCase>()
        coEvery { affinityTags.execute() } returns listOf(
            AffinityTag(
                name = "Love Stories",
                canonicalTag = "romance",
                score = 10.0,
            ),
        )
        val useCase = GetUserSuggestionQueriesUseCase(
            getAffinityTags = affinityTags,
            savedSearchRepository = EmptySavedSearchRepository,
            preferences = stubPreferences(pinnedTags = emptySet()),
            canonicalizer = stubCanonicalizer(),
        )

        val queries = useCase.execute()

        assertEquals("romance", queries.single().query)
        assertEquals("tag:romance", queries.single().sectionKey)
    }

    @Test
    fun `pinned tags rank above affinity tags and saved searches`() = runBlocking {
        val affinityTags = mockk<GetUserAffinityTagsUseCase>()
        coEvery { affinityTags.execute() } returns listOf(
            AffinityTag(name = "Romance", canonicalTag = "romance", score = 10.0),
            AffinityTag(name = "Drama", canonicalTag = "drama", score = 3.0),
        )
        val useCase = GetUserSuggestionQueriesUseCase(
            getAffinityTags = affinityTags,
            savedSearchRepository = EmptySavedSearchRepository,
            preferences = stubPreferences(pinnedTags = setOf("Mecha", "Romance")),
            canonicalizer = stubCanonicalizer(),
        )

        val queries = useCase.execute()

        // Pin order in output is determined by sort-by-score-desc (all pins share PINNED_SCORE,
        // so original sequence is stable — set iteration order = insertion order).
        val top = queries.take(2).map { it.query }.toSet()
        assertEquals(setOf("mecha", "romance"), top)
        // Romance must surface under the pinned section, not the affinity tag section.
        assertTrue(queries.any { it.sectionKey == "pinned:romance" })
        assertTrue(queries.none { it.sectionKey == "tag:romance" })
        // Non-pinned affinity tag still present.
        assertTrue(queries.any { it.sectionKey == "tag:drama" })
    }

    @Test
    fun `blank or whitespace pinned tags are dropped`() = runBlocking {
        val affinityTags = mockk<GetUserAffinityTagsUseCase>()
        coEvery { affinityTags.execute() } returns emptyList()
        val useCase = GetUserSuggestionQueriesUseCase(
            getAffinityTags = affinityTags,
            savedSearchRepository = EmptySavedSearchRepository,
            preferences = stubPreferences(pinnedTags = setOf("", "   ", "Action")),
            canonicalizer = stubCanonicalizer(),
        )

        val queries = useCase.execute()

        assertEquals(listOf("pinned:action"), queries.map { it.sectionKey })
    }

    private fun stubPreferences(pinnedTags: Set<String>): PreferencesHelper {
        val preferences = mockk<PreferencesHelper>()
        every { preferences.suggestionsPinnedTags() } returns StubPreference(pinnedTags)
        return preferences
    }

    /** Lowercases + trims; no DB lookup required for these tests. */
    private fun stubCanonicalizer(): TagCanonicalizer {
        val canonicalizer = mockk<TagCanonicalizer>()
        coEvery { canonicalizer.canonicalize(any(), any()) } answers {
            val raw = firstArg<String>().trim()
            CanonicalTag(
                canonicalKey = raw.lowercase(),
                displayName = raw,
            )
        }
        return canonicalizer
    }
}

private object EmptySavedSearchRepository : SavedSearchRepository {
    override suspend fun findAll(): List<RawSavedSearch> = emptyList()
    override fun subscribeAllBySourceId(sourceId: Long): Flow<List<RawSavedSearch>> = emptyFlow()
    override suspend fun findAllBySourceId(sourceId: Long): List<RawSavedSearch> = emptyList()
    override suspend fun findOneBySourceIdAndName(sourceId: Long, name: String): RawSavedSearch? = null
    override suspend fun findById(id: Long): RawSavedSearch? = null
    override suspend fun deleteById(id: Long) = Unit
    override suspend fun insert(sourceId: Long, name: String, query: String?, filtersJson: String?): Long? = null
}

private class StubPreference<T>(initialValue: T) : Preference<T> {
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
