package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Positive-match blacklist verifier for suggestions.
 *
 * Many catalogue search/list results do not include full genres. When a blacklist
 * is active, weak rows are detail-checked, but missing/unverifiable metadata is not
 * treated as a blacklist match. Otherwise one source with sparse metadata can lose
 * its entire contribution even when it did not return blacklisted manga.
 */
class SuggestionMetadataVerifier(
    private val sourceManager: SourceManager,
    private val tagCanonicalizer: TagCanonicalizer,
    private val debugLog: SuggestionsDebugLog,
) {
    private val detailTagCache = ConcurrentHashMap<String, CachedTags>()
    private val requestGate = Semaphore(SuggestionsConfig.MAX_CONCURRENT_SOURCE_REQUESTS)

    suspend fun canonicalizeBlacklist(rawTags: Set<String>): Set<String> =
        rawTags
            .mapNotNull { rawTag ->
                tagCanonicalizer.canonicalizeToLookupKey(rawTag)
                    .takeIf { it.isNotBlank() }
            }
            .toSet()

    suspend fun filterCandidates(
        candidates: List<SuggestionCandidate>,
        blacklistedTags: Set<String>,
    ): List<SuggestionCandidate> =
        filterByBlacklist(
            items = candidates,
            blacklistedTags = blacklistedTags,
            sourceId = { it.sourceId },
            manga = { it.manga },
            context = { "section '${it.section.sectionKey}'" },
        )

    suspend fun filterSuggestions(
        suggestions: List<SuggestedManga>,
        blacklistedTags: Set<String>,
    ): List<SuggestedManga> =
        filterByBlacklist(
            items = suggestions,
            blacklistedTags = blacklistedTags,
            sourceId = { it.source },
            manga = { suggestion ->
                SManga.create().apply {
                    url = suggestion.url
                    title = suggestion.title
                    thumbnail_url = suggestion.thumbnailUrl
                    initialized = true
                }
            },
            context = { "section '${it.sectionKey}'" },
        )

    suspend fun <T> filterByBlacklist(
        items: List<T>,
        blacklistedTags: Set<String>,
        sourceId: (T) -> Long,
        manga: (T) -> SManga,
        context: (T) -> String = { "suggestions" },
    ): List<T> {
        if (items.isEmpty() || blacklistedTags.isEmpty()) return items
        return coroutineScope {
            items.map { item ->
                async {
                    item to isAllowed(
                        sourceId = sourceId(item),
                        manga = manga(item),
                        blacklistedTags = blacklistedTags,
                        context = context(item),
                    )
                }
            }
                .awaitAll()
                .filter { it.second }
                .map { it.first }
        }
    }

    private suspend fun isAllowed(
        sourceId: Long,
        manga: SManga,
        blacklistedTags: Set<String>,
        context: String,
    ): Boolean {
        val mangaKey = mangaKey(sourceId, manga.url)
        val listTags = canonicalTags(manga.getGenres().orEmpty(), sourceId)
        val listMatch = listTags.firstOrNull { it in blacklistedTags }
        if (listMatch != null) {
            debugLog.add(LogType.ITEM_FILTERED, "[$mangaKey] filtered - tag '$listMatch' blacklisted from list metadata")
            return false
        }
        if (hasStrongListMetadata(listTags)) return true

        val cachedTags = detailTagCache[mangaKey]
            ?: fetchDetailTags(sourceId, manga, context).also { detailTagCache[mangaKey] = it }
        val detailMatch = cachedTags.tags.firstOrNull { it in blacklistedTags }
        return when {
            detailMatch != null -> {
                debugLog.add(LogType.ITEM_FILTERED, "[$mangaKey] filtered - tag '$detailMatch' blacklisted from detail metadata")
                false
            }
            cachedTags.tags.isNotEmpty() -> true
            else -> {
                debugLog.add(LogType.SORT_FALLBACK, "[$mangaKey] allowed - missing verifiable tags while blacklist active in $context")
                true
            }
        }
    }

    private suspend fun fetchDetailTags(sourceId: Long, manga: SManga, context: String): CachedTags {
        val source = sourceManager.get(sourceId)
        if (source == null) {
            debugLog.add(LogType.SORT_FALLBACK, "[${mangaKey(sourceId, manga.url)}] allowed - source missing for detail verification in $context")
            return CachedTags(emptySet())
        }
        val details = sourceResult(source, sourceId, manga)
            ?: return CachedTags(emptySet())
        return CachedTags(canonicalTags(details.getGenres().orEmpty(), sourceId))
    }

    private suspend fun sourceResult(source: Source, sourceId: Long, manga: SManga): SManga? =
        try {
            requestGate.withPermit {
                withTimeoutOrNull(SuggestionsConfig.SOURCE_REQUEST_TIMEOUT_MS) {
                    source.getMangaDetails(manga.copy())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            debugLog.add(
                LogType.SORT_FALLBACK,
                "[${mangaKey(sourceId, manga.url)}] allowed - detail verification failed: ${e.javaClass.simpleName}: ${e.message}",
            )
            null
        }

    private suspend fun canonicalTags(rawTags: List<String>, sourceId: Long): Set<String> =
        rawTags
            .mapNotNull { rawTag ->
                tagCanonicalizer.canonicalizeToLookupKey(rawTag, sourceId)
                    .takeIf { it.isNotBlank() }
            }
            .toSet()

    private data class CachedTags(val tags: Set<String>)

    private fun hasStrongListMetadata(tags: Set<String>): Boolean =
        tags.any { it !in WEAK_LIST_METADATA_TAGS }

    private fun mangaKey(sourceId: Long, url: String): String = "$sourceId:$url"

    private companion object {
        private val WEAK_LIST_METADATA_TAGS = setOf(
            "all",
            "cancelled",
            "chapter",
            "chinese",
            "comic",
            "complete",
            "completed",
            "doujinshi",
            "english",
            "french",
            "german",
            "hiatus",
            "italian",
            "japanese",
            "korean",
            "manga",
            "manhua",
            "manhwa",
            "ongoing",
            "one shot",
            "portuguese",
            "raw",
            "russian",
            "spanish",
            "translated",
            "webtoon",
        )
    }
}
