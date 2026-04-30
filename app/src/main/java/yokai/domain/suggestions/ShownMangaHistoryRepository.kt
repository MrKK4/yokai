package yokai.domain.suggestions

interface ShownMangaHistoryRepository {
    /**
     * Returns all "source:url" keys from the shown history.
     */
    suspend fun getAllKeys(): Set<String>

    /**
     * Inserts or replaces shown entries for the given source/url pairs.
     */
    suspend fun insertAll(entries: List<Pair<Long, String>>)

    /**
     * Deletes entries older than [cutoffMillis] (epoch millis).
     */
    suspend fun deleteOlderThan(cutoffMillis: Long)
}
