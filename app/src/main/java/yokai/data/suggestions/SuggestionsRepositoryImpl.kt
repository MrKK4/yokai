package yokai.data.suggestions

import kotlinx.coroutines.flow.Flow
import yokai.data.DatabaseHandler
import yokai.domain.suggestions.SuggestedManga
import yokai.domain.suggestions.SuggestionsRepository

class SuggestionsRepositoryImpl(private val handler: DatabaseHandler) : SuggestionsRepository {
    override fun getSuggestionsAsFlow(): Flow<List<SuggestedManga>> =
        handler.subscribeToList { suggestionsQueries.findAll(::mapSuggestedManga) }

    override suspend fun getSuggestions(): List<SuggestedManga> =
        handler.awaitList { suggestionsQueries.findAll(::mapSuggestedManga) }

    override suspend fun insertSuggestions(suggestions: List<SuggestedManga>) {
        handler.await(inTransaction = true) {
            suggestions.forEach {
                suggestionsQueries.insert(
                    source = it.source,
                    url = it.url,
                    title = it.title,
                    thumbnailUrl = it.thumbnailUrl,
                    reason = it.reason,
                    relevanceScore = it.relevanceScore,
                    displayRank = it.displayRank,
                    fetchedAt = it.fetchedAt,
                )
            }
        }
    }

    override suspend fun replaceAll(suggestions: List<SuggestedManga>) {
        handler.await(inTransaction = true) {
            suggestionsQueries.deleteAll()
            suggestions.forEach {
                suggestionsQueries.insert(
                    source = it.source,
                    url = it.url,
                    title = it.title,
                    thumbnailUrl = it.thumbnailUrl,
                    reason = it.reason,
                    relevanceScore = it.relevanceScore,
                    displayRank = it.displayRank,
                    fetchedAt = it.fetchedAt,
                )
            }
        }
    }

    override suspend fun deleteAll() {
        handler.await { suggestionsQueries.deleteAll() }
    }

    override suspend fun deleteByReason(reason: String) {
        handler.await { suggestionsQueries.deleteByReason(reason) }
    }

    override suspend fun count(): Long =
        handler.awaitOne { suggestionsQueries.count() }

    private fun mapSuggestedManga(
        _id: Long,
        source: Long,
        url: String,
        title: String,
        thumbnailUrl: String?,
        reason: String,
        relevanceScore: Double,
        displayRank: Long,
        fetchedAt: Long,
    ): SuggestedManga = SuggestedManga(_id, source, url, title, thumbnailUrl, reason, relevanceScore, displayRank, fetchedAt)
}
