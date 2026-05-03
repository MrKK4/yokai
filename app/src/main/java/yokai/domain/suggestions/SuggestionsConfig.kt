package yokai.domain.suggestions

object SuggestionsConfig {
    const val STM_HALF_LIFE_DAYS = 7.0
    const val LTM_ALPHA = 0.1
    const val VELOCITY_WEIGHT = 0.05

    const val STM_WEIGHT = 0.6
    const val LTM_WEIGHT = 0.4

    const val GUARANTEED_SLOTS = 2
    const val ROTATING_SLOTS_MIN = 3
    const val ROTATING_SLOTS_MAX = 5
    const val COOLDOWN_MULTIPLIER = 2

    const val MAX_CANDIDATES_PER_SECTION = 40
    const val MAX_PER_SOURCE_FETCH = 5

    const val MAX_RESULTS_PER_SECTION = 8
    const val MAX_PER_SOURCE_PER_SECTION = 3
    const val MAX_PER_SOURCE_FEED = 8

    const val HARD_REFRESH_NOVELTY_QUOTA = 0.70
    const val SOFT_REFRESH_NOVELTY_QUOTA = 0.40
    const val DISCOVERY_CACHE_TTL_MS = 30 * 60 * 1000L
    const val TAG_SECTION_CACHE_TTL_MS = 90 * 60 * 1000L
    const val SEEN_LOG_TTL_MS = 24 * 60 * 60 * 1000L
    const val DEFAULT_REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L

    const val DEBUG_LOG_MAX_ENTRIES = 500
}
