package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.mockk
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import yokai.domain.manga.MangaRepository

class SuggestionRankerTest {

    @Test
    fun `ranker takes one manga per source before circling back`() = runBlocking {
        val tagRepository = FakeTagProfileRepository()
        val ranker = SuggestionRanker(
            mangaRepository = mockk<MangaRepository>(relaxed = true),
            tagCanonicalizer = TagCanonicalizer(tagRepository),
            tagProfileRepository = tagRepository,
            debugLog = SuggestionsDebugLog(),
            random = ZeroRandom,
        )

        val section = section()
        val candidates = buildList {
            repeat(SuggestionsConfig.MAX_RESULTS_PER_SECTION) { position ->
                add(candidate(section = section, sourceId = 1L, sourceIndex = 0, position = position))
            }
            (2L..SuggestionsConfig.MAX_RESULTS_PER_SECTION.toLong()).forEach { sourceId ->
                add(candidate(section = section, sourceId = sourceId, sourceIndex = sourceId.toInt() - 1))
            }
        }

        val ranked = ranker.rankWithContext(
            retrievalResults = listOf(CandidateRetrievalResult(section, candidates)),
            context = RankingContext(
                localKeys = emptySet(),
                localTitles = emptySet(),
                profiles = mapOf("action" to profile("action", recent = 10.0)),
                blacklistedTags = emptySet(),
            ),
            globalSeenKeys = emptySet(),
            sectionSeenKeys = emptyMap(),
            sessionContext = SessionContext(),
        )

        assertEquals(
            (1L..SuggestionsConfig.MAX_RESULTS_PER_SECTION.toLong()).toList(),
            ranked.map { it.source },
        )
    }

    @Test
    fun `ranker circles back to productive sources when other sources are thin`() = runBlocking {
        val tagRepository = FakeTagProfileRepository()
        val ranker = SuggestionRanker(
            mangaRepository = mockk<MangaRepository>(relaxed = true),
            tagCanonicalizer = TagCanonicalizer(tagRepository),
            tagProfileRepository = tagRepository,
            debugLog = SuggestionsDebugLog(),
            random = ZeroRandom,
        )

        val section = section()
        val candidates = buildList {
            repeat(10) { position ->
                add(candidate(section = section, sourceId = 1L, sourceIndex = 0, position = position))
            }
            repeat(2) { position ->
                add(candidate(section = section, sourceId = 2L, sourceIndex = 1, position = position))
            }
        }

        val ranked = ranker.rankWithContext(
            retrievalResults = listOf(CandidateRetrievalResult(section, candidates)),
            context = RankingContext(
                localKeys = emptySet(),
                localTitles = emptySet(),
                profiles = mapOf("action" to profile("action", recent = 10.0)),
                blacklistedTags = emptySet(),
            ),
            globalSeenKeys = emptySet(),
            sectionSeenKeys = emptyMap(),
            sessionContext = SessionContext(),
        )

        assertEquals(
            listOf(1L, 2L, 1L, 2L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L),
            ranked.map { it.source },
        )
    }

    private fun section(): PlannedSection =
        PlannedSection(
            sectionKey = "tag:action",
            type = SectionType.MANAGED_TAG,
            canonicalTag = "action",
            displayReason = "Because you read Action",
            searchTerms = listOf("action"),
            sortOrder = SuggestionSortOrder.Popular,
        )

    private fun candidate(
        section: PlannedSection,
        sourceId: Long,
        sourceIndex: Int,
        position: Int = 0,
    ): SuggestionCandidate =
        SuggestionCandidate(
            section = section,
            sourceId = sourceId,
            manga = SManga.create().apply {
                url = "source-$sourceId-$position"
                title = "Source $sourceId Manga $position"
                genre = "Action"
                initialized = true
            },
            searchTerm = "action",
            sourceIndex = sourceIndex,
            position = position,
        )

    private object ZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }
}
