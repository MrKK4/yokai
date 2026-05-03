package yokai.data.suggestions

import yokai.data.DatabaseHandler
import yokai.domain.suggestions.ShownMangaHistoryRepository

class ShownMangaHistoryRepositoryImpl(
    private val handler: DatabaseHandler,
) : ShownMangaHistoryRepository {

    override suspend fun getAllKeys(): Set<String> =
        handler.awaitList {
            shown_manga_historyQueries.findAllKeys()
        }.toSet()

    override suspend fun getKeysShownAfter(cutoffMillis: Long): Set<String> =
        handler.awaitList {
            shown_manga_historyQueries.findKeysShownAfter(cutoffMillis)
        }.toSet()

    override suspend fun insertAll(entries: List<Pair<Long, String>>) {
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        handler.await(inTransaction = true) {
            entries.forEach { (source, url) ->
                shown_manga_historyQueries.insertShown(
                    source = source,
                    url = url,
                    shownAt = now,
                )
            }
        }
    }

    override suspend fun deleteOlderThan(cutoffMillis: Long) {
        handler.await {
            shown_manga_historyQueries.deleteOlderThan(cutoff = cutoffMillis)
        }
    }
}
