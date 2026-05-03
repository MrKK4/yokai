package yokai.domain.suggestions

interface TagProfileRepository {
    suspend fun getAllProfiles(): List<TagProfile>
    suspend fun getNonBlacklistedProfiles(): List<TagProfile>
    suspend fun getProfile(canonicalTag: String): TagProfile?
    suspend fun upsertProfile(profile: TagProfile)
    suspend fun upsertProfiles(profiles: List<TagProfile>)
    suspend fun setTagState(canonicalTag: String, state: TagState, now: Long = System.currentTimeMillis())
    suspend fun updateCooldown(canonicalTag: String, cooldownUntil: Long, now: Long = System.currentTimeMillis())

    suspend fun findAlias(rawKey: String, sourceId: Long? = null): TagAlias?
    suspend fun getSearchTerms(canonicalTag: String): List<String>
    suspend fun aliasOrProfileExists(key: String): Boolean
    suspend fun aliasCount(): Long
    suspend fun seedAliases(aliases: List<TagAlias>)

    suspend fun recordVariant(canonicalTag: String, rawTag: String, now: Long = System.currentTimeMillis())
    suspend fun bestDisplayName(canonicalTag: String): String?
}

interface SuggestionSeenLogRepository {
    suspend fun insertSeen(sectionKey: String, mangaKey: String, shownAt: Long, refreshId: Long)
    suspend fun deleteOlderThan(cutoff: Long)
    suspend fun recentKeysForSection(sectionKey: String, cutoff: Long): Set<String>
    suspend fun keysSinceRefresh(minimumRefreshId: Long): Set<String>
    suspend fun shownAt(sectionKey: String, mangaKey: String): Long?
}

interface PlannedSectionRepository {
    suspend fun getPlannedSections(): List<PlannedSection>
    suspend fun replaceAll(sections: List<PlannedSection>)
    suspend fun deleteAll()
}
