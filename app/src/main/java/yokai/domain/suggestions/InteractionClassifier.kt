package yokai.domain.suggestions

import kotlin.math.max

enum class InteractionType {
    DROPPED_EARLY,
    SAMPLED,
    READ,
    COMPLETED,
    FAVORITED,
}

object InteractionClassifier {
    fun classify(
        chaptersRead: Int,
        totalChapters: Int,
        isFavorited: Boolean,
        lastReadAt: Long,
    ): InteractionType {
        if (isFavorited) return InteractionType.FAVORITED

        val ratio = if (totalChapters > 0) chaptersRead.toDouble() / totalChapters else 0.0
        val daysSince = daysSince(lastReadAt)

        return when {
            ratio < 0.10 && daysSince > 7.0 -> InteractionType.DROPPED_EARLY
            chaptersRead == 1 -> InteractionType.SAMPLED
            ratio >= 0.80 -> InteractionType.COMPLETED
            ratio >= 0.30 -> InteractionType.READ
            else -> InteractionType.SAMPLED
        }
    }

    fun baseWeight(type: InteractionType): Double = when (type) {
        InteractionType.DROPPED_EARLY -> -1.0
        InteractionType.SAMPLED -> 1.0
        InteractionType.READ -> 2.0
        InteractionType.COMPLETED -> 3.0
        InteractionType.FAVORITED -> 4.0
    }

    private fun daysSince(timestamp: Long): Double {
        val elapsed = System.currentTimeMillis() - timestamp
        return max(0L, elapsed) / MILLIS_PER_DAY
    }

    private const val MILLIS_PER_DAY = 86_400_000.0
}

data class AffinityTag(
    val name: String,
    val score: Double,
)

data class SuggestionQuery(
    val query: String,
    val reason: String,
    val score: Double,
)
