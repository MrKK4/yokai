package yokai.domain.suggestions

object SuggestionsConfig {
    const val STM_HALF_LIFE_DAYS = 7.0
    const val LTM_ALPHA = 0.1
    const val VELOCITY_WEIGHT = 0.05

    const val STM_WEIGHT = 0.6
    const val LTM_WEIGHT = 0.4

    // ── Batch loading ──────────────────────────────────────────────────────────
    // Fetch 2 sections per batch so the user sees first content in ~2–4s instead
    // of waiting for 5 sections. The next batch is pre-fetched speculatively as
    // soon as the current one finishes, so scrolling rarely stalls.
    const val SECTION_BATCH_SIZE = 2
    // Trigger the fallback "load more" when the user is 1 section from the bottom.
    // This is only a safety net — speculative pre-fetch fires earlier.
    const val LOAD_MORE_SECTION_THRESHOLD = 1

    // ── Per-section result caps ────────────────────────────────────────────────
    // Raised so a section reliably shows ≥10 items (12 max after dedup/filter).
    const val MAX_CANDIDATES_PER_SECTION = 50
    const val MAX_PER_SOURCE_FETCH = 6

    const val MAX_RESULTS_PER_SECTION = 12
    const val MAX_PER_SOURCE_PER_SECTION = 4
    /** Minimum acceptable results per section. If a section yields fewer than this
     *  after all sources + dedup + filters, it is logged as SECTION_THIN and the
     *  seen-log filter is relaxed for a second ranking pass. */
    const val MIN_RESULTS_PER_SECTION = 10
    // ──────────────────────────────────────────────────────────────────────────

    const val HARD_REFRESH_NOVELTY_QUOTA = 0.70
    const val SOFT_REFRESH_NOVELTY_QUOTA = 0.40
    const val DISCOVERY_CACHE_TTL_MS = 30 * 60 * 1000L
    const val TAG_SECTION_CACHE_TTL_MS = 90 * 60 * 1000L
    const val SEEN_LOG_TTL_MS = 24 * 60 * 60 * 1000L

    const val DEBUG_LOG_MAX_ENTRIES = 500
}
