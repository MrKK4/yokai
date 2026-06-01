package yokai.data.suggestions

import yokai.data.DatabaseHandler
import yokai.domain.suggestions.SuggestedManga
import yokai.domain.suggestions.SuggestionCandidateCacheRepository

class SuggestionCandidateCacheRepositoryImpl(
    private val handler: DatabaseHandler,
) : SuggestionCandidateCacheRepository {

    override suspend fun getCached(resultVersion: Int, sectionKey: String, limit: Int): List<SuggestedManga> {
        if (limit <= 0) return emptyList()
        return handler.awaitList {
            suggestion_candidate_cacheQueries.findBySection(
                resultVersion = resultVersion.toLong(),
                sectionKey = sectionKey,
                limit = limit.toLong(),
                mapper = ::mapCachedCandidate,
            )
        }
    }

    override suspend fun putSection(
        resultVersion: Int,
        sectionKey: String,
        items: List<SuggestedManga>,
        cap: Int,
    ) {
        val capped = items
            .sortedByDescending { it.relevanceScore }
            .take(cap.coerceAtLeast(0))
        val now = System.currentTimeMillis()
        handler.await(inTransaction = true) {
            suggestion_candidate_cacheQueries.deleteBySection(resultVersion.toLong(), sectionKey)
            capped.forEach { item ->
                suggestion_candidate_cacheQueries.insertCandidate(
                    resultVersion = resultVersion.toLong(),
                    sectionKey = sectionKey,
                    source = item.source,
                    url = item.url,
                    title = item.title,
                    thumbnailUrl = item.thumbnailUrl,
                    relevanceScore = item.relevanceScore,
                    cachedAt = now,
                )
            }
        }
    }

    override suspend fun deleteOlderThan(cutoffMillis: Long) {
        handler.await {
            suggestion_candidate_cacheQueries.deleteOlderThan(cutoffMillis)
        }
    }

    override suspend fun clearForResultVersion(resultVersion: Int) {
        handler.await {
            suggestion_candidate_cacheQueries.deleteByResultVersion(resultVersion.toLong())
        }
    }

    override suspend fun clearAll() {
        handler.await {
            suggestion_candidate_cacheQueries.deleteAll()
        }
    }

    private fun mapCachedCandidate(
        resultVersion: Long,
        sectionKey: String,
        source: Long,
        url: String,
        title: String,
        thumbnailUrl: String?,
        relevanceScore: Double,
        cachedAt: Long,
    ): SuggestedManga = SuggestedManga(
        source = source,
        url = url,
        title = title,
        thumbnailUrl = thumbnailUrl,
        sectionKey = sectionKey,
        relevanceScore = relevanceScore,
        fetchedAt = cachedAt,
        resultVersion = resultVersion.toInt(),
    )
}
