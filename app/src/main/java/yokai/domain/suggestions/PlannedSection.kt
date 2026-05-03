package yokai.domain.suggestions

enum class SectionType {
    DISCOVERY,
    PINNED_TAG,
    MANAGED_TAG,
}

data class PlannedSection(
    val sectionKey: String,
    val type: SectionType,
    val canonicalTag: String?,
    val displayReason: String,
    val searchTerms: List<String>,
    val sortOrder: SuggestionSortOrder,
    val sortFallback: Boolean = false,
    val rank: Long = 0L,
    val plannedAt: Long = System.currentTimeMillis(),
)
