package yokai.domain.suggestions

/**
 * Caches suggestion candidates that already passed every filter (library / seen / blacklist /
 * title-dedup) and were ranked, but did NOT make the visible per-section target. On the next
 * refresh these are drained first to fill the section before any network call is made — reusing
 * already-fetched results and cutting source requests.
 *
 * Cached rows are NOT marked shown; they stay eligible until actually displayed, at which point the
 * normal shown-history / seen-log path claims them. Entries expire via [deleteOlderThan].
 */
interface SuggestionCandidateCacheRepository {
    /** Top [limit] cached candidates for a section, highest relevance first. */
    suspend fun getCached(resultVersion: Int, sectionKey: String, limit: Int): List<SuggestedManga>

    /**
     * Replaces a section's cached surplus with [items] (highest-scored [cap] kept). Replace, not
     * append, so the cache for a section never grows beyond the freshest fetch's surplus.
     */
    suspend fun putSection(resultVersion: Int, sectionKey: String, items: List<SuggestedManga>, cap: Int)

    /** Prunes entries cached before [cutoffMillis] (epoch millis). */
    suspend fun deleteOlderThan(cutoffMillis: Long)

    /** Drops all cache rows for a result version (e.g. on V2↔V1 toggle / version reset). */
    suspend fun clearForResultVersion(resultVersion: Int)

    /** Drops the entire cache (e.g. blacklist/pin change — cached rows lack genres to re-filter). */
    suspend fun clearAll()
}
