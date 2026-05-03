package yokai.data.suggestions

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import yokai.data.DatabaseHandler
import yokai.domain.suggestions.SeenEntry
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

    /** Insert all seen entries in a single DB transaction, replacing N round-trips with one. */
    override suspend fun insertSeenBatch(entries: List<SeenEntry>) {
        if (entries.isEmpty()) return
        handler.await(inTransaction = true) {
            entries.forEach { entry ->
                suggestion_seen_logQueries.insertSeen(
                    sectionKey = entry.sectionKey,
                    mangaKey = entry.mangaKey,
                    shownAt = entry.shownAt,
                    refreshId = entry.refreshId,
                )
            }
        }
    }

    override suspend fun deleteOlderThan(cutoff: Long) {
        handler.await { suggestion_seen_logQueries.deleteOlderThan(cutoff) }
    }

    override suspend fun recentKeysForSection(sectionKey: String, cutoff: Long): Set<String> =
        handler.awaitList { suggestion_seen_logQueries.findRecentKeysForSection(sectionKey, cutoff) }.toSet()

    /** Fetch recent-seen keys for multiple sections in parallel, returning a map keyed by sectionKey. */
    override suspend fun recentKeysForSections(
        sectionKeys: Collection<String>,
        cutoff: Long,
    ): Map<String, Set<String>> = coroutineScope {
        sectionKeys.map { key ->
            async {
                key to handler.awaitList {
                    suggestion_seen_logQueries.findRecentKeysForSection(key, cutoff)
                }.toSet()
            }
        }.associate { it.await() }
    }

    override suspend fun keysSinceRefresh(minimumRefreshId: Long): Set<String> =
        handler.awaitList { suggestion_seen_logQueries.findKeysSinceRefresh(minimumRefreshId) }.toSet()

    override suspend fun shownAt(sectionKey: String, mangaKey: String): Long? =
        handler.awaitOneOrNull { suggestion_seen_logQueries.findShownAt(sectionKey, mangaKey) }
}

