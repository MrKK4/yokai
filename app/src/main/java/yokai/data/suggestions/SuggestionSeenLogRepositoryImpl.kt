package yokai.data.suggestions

import yokai.data.DatabaseHandler
import yokai.domain.suggestions.SuggestionSeenLogRepository

class SuggestionSeenLogRepositoryImpl(
    private val handler: DatabaseHandler,
) : SuggestionSeenLogRepository {

    override suspend fun insertSeen(sectionKey: String, mangaKey: String, shownAt: Long, refreshId: Long) {
        handler.await {
            suggestion_seen_logQueries.insertSeen(
                sectionKey = sectionKey,
                mangaKey = mangaKey,
                shownAt = shownAt,
                refreshId = refreshId,
            )
        }
    }

    override suspend fun deleteOlderThan(cutoff: Long) {
        handler.await { suggestion_seen_logQueries.deleteOlderThan(cutoff) }
    }

    override suspend fun recentKeysForSection(sectionKey: String, cutoff: Long): Set<String> =
        handler.awaitList { suggestion_seen_logQueries.findRecentKeysForSection(sectionKey, cutoff) }.toSet()

    override suspend fun keysSinceRefresh(minimumRefreshId: Long): Set<String> =
        handler.awaitList { suggestion_seen_logQueries.findKeysSinceRefresh(minimumRefreshId) }.toSet()

    override suspend fun shownAt(sectionKey: String, mangaKey: String): Long? =
        handler.awaitOneOrNull { suggestion_seen_logQueries.findShownAt(sectionKey, mangaKey) }
}
