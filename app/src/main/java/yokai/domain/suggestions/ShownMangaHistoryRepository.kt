package yokai.domain.suggestions

interface ShownMangaHistoryRepository {
    /**
     * Returns all "source:url" keys from the shown history.
     */
    suspend fun getAllKeys(): Set<String>

    /**
     * Returns "source:url" keys for entries shown at or after [cutoffMillis] (epoch millis).
     * Used on startup to seed [seenMangaUrls] with only recent history so the in-memory
     * deduplication set doesn't become too large to find new content.
     */
    suspend fun getKeysShownAfter(cutoffMillis: Long): Set<String>

    /**
     * Inserts or replaces shown entries for the given source/url pairs.
     */
    suspend fun insertAll(entries: List<Pair<Long, String>>)

    /**
     * Deletes entries older than [cutoffMillis] (epoch millis).
     */
    suspend fun deleteOlderThan(cutoffMillis: Long)
}
