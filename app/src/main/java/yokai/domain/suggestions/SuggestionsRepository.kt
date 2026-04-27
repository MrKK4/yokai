package yokai.domain.suggestions

import kotlinx.coroutines.flow.Flow

interface SuggestionsRepository {
    fun getSuggestionsAsFlow(): Flow<List<SuggestedManga>>
    suspend fun getSuggestions(): List<SuggestedManga>
    suspend fun insertSuggestions(suggestions: List<SuggestedManga>)
    suspend fun replaceAll(suggestions: List<SuggestedManga>)
    suspend fun deleteAll()
    suspend fun count(): Long
}

data class SuggestedManga(
    val id: Long? = null,
    val source: Long,
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val reason: String,
    val relevanceScore: Double,
    val fetchedAt: Long = System.currentTimeMillis(),
)
