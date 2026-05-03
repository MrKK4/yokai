package yokai.domain.suggestions

interface TagProfileRepository {
    suspend fun getAllProfiles(): List<TagProfile>
    suspend fun getNonBlacklistedProfiles(): List<TagProfile>
    suspend fun getProfile(canonicalTag: String): TagProfile?
    suspend fun upsertProfile(profile: TagProfile)
    suspend fun upsertProfiles(profiles: List<TagProfile>)
    suspend fun setTagState(canonicalTag: String, state: TagState, now: Long = System.currentTimeMillis())

    suspend fun findAlias(rawKey: String, sourceId: Long? = null): TagAlias?
    suspend fun getSearchTerms(canonicalTag: String): List<String>
    /**
     * Returns the exact raw string that [sourceId] uses for [canonicalTag], or `null` if
     * no source-specific alias has been recorded yet. Used by the text-search fallback
     * so we send the source's own vocabulary instead of a generic canonical key.
     */
    suspend fun getExactTermForSource(canonicalTag: String, sourceId: Long): String?
    /**
     * Persists a (sourceId, rawTag → canonicalTag) alias learned from a live source
     * result so future fetches from the same source can use the exact string the source
     * understands. No-op if the mapping already exists.
     */
    suspend fun recordSourceVocabulary(rawTag: String, canonicalTag: String, sourceId: Long)
    suspend fun aliasOrProfileExists(key: String): Boolean
    suspend fun aliasCount(): Long
    suspend fun seedAliases(aliases: List<TagAlias>)

    suspend fun recordVariant(canonicalTag: String, rawTag: String, now: Long = System.currentTimeMillis())
    suspend fun bestDisplayName(canonicalTag: String): String?
}

interface SuggestionSeenLogRepository {
    suspend fun insertSeen(sectionKey: String, mangaKey: String, shownAt: Long, refreshId: Long)
    suspend fun insertSeenBatch(entries: List<SeenEntry>)
    suspend fun deleteOlderThan(cutoff: Long)
    suspend fun recentKeysForSection(sectionKey: String, cutoff: Long): Set<String>
    suspend fun recentKeysForSections(sectionKeys: Collection<String>, cutoff: Long): Map<String, Set<String>>
    suspend fun keysSinceRefresh(minimumRefreshId: Long): Set<String>
    suspend fun shownAt(sectionKey: String, mangaKey: String): Long?
}

data class SeenEntry(
    val sectionKey: String,
    val mangaKey: String,
    val shownAt: Long,
    val refreshId: Long,
)

interface PlannedSectionRepository {
    suspend fun getPlannedSections(): List<PlannedSection>
    suspend fun replaceAll(sections: List<PlannedSection>)
    suspend fun deleteAll()
}
