package yokai.data.suggestions

import kotlinx.coroutines.flow.Flow
import yokai.data.DatabaseHandler
import yokai.domain.suggestions.SuggestedManga
import yokai.domain.suggestions.SuggestionsRepository

class SuggestionsRepositoryImpl(private val handler: DatabaseHandler) : SuggestionsRepository {
    override fun getSuggestionsAsFlow(resultVersion: Int?): Flow<List<SuggestedManga>> =
        handler.subscribeToList {
            if (resultVersion == null) {
                suggestionsQueries.findAll(::mapSuggestedManga)
            } else {
                suggestionsQueries.findByResultVersion(resultVersion.toLong(), ::mapSuggestedManga)
            }
        }

    override suspend fun getSuggestions(resultVersion: Int?): List<SuggestedManga> =
        handler.awaitList {
            if (resultVersion == null) {
                suggestionsQueries.findAll(::mapSuggestedManga)
            } else {
                suggestionsQueries.findByResultVersion(resultVersion.toLong(), ::mapSuggestedManga)
            }
        }

    override suspend fun insertSuggestions(
        suggestions: List<SuggestedManga>,
        resultVersion: Int?,
        refreshSessionId: Long?,
    ) {
        handler.await(inTransaction = true) {
            suggestions.withStorageMetadata(resultVersion, refreshSessionId).forEach {
                suggestionsQueries.insert(
                    source = it.source,
                    url = it.url,
                    title = it.title,
                    thumbnailUrl = it.thumbnailUrl,
                    sectionKey = it.sectionKey,
                    relevanceScore = it.relevanceScore,
                    displayRank = it.displayRank,
                    fetchedAt = it.fetchedAt,
                    resultVersion = it.resultVersion.toLong(),
                    refreshSessionId = it.refreshSessionId,
                )
            }
        }
    }

    override suspend fun replaceAll(
        suggestions: List<SuggestedManga>,
        resultVersion: Int?,
        refreshSessionId: Long?,
    ) {
        handler.await(inTransaction = true) {
            if (resultVersion == null) {
                suggestionsQueries.deleteAll()
            } else {
                suggestionsQueries.deleteByResultVersion(resultVersion.toLong())
            }
            suggestions.withStorageMetadata(resultVersion, refreshSessionId).forEach {
                suggestionsQueries.insert(
                    source = it.source,
                    url = it.url,
                    title = it.title,
                    thumbnailUrl = it.thumbnailUrl,
                    sectionKey = it.sectionKey,
                    relevanceScore = it.relevanceScore,
                    displayRank = it.displayRank,
                    fetchedAt = it.fetchedAt,
                    resultVersion = it.resultVersion.toLong(),
                    refreshSessionId = it.refreshSessionId,
                )
            }
        }
    }

    override suspend fun replaceSection(
        sectionKey: String,
        suggestions: List<SuggestedManga>,
        resultVersion: Int?,
        refreshSessionId: Long?,
    ) {
        handler.await(inTransaction = true) {
            if (resultVersion == null) {
                suggestionsQueries.deleteBySectionKey(sectionKey)
            } else {
                suggestionsQueries.deleteBySectionKeyAndResultVersion(sectionKey, resultVersion.toLong())
            }
            suggestions.withStorageMetadata(resultVersion, refreshSessionId).forEach {
                suggestionsQueries.insert(
                    source = it.source,
                    url = it.url,
                    title = it.title,
                    thumbnailUrl = it.thumbnailUrl,
                    sectionKey = it.sectionKey,
                    relevanceScore = it.relevanceScore,
                    displayRank = it.displayRank,
                    fetchedAt = it.fetchedAt,
                    resultVersion = it.resultVersion.toLong(),
                    refreshSessionId = it.refreshSessionId,
                )
            }
        }
    }

    override suspend fun deleteAll() {
        handler.await { suggestionsQueries.deleteAll() }
    }

    override suspend fun deleteByResultVersion(resultVersion: Int) {
        handler.await { suggestionsQueries.deleteByResultVersion(resultVersion.toLong()) }
    }

    override suspend fun deleteBySectionKey(sectionKey: String, resultVersion: Int?) {
        handler.await {
            if (resultVersion == null) {
                suggestionsQueries.deleteBySectionKey(sectionKey)
            } else {
                suggestionsQueries.deleteBySectionKeyAndResultVersion(sectionKey, resultVersion.toLong())
            }
        }
    }

    override suspend fun deleteOrphanedByPlan(resultVersion: Int) {
        handler.await { suggestionsQueries.deleteOrphanedByPlan(resultVersion.toLong()) }
    }

    override suspend fun count(resultVersion: Int?): Long =
        handler.awaitOne {
            if (resultVersion == null) {
                suggestionsQueries.count()
            } else {
                suggestionsQueries.countByResultVersion(resultVersion.toLong())
            }
        }

    private fun mapSuggestedManga(
        _id: Long,
        source: Long,
        url: String,
        title: String,
        thumbnailUrl: String?,
        sectionKey: String,
        relevanceScore: Double,
        displayRank: Long,
        fetchedAt: Long,
        resultVersion: Long,
        refreshSessionId: Long,
    ): SuggestedManga = SuggestedManga(
        _id,
        source,
        url,
        title,
        thumbnailUrl,
        sectionKey,
        relevanceScore,
        displayRank,
        fetchedAt,
        resultVersion.toInt(),
        refreshSessionId,
    )

    private fun List<SuggestedManga>.withStorageMetadata(
        resultVersion: Int?,
        refreshSessionId: Long?,
    ): List<SuggestedManga> =
        map { suggestion ->
            suggestion.copy(
                resultVersion = resultVersion ?: suggestion.resultVersion,
                refreshSessionId = refreshSessionId ?: suggestion.refreshSessionId,
            )
        }
}
