package yokai.domain.suggestions

object SuggestionsConfig {
    const val STM_HALF_LIFE_DAYS = 7.0
    const val LTM_ALPHA = 0.1
    const val VELOCITY_WEIGHT = 0.05

    const val STM_WEIGHT = 0.6
    const val LTM_WEIGHT = 0.4

    // ── Batch loading ──────────────────────────────────────────────────────────
    // Fetch one section per batch. Suggestions should load the source/latest section
    // first, then fetch the next planned tag section only when the user scrolls down.
    const val SECTION_BATCH_SIZE = 1
    // Trigger the next section when the user reaches the currently rendered section.
    const val LOAD_MORE_SECTION_THRESHOLD = 1

    // ── Per-section result caps ────────────────────────────────────────────────
    // Normal sections stay compact; expanded "view more" sheets carry deeper lists.
    const val MAX_RESULTS_PER_SECTION = 9
    const val EXPANDED_MAX_RESULTS = 40
    const val EXPANDED_PAGE_SIZE = 20
    const val EXPANDED_SOURCE_BATCH_SIZE = 8
    const val MAX_ACTIVE_SOURCES = Int.MAX_VALUE
    const val MAX_CONCURRENT_SOURCE_REQUESTS = 8
    const val SOURCE_REQUEST_TIMEOUT_MS = 15_000L
    const val SECTION_TIMEOUT_MS = 22_000L
    const val SECTION_FILL_EXTRA_PAGE_LIMIT = 2
    const val MAX_CANDIDATES_PER_SECTION = 500
    const val MAX_PER_SOURCE_FETCH = 6
    const val MANUAL_REFRESH_MAX_PER_SOURCE_FETCH = 2
    const val EXPANDED_MAX_PER_SOURCE_FETCH = 5
    const val COLD_START_HISTORY_THRESHOLD = 12
    const val COLD_START_SOURCE_PAGE_LIMIT = 2
    const val COLD_START_SOURCE_CHUNK_SIZE = 4
    const val COLD_START_MAX_PER_SOURCE_FETCH = 25
    const val COLD_START_EARLY_BAILOUT_CANDIDATES = 300
    const val COLD_START_MAX_CANDIDATES = 700

    const val COLD_START_MAX_RESULTS = 500
    /** Max time to wait for SourceManager to populate before cold-start discovery gives up. */
    const val SOURCE_POPULATION_TIMEOUT_MS = 10_000L
    const val BACKGROUND_MAX_SECTION_BATCHES = 3
    /** Minimum acceptable results per section. If a section yields fewer than this
     *  after all sources + dedup + filters, it is logged as SECTION_THIN and the
     *  seen-log filter is relaxed for a second ranking pass. */
    const val MIN_RESULTS_PER_SECTION = 5
    // ──────────────────────────────────────────────────────────────────────────

    const val HARD_REFRESH_NOVELTY_QUOTA = 0.70
    const val SOFT_REFRESH_NOVELTY_QUOTA = 0.40
    // Stored suggestions are useful for instant startup, but should not stay frozen
    // forever if the app is opened repeatedly without a manual refresh.
    const val AUTO_REFRESH_STALE_AFTER_MS = 30 * 60 * 1000L
    const val DISCOVERY_CACHE_TTL_MS = 30 * 60 * 1000L
    const val TAG_SECTION_CACHE_TTL_MS = 90 * 60 * 1000L
    const val SEEN_LOG_TTL_MS = 24 * 60 * 60 * 1000L
    /** Hard-refresh seen-log retention. Long enough to avoid repeats across a few back-to-back
     *  hard refreshes, short enough that popular titles can resurface within a couple of days. */
    const val SEEN_LOG_HARD_REFRESH_RETENTION_MS = 3 * 24 * 60 * 60 * 1000L

    const val DEBUG_LOG_MAX_ENTRIES = 500

    const val RESULT_VERSION_UNKNOWN = 0
    const val RESULT_VERSION_V1 = 1
    const val RESULT_VERSION_V2 = 2
}
