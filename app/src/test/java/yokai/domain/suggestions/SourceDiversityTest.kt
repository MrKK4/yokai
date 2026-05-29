package yokai.domain.suggestions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceDiversityTest {

    @Test
    fun `expanded sections return forty results by circling through productive sources`() {
        val candidates = (1L..20L).flatMap { sourceId ->
            List(5) { position ->
                Candidate(sourceId = sourceId, sourceIndex = sourceId.toInt() - 1, position = position)
            }
        }

        val selected = SourceDiversity.roundRobinBySource(
            items = candidates,
            maxResults = SuggestionsConfig.EXPANDED_MAX_RESULTS,
            sourceId = { it.sourceId },
            sourceIndex = { it.sourceIndex },
            score = { -it.position.toDouble() },
        )

        assertEquals(40, selected.size)
        assertEquals((1L..20L).toList(), selected.take(20).map { it.sourceId })
        assertEquals((1L..20L).toList(), selected.drop(20).map { it.sourceId })
    }

    @Test
    fun `expanded first page returns twenty results from source batch`() {
        val candidates = (1L..8L).flatMap { sourceId ->
            List(5) { position ->
                Candidate(sourceId = sourceId, sourceIndex = sourceId.toInt() - 1, position = position)
            }
        }

        val selected = SourceDiversity.roundRobinBySource(
            items = candidates,
            maxResults = SuggestionsConfig.EXPANDED_PAGE_SIZE,
            sourceId = { it.sourceId },
            sourceIndex = { it.sourceIndex },
            score = { -it.position.toDouble() },
        )

        assertEquals(20, selected.size)
        assertEquals((1L..8L).toList(), selected.take(8).map { it.sourceId })
    }

    @Test
    fun `soft cap fills empty slots from productive sources`() {
        val candidates = buildList {
            repeat(8) { position ->
                add(Candidate(sourceId = 1L, sourceIndex = 0, position = position))
            }
            repeat(3) { position ->
                add(Candidate(sourceId = 2L, sourceIndex = 1, position = position))
            }
            add(Candidate(sourceId = 3L, sourceIndex = 2, position = 0))
            add(Candidate(sourceId = 4L, sourceIndex = 3, position = 0))
        }

        val selected = SourceDiversity.roundRobinBySource(
            items = candidates,
            maxResults = SuggestionsConfig.MAX_RESULTS_PER_SECTION,
            maxPerSource = SuggestionsConfig.MAIN_FEED_MAX_RESULTS_PER_SOURCE,
            sourceId = { it.sourceId },
            sourceIndex = { it.sourceIndex },
            score = { -it.position.toDouble() },
        )

        assertEquals(9, selected.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 1L, 2L, 1L, 2L, 1L), selected.map { it.sourceId })
    }

    @Test
    fun `soft cap does not fill all slots from a single surviving source`() {
        val candidates = List(12) { position ->
            Candidate(sourceId = 1L, sourceIndex = 0, position = position)
        }

        val selected = SourceDiversity.roundRobinBySource(
            items = candidates,
            maxResults = SuggestionsConfig.MAX_RESULTS_PER_SECTION,
            maxPerSource = SuggestionsConfig.MAIN_FEED_MAX_RESULTS_PER_SOURCE,
            sourceId = { it.sourceId },
            sourceIndex = { it.sourceIndex },
            score = { -it.position.toDouble() },
        )

        assertEquals(SuggestionsConfig.MAIN_FEED_MAX_RESULTS_PER_SOURCE, selected.size)
    }

    private data class Candidate(
        val sourceId: Long,
        val sourceIndex: Int,
        val position: Int,
    )
}
