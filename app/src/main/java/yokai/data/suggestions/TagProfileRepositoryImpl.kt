package yokai.data.suggestions

import yokai.data.DatabaseHandler
import yokai.domain.suggestions.TagAlias
import yokai.domain.suggestions.TagProfile
import yokai.domain.suggestions.TagProfileRepository
import yokai.domain.suggestions.TagState
import yokai.domain.suggestions.toTagState

class TagProfileRepositoryImpl(
    private val handler: DatabaseHandler,
) : TagProfileRepository {

    override suspend fun getAllProfiles(): List<TagProfile> =
        handler.awaitList { tag_profileQueries.findAll(::mapTagProfile) }.filter { it.isValid() }

    override suspend fun getNonBlacklistedProfiles(): List<TagProfile> =
        handler.awaitList { tag_profileQueries.findNonBlacklisted(::mapTagProfile) }.filter { it.isValid() }

    override suspend fun getProfile(canonicalTag: String): TagProfile? =
        handler.awaitOneOrNull { tag_profileQueries.findByCanonicalTag(canonicalTag, ::mapTagProfile) }
            ?.takeIf { it.isValid() }

    override suspend fun upsertProfile(profile: TagProfile) {
        handler.await {
            tag_profileQueries.upsertProfile(
                canonicalTag = profile.canonicalTag,
                displayName = profile.displayName,
                longTermCount = profile.longTermCount,
                recentCount = profile.recentCount,
                velocity = profile.velocity,
                currentWeekCount = profile.currentWeekCount,
                previousWeekCount = profile.previousWeekCount,
                lastSeenAt = profile.lastSeenAt,
                updatedAt = profile.updatedAt,
            )
        }
    }

    override suspend fun upsertProfiles(profiles: List<TagProfile>) {
        if (profiles.isEmpty()) return
        handler.await(inTransaction = true) {
            profiles.forEach { profile ->
                tag_profileQueries.upsertProfile(
                    canonicalTag = profile.canonicalTag,
                    displayName = profile.displayName,
                    longTermCount = profile.longTermCount,
                    recentCount = profile.recentCount,
                    velocity = profile.velocity,
                    currentWeekCount = profile.currentWeekCount,
                    previousWeekCount = profile.previousWeekCount,
                    lastSeenAt = profile.lastSeenAt,
                    updatedAt = profile.updatedAt,
                )
            }
        }
    }

    override suspend fun setTagState(canonicalTag: String, state: TagState, now: Long) {
        // pinned_at column is retained in the schema for backward compatibility but is no
        // longer written — pin functionality is V1-only and reads the preference directly.
        handler.await {
            tag_profileQueries.updateState(
                state = state.name,
                pinnedAt = null,
                updatedAt = now,
                canonicalTag = canonicalTag,
            )
        }
    }

    override suspend fun resetBlacklistedToManaged(now: Long) {
        handler.await {
            tag_profileQueries.resetBlacklistedToManaged(updatedAt = now)
        }
    }

    override suspend fun findAlias(rawKey: String, sourceId: Long?): TagAlias? {
        val sourceKey = sourceId ?: GLOBAL_SOURCE_KEY
        return handler.awaitOneOrNull {
            tag_aliasQueries.findByRawKeyAndSource(rawKey, sourceKey, ::mapTagAlias)
        }
    }

    override suspend fun getSearchTerms(canonicalTag: String): List<String> {
        val terms = handler.awaitList {
            tag_aliasQueries.findRawTagsForCanonical(canonicalTag)
        }
        return (terms + canonicalTag)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    override suspend fun getExactTermForSource(canonicalTag: String, sourceId: Long): String? =
        handler.awaitOneOrNull {
            tag_aliasQueries.findRawTagsBySourceAndCanonical(canonicalTag, sourceId)
        }

    override suspend fun recordSourceVocabulary(rawTag: String, canonicalTag: String, sourceId: Long) {
        val rawKey = rawTag.trim().lowercase()
        if (rawKey.isBlank() || canonicalTag.isBlank()) return
        handler.await {
            tag_aliasQueries.insertOrIgnoreAlias(
                rawTag = rawTag.trim(),
                rawKey = rawKey,
                canonicalTag = canonicalTag,
                sourceId = sourceId,
                sourceKey = sourceId,
            )
        }
    }

    override suspend fun recordSourceVocabularyBatch(entries: List<Triple<String, String, Long>>) {
        if (entries.isEmpty()) return
        handler.await(inTransaction = true) {
            entries.forEach { (rawTag, canonicalTag, sourceId) ->
                val rawKey = rawTag.trim().lowercase()
                if (rawKey.isBlank() || canonicalTag.isBlank()) return@forEach
                tag_aliasQueries.insertOrIgnoreAlias(
                    rawTag = rawTag.trim(),
                    rawKey = rawKey,
                    canonicalTag = canonicalTag,
                    sourceId = sourceId,
                    sourceKey = sourceId,
                )
            }
        }
    }

    override suspend fun aliasOrProfileExists(key: String): Boolean {
        val aliasCount = handler.awaitOne { tag_aliasQueries.aliasExists(key) }
        if (aliasCount > 0) return true
        return handler.awaitOne { tag_profileQueries.profileExists(key) } > 0
    }

    override suspend fun aliasCount(): Long =
        handler.awaitOne { tag_aliasQueries.countAliases() }

    override suspend fun seedAliases(aliases: List<TagAlias>) {
        if (aliases.isEmpty()) return
        handler.await(inTransaction = true) {
            aliases.forEach { alias ->
                tag_aliasQueries.insertAlias(
                    rawTag = alias.rawTag,
                    rawKey = alias.rawKey,
                    canonicalTag = alias.canonicalTag,
                    sourceId = alias.sourceId,
                    sourceKey = alias.sourceKey,
                )
            }
        }
    }

    override suspend fun recordVariant(canonicalTag: String, rawTag: String, now: Long) {
        val displayName = rawTag.trim()
        if (canonicalTag.isBlank() || displayName.isBlank()) return
        handler.await {
            tag_variantQueries.incrementVariant(
                canonicalTag = canonicalTag,
                rawTag = displayName,
                lastSeenAt = now,
            )
        }
    }

    override suspend fun bestDisplayName(canonicalTag: String): String? =
        handler.awaitOneOrNull { tag_variantQueries.findBestDisplayName(canonicalTag) }

    /**
     * SQLDelight-generated callbacks require non-null returns, so the mapper itself never
     * fails — instead we coerce a missing display_name to canonical_tag and post-filter
     * blank canonical_tag via [isValid] after the list returns. This way one malformed row
     * (e.g. introduced by a buggy raw migration) doesn't fail buildProfile and leave the
     * user with an empty feed forever.
     */
    private fun mapTagProfile(
        canonicalTag: String,
        displayName: String,
        longTermCount: Double,
        recentCount: Double,
        velocity: Double,
        currentWeekCount: Double,
        previousWeekCount: Double,
        lastSeenAt: Long,
        state: String,
        pinnedAt: Long?,
        updatedAt: Long,
    ): TagProfile = TagProfile(
        canonicalTag = canonicalTag,
        displayName = displayName.ifBlank { canonicalTag },
        longTermCount = longTermCount,
        recentCount = recentCount,
        velocity = velocity,
        currentWeekCount = currentWeekCount,
        previousWeekCount = previousWeekCount,
        lastSeenAt = lastSeenAt,
        state = state.toTagState(),
        pinnedAt = pinnedAt,
        updatedAt = updatedAt,
    )

    private fun TagProfile.isValid(): Boolean = canonicalTag.isNotBlank()

    private fun mapTagAlias(
        rawTag: String,
        rawKey: String,
        canonicalTag: String,
        sourceId: Long?,
        sourceKey: Long,
    ): TagAlias =
        TagAlias(
            rawTag = rawTag,
            rawKey = rawKey,
            canonicalTag = canonicalTag,
            sourceId = sourceId,
            sourceKey = sourceKey,
        )

    private companion object {
        private const val GLOBAL_SOURCE_KEY = -1L
    }
}
