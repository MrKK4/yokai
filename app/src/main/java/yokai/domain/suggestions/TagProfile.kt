package yokai.domain.suggestions

data class TagProfile(
    val canonicalTag: String,
    val displayName: String,
    val longTermCount: Double,
    val recentCount: Double,
    val velocity: Double,
    val currentWeekCount: Double,
    val previousWeekCount: Double,
    val lastSeenAt: Long,
    val state: TagState,
    val pinnedAt: Long?,
    val updatedAt: Long,
) {
    val affinity: Double
        get() = SuggestionsConfig.STM_WEIGHT * recentCount + SuggestionsConfig.LTM_WEIGHT * longTermCount

    val isPinned: Boolean
        get() = state == TagState.PINNED

    val isBlacklisted: Boolean
        get() = state == TagState.BLACKLISTED

    val isManaged: Boolean
        get() = state == TagState.MANAGED
}

data class TagAlias(
    val rawTag: String,
    val rawKey: String,
    val canonicalTag: String,
    val sourceId: Long?,
    val sourceKey: Long,
)
