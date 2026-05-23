package yokai.domain.suggestions

import kotlin.random.Random

object SuggestionProfilePlanner {
    fun precise(profiles: List<TagProfile>): List<TagProfile> =
        profiles
            .filter { it.isManaged && it.affinity > 0.0 }
            .sortedWith(compareByDescending<TagProfile> { it.affinity }.thenBy { it.canonicalTag })

    fun surprise(
        profiles: List<TagProfile>,
        random: Random,
        maxProfiles: Int = MAX_SURPRISE_PROFILES,
    ): List<TagProfile> {
        val ranked = precise(profiles)
        if (ranked.size <= 2) return ranked.shuffled(random)

        val midpoint = (ranked.size + 1) / 2
        val high = ranked.take(midpoint).shuffled(random)
        val low = ranked.drop(midpoint).shuffled(random)

        return buildList {
            var index = 0
            while (index < high.size || index < low.size) {
                high.getOrNull(index)?.let(::add)
                low.getOrNull(index)?.let(::add)
                index++
            }
        }.take(maxProfiles)
    }

    private const val MAX_SURPRISE_PROFILES = 64
}
