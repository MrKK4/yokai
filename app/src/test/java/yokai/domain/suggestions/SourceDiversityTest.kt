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

    private data class Candidate(
        val sourceId: Long,
        val sourceIndex: Int,
        val position: Int,
    )
}
