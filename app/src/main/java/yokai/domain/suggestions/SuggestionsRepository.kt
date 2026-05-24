package yokai.domain.suggestions

import kotlinx.coroutines.flow.Flow

enum class SuggestionSortOrder {
    Popular,
    Latest,
}

data class SuggestionFeedPage(
    val suggestions: List<SuggestedManga>,
    val usedTags: Set<String>,
    val hasReachedEnd: Boolean,
)

interface SuggestionsRepository {
    fun getSuggestionsAsFlow(resultVersion: Int? = null): Flow<List<SuggestedManga>>
    suspend fun getSuggestions(resultVersion: Int? = null): List<SuggestedManga>
    suspend fun insertSuggestions(
        suggestions: List<SuggestedManga>,
        resultVersion: Int? = null,
        refreshSessionId: Long? = null,
    )
    suspend fun replaceAll(
        suggestions: List<SuggestedManga>,
        resultVersion: Int? = null,
        refreshSessionId: Long? = null,
    )
    suspend fun replaceSection(
        sectionKey: String,
        suggestions: List<SuggestedManga>,
        resultVersion: Int? = null,
        refreshSessionId: Long? = null,
    )
    suspend fun deleteAll()
    suspend fun deleteByResultVersion(resultVersion: Int)
    suspend fun deleteBySectionKey(sectionKey: String, resultVersion: Int? = null)
    /** Deletes rows whose section_key is no longer present in `suggestion_planned_section`. V2-only. */
    suspend fun deleteOrphanedByPlan(resultVersion: Int)
    suspend fun count(resultVersion: Int? = null): Long
}

data class SuggestedManga(
    val id: Long? = null,
    val source: Long,
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val sectionKey: String,
    val relevanceScore: Double,
    val displayRank: Long = 0L,
    val fetchedAt: Long = System.currentTimeMillis(),
    val resultVersion: Int = SuggestionsConfig.RESULT_VERSION_UNKNOWN,
    val refreshSessionId: Long = 0L,
)
