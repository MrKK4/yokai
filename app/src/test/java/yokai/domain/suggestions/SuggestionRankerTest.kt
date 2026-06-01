package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.mockk
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `ranker caps normal sections at nine results with unique sources first`() = runBlocking {
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
            (1L..20L).forEach { sourceId ->
                repeat(2) { position ->
                    add(candidate(section = section, sourceId = sourceId, sourceIndex = sourceId.toInt() - 1, position = position))
                }
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

        assertEquals(9, ranked.size)
        assertEquals((1L..9L).toList(), ranked.map { it.source })
    }

    @Test
    fun `cold-start discovery takes one manga per source before circling back`() = runBlocking {
        val tagRepository = FakeTagProfileRepository()
        val ranker = SuggestionRanker(
            mangaRepository = mockk<MangaRepository>(relaxed = true),
            tagCanonicalizer = TagCanonicalizer(tagRepository),
            tagProfileRepository = tagRepository,
            debugLog = SuggestionsDebugLog(),
            random = ZeroRandom,
        )

        val section = coldStartSection()
        val candidates = buildList {
            repeat(20) { position ->
                add(candidate(section = section, sourceId = 1L, sourceIndex = 0, position = position))
            }
            (2L..9L).forEach { sourceId ->
                add(candidate(section = section, sourceId = sourceId, sourceIndex = sourceId.toInt() - 1))
            }
        }

        val ranked = ranker.rankWithContext(
            retrievalResults = listOf(CandidateRetrievalResult(section, candidates)),
            context = RankingContext(
                localKeys = emptySet(),
                localTitles = emptySet(),
                profiles = emptyMap(),
                blacklistedTags = emptySet(),
            ),
            globalSeenKeys = emptySet(),
            sectionSeenKeys = emptyMap(),
            sessionContext = SessionContext(),
        )

        assertEquals((1L..9L).toList(), ranked.take(9).map { it.source })
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

        assertEquals(9, ranked.size)
        assertEquals(
            listOf(1L, 2L, 1L, 2L, 1L, 1L, 1L, 1L, 1L),
            ranked.map { it.source },
        )
    }

    @Test
    fun `ranker fills from surplus candidates after library filtering removes top source items`() = runBlocking {
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
            repeat(11) { position ->
                add(candidate(section = section, sourceId = 1L, sourceIndex = 0, position = position))
            }
            (2L..8L).forEach { sourceId ->
                add(candidate(section = section, sourceId = sourceId, sourceIndex = sourceId.toInt() - 1))
            }
        }

        val ranked = ranker.rankWithContext(
            retrievalResults = listOf(CandidateRetrievalResult(section, candidates, sourcePoolSize = 8)),
            context = RankingContext(
                localKeys = setOf(1L to "source-1-0", 1L to "source-1-1"),
                localTitles = emptySet(),
                profiles = mapOf("action" to profile("action", recent = 10.0)),
                blacklistedTags = emptySet(),
            ),
            globalSeenKeys = emptySet(),
            sectionSeenKeys = emptyMap(),
            sessionContext = SessionContext(),
        )

        assertEquals(9, ranked.size)
        assertEquals(2, ranked.count { it.source == 1L })
    }

    @Test
    fun `ranker fills section from single source when no other sources produce results`() = runBlocking {
        val tagRepository = FakeTagProfileRepository()
        val ranker = SuggestionRanker(
            mangaRepository = mockk<MangaRepository>(relaxed = true),
            tagCanonicalizer = TagCanonicalizer(tagRepository),
            tagProfileRepository = tagRepository,
            debugLog = SuggestionsDebugLog(),
            random = ZeroRandom,
        )

        val section = section()
        val candidates = List(12) { position ->
            candidate(section = section, sourceId = 1L, sourceIndex = 0, position = position)
        }

        val ranked = ranker.rankWithContext(
            retrievalResults = listOf(
                CandidateRetrievalResult(
                    section = section,
                    candidates = candidates,
                    sourcePoolSize = SuggestionsConfig.MAIN_FEED_SOURCE_COHORT_SIZE,
                ),
            ),
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

        assertEquals(SuggestionsConfig.MAX_RESULTS_PER_SECTION, ranked.size)
        assertEquals(setOf(1L), ranked.map { it.source }.toSet())
    }

    @Test
    fun `ranker prefers native tag results before text fallback results`() = runBlocking {
        val tagRepository = FakeTagProfileRepository()
        val ranker = SuggestionRanker(
            mangaRepository = mockk<MangaRepository>(relaxed = true),
            tagCanonicalizer = TagCanonicalizer(tagRepository),
            tagProfileRepository = tagRepository,
            debugLog = SuggestionsDebugLog(),
            random = ZeroRandom,
        )

        val section = section()
        val textFallback = (1L..6L).map { sourceId ->
            candidate(section = section, sourceId = sourceId, sourceIndex = sourceId.toInt() - 1, searchTerm = "action")
        }
        val nativeTag = listOf(
            candidate(section = section, sourceId = 10L, sourceIndex = 9, searchTerm = ""),
            candidate(section = section, sourceId = 11L, sourceIndex = 10, searchTerm = ""),
            candidate(section = section, sourceId = 12L, sourceIndex = 11, searchTerm = ""),
        )

        val ranked = ranker.rankWithContext(
            retrievalResults = listOf(CandidateRetrievalResult(section, textFallback + nativeTag)),
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

        assertEquals(listOf(10L, 11L, 12L), ranked.take(3).map { it.source })
    }

    @Test
    fun `rankSectionWithSurplus returns shown plus non-overlapping surplus ordered by score`() = runBlocking {
        val tagRepository = FakeTagProfileRepository()
        val ranker = SuggestionRanker(
            mangaRepository = mockk<MangaRepository>(relaxed = true),
            tagCanonicalizer = TagCanonicalizer(tagRepository),
            tagProfileRepository = tagRepository,
            debugLog = SuggestionsDebugLog(),
            random = ZeroRandom,
        )

        val section = section()
        // 4 sources × 5 candidates = 20 filter-passing candidates; far more than the 9 target.
        val candidates = (1L..4L).flatMap { sourceId ->
            List(5) { position ->
                candidate(section = section, sourceId = sourceId, sourceIndex = sourceId.toInt() - 1, position = position)
            }
        }

        val ranked = ranker.rankSectionWithSurplus(
            result = CandidateRetrievalResult(section, candidates, sourcePoolSize = 4),
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

        assertEquals(SuggestionsConfig.MAX_RESULTS_PER_SECTION, ranked.shown.size)
        // The 11 filter-passed candidates that didn't fit the target are kept as surplus.
        assertEquals(20 - SuggestionsConfig.MAX_RESULTS_PER_SECTION, ranked.surplus.size)
        val shownKeys = ranked.shown.map { it.source to it.url }.toSet()
        assertTrue(ranked.surplus.none { (it.source to it.url) in shownKeys }, "surplus must not overlap shown")
        assertEquals(
            ranked.surplus.sortedByDescending { it.relevanceScore },
            ranked.surplus,
            "surplus must be ordered by relevance score descending",
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

    private fun coldStartSection(): PlannedSection =
        PlannedSection(
            sectionKey = COLD_START_DISCOVERY_SECTION_KEY,
            type = SectionType.DISCOVERY,
            canonicalTag = null,
            displayReason = "Popular from your sources",
            searchTerms = emptyList(),
            sortOrder = SuggestionSortOrder.Popular,
        )

    private fun candidate(
        section: PlannedSection,
        sourceId: Long,
        sourceIndex: Int,
        position: Int = 0,
        searchTerm: String? = "action",
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
            searchTerm = searchTerm,
            sourceIndex = sourceIndex,
            position = position,
        )

    private object ZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }
}
