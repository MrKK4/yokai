package yokai.domain.suggestions

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import yokai.domain.manga.MangaRepository

class FeedAggregator(
    private val sourceManager: SourceManager,
    private val mangaRepository: MangaRepository,
    private val preferences: PreferencesHelper,
) {
    suspend fun fetch(suggestionQueries: List<SuggestionQuery>): List<SuggestedManga> {
        val random = Random(System.nanoTime())
        val localManga = mangaRepository.getMangaList()
        val (localKeys, localTitles) = withContext(Dispatchers.Default) {
            localManga.map { it.source to it.url }.toSet() to
                localManga.map { it.title.normalizedTitle() }.toSet()
        }
        val sourceSelection = activeSourceSelection(random)
        if (sourceSelection.isEmpty) return emptyList()

        val latest = fetchLatestFromSources(
            sources = sourceSelection.latestSources,
            localKeys = localKeys,
            localTitles = localTitles,
        )
        val latestTitles = withContext(Dispatchers.Default) {
            latest.map { it.titleKey }.toSet()
        }
        val popular = fetchPopularFromSources(
            sources = sourceSelection.mixedSources,
            random = random,
            localKeys = localKeys,
            localTitles = localTitles,
        ).filterOutTitles(latestTitles)
        val popularTitles = withContext(Dispatchers.Default) {
            popular.map { it.titleKey }.toSet()
        }
        val personalizedQueries = withContext(Dispatchers.Default) {
            suggestionQueries
                .distinctBy { it.query.normalizedQuery() }
                .shuffled(random)
                .take(MAX_FEATURED_QUERIES)
        }
        val personalized = fetchPersonalizedFromSources(
            sources = sourceSelection.mixedSources,
            suggestionQueries = personalizedQueries,
            random = random,
            localKeys = localKeys,
            localTitles = localTitles,
        ).filterOutTitles(latestTitles + popularTitles)

        return withContext(Dispatchers.Default) {
            (latest + mixFeedForRefresh(popular, personalized, random))
                .distinctBy { it.sourceId to it.manga.url }
                .take(MAX_TOTAL)
                .mapIndexed { index, hit ->
                    hit.toSuggestion(displayScore = displayScoreFor(index))
                }
        }
    }

    private suspend fun fetchLatestFromSources(
        sources: List<CatalogueSource>,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
    ): List<ScoredManga> {
        val latestSources = sources.filter { it.supportsLatest }
        if (latestSources.isEmpty()) return emptyList()

        val hits = coroutineScope {
            latestSources
                .mapIndexed { sourceIndex, source ->
                    async { fetchLatestFromSource(source, sourceIndex, localKeys, localTitles) }
                }
                .awaitAll()
                .flatten()
        }

        return withContext(Dispatchers.Default) {
            hits.bestByTitle()
                .sortedByDescending { it.relevance }
                .take(MAX_LATEST_TOTAL)
        }
    }

    private suspend fun fetchPopularFromSources(
        sources: List<CatalogueSource>,
        random: Random,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
    ): List<ScoredManga> {
        val hits = coroutineScope {
            sources
                .mapIndexed { sourceIndex, source ->
                    async { fetchPopularFromSource(source, sourceIndex, localKeys, localTitles) }
                }
                .awaitAll()
                .flatten()
        }

        return withContext(Dispatchers.Default) {
            hits.bestByTitle()
                .sortedByDescending { it.relevance }
                .take(MAX_POPULAR_TOTAL)
                .shuffled(random)
        }
    }

    private suspend fun fetchPersonalizedFromSources(
        sources: List<CatalogueSource>,
        suggestionQueries: List<SuggestionQuery>,
        random: Random,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
    ): List<ScoredManga> {
        if (suggestionQueries.isEmpty()) return emptyList()

        val hits = coroutineScope {
            sources
                .map { source ->
                    async { fetchPersonalizedFromSource(source, suggestionQueries, localKeys, localTitles) }
                }
                .awaitAll()
                .flatten()
        }

        return withContext(Dispatchers.Default) {
            hits.bestByTitle()
                .balancedByReason(random)
        }
    }

    private suspend fun fetchLatestFromSource(
        source: CatalogueSource,
        sourceIndex: Int,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
    ): List<ScoredManga> {
        return sourceResult {
            source.getLatestUpdates(1)
        }
            ?.mangas
            ?.asSequence()
            ?.filter { manga -> (source.id to manga.url) !in localKeys }
            ?.filterNot { manga -> manga.title.normalizedTitle() in localTitles }
            ?.mapIndexed { index, manga ->
                ScoredManga(
                    manga = manga,
                    titleKey = manga.title.normalizedTitle(),
                    sourceId = source.id,
                    relevance = GENERIC_RELEVANCE - (sourceIndex * SOURCE_PENALTY) - (index * POSITION_PENALTY),
                    reason = LATEST_REASON,
                    queryScore = 0.0,
                )
            }
            ?.toList()
            .orEmpty()
    }

    private suspend fun fetchPopularFromSource(
        source: CatalogueSource,
        sourceIndex: Int,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
    ): List<ScoredManga> {
        return sourceResult {
            source.getPopularManga(1)
        }
            ?.mangas
            ?.asSequence()
            ?.filter { manga -> (source.id to manga.url) !in localKeys }
            ?.filterNot { manga -> manga.title.normalizedTitle() in localTitles }
            ?.mapIndexed { index, manga ->
                ScoredManga(
                    manga = manga,
                    titleKey = manga.title.normalizedTitle(),
                    sourceId = source.id,
                    relevance = GENERIC_RELEVANCE - (sourceIndex * SOURCE_PENALTY) - (index * POSITION_PENALTY),
                    reason = POPULAR_REASON,
                    queryScore = 0.0,
                )
            }
            ?.toList()
            .orEmpty()
    }

    private suspend fun fetchPersonalizedFromSource(
        source: CatalogueSource,
        suggestionQueries: List<SuggestionQuery>,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
    ): List<ScoredManga> {
        val sourceResults = mutableListOf<ScoredManga>()

        suggestionQueries.forEach { suggestionQuery ->
            sourceResult {
                source.getSearchManga(1, suggestionQuery.query, source.getFilterList())
            }
                ?.mangas
                ?.asSequence()
                ?.filter { manga -> (source.id to manga.url) !in localKeys }
                ?.filterNot { manga -> manga.title.normalizedTitle() in localTitles }
                ?.mapIndexed { index, manga ->
                    ScoredManga(
                        manga = manga,
                        titleKey = manga.title.normalizedTitle(),
                        sourceId = source.id,
                        relevance = suggestionQuery.score - (index * POSITION_PENALTY),
                        reason = suggestionQuery.reason,
                        queryScore = suggestionQuery.score,
                    )
                }
                ?.toList()
                ?.let(sourceResults::addAll)
        }

        return sourceResults.bestByTitle()
    }

    private suspend fun activeSourceSelection(random: Random): SourceSelection {
        val pinnedCatalogueIds = preferences.pinnedCatalogues().get()
        val allSources = sourceManager.getCatalogueSources()
        val pinnedSources = allSources.filter { source -> source.id.toString() in pinnedCatalogueIds }
        val sourceCandidates = pinnedSources.takeIf { it.isNotEmpty() } ?: allSources
        val limit = if (pinnedSources.isNotEmpty()) MAX_ACTIVE_SOURCES else FALLBACK_SOURCE_COUNT

        return withContext(Dispatchers.Default) {
            SourceSelection(
                latestSources = sourceCandidates.take(limit),
                mixedSources = sourceCandidates.shuffled(random).take(limit),
            )
        }
    }

    private fun String.normalizedTitle(): String =
        normalizedQuery()

    private fun String.normalizedQuery(): String =
        lowercase().trim().replace(WHITESPACE, " ")

    private suspend fun <T> sourceResult(block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun List<ScoredManga>.filterOutTitles(titleKeys: Set<String>): List<ScoredManga> =
        filterNot { it.titleKey in titleKeys }

    private fun List<ScoredManga>.bestByTitle(): List<ScoredManga> {
        val bestHits = linkedMapOf<String, ScoredManga>()
        forEach { hit ->
            val key = hit.titleKey
            val existing = bestHits[key]
            if (existing == null || hit.isBetterThan(existing)) {
                bestHits[key] = hit
            }
        }
        return bestHits.values.toList()
    }

    private data class SourceSelection(
        val latestSources: List<CatalogueSource>,
        val mixedSources: List<CatalogueSource>,
    ) {
        val isEmpty: Boolean
            get() = latestSources.isEmpty() && mixedSources.isEmpty()
    }

    private data class ScoredManga(
        val manga: SManga,
        val titleKey: String,
        val sourceId: Long,
        val relevance: Double,
        val reason: String,
        val queryScore: Double,
    ) {
        fun isBetterThan(other: ScoredManga): Boolean =
            queryScore > other.queryScore ||
                (queryScore == other.queryScore && relevance > other.relevance)

        fun toSuggestion(displayScore: Double): SuggestedManga =
            SuggestedManga(
                source = sourceId,
                url = manga.url,
                title = manga.title,
                thumbnailUrl = manga.thumbnail_url,
                reason = reason,
                relevanceScore = displayScore,
            )
    }

    private fun List<ScoredManga>.balancedByReason(random: Random): List<ScoredManga> =
        groupBy { it.reason }
            .entries
            .shuffled(random)
            .flatMap { (_, hits) ->
                hits.sortedByDescending { it.relevance }
                    .take(MAX_PER_REASON)
                    .shuffled(random)
            }

    private fun mixFeedForRefresh(
        popular: List<ScoredManga>,
        personalized: List<ScoredManga>,
        random: Random,
    ): List<ScoredManga> {
        val personalizedQueue = personalized.shuffled(random).toMutableList()
        val popularQueue = popular.shuffled(random).toMutableList()
        val mixed = mutableListOf<ScoredManga>()

        while ((personalizedQueue.isNotEmpty() || popularQueue.isNotEmpty()) && mixed.size < MAX_TOTAL) {
            repeat(PERSONALIZED_BURST) {
                personalizedQueue.popFirst()?.let(mixed::add)
            }
            repeat(POPULAR_BURST) {
                popularQueue.popFirst()?.let(mixed::add)
            }
        }

        return mixed
    }

    private fun MutableList<ScoredManga>.popFirst(): ScoredManga? =
        if (isEmpty()) null else removeAt(0)

    private fun displayScoreFor(index: Int): Double =
        (MAX_TOTAL - index).toDouble()

    private companion object {
        private const val MAX_ACTIVE_SOURCES = 8
        private const val FALLBACK_SOURCE_COUNT = 3
        private const val MAX_FEATURED_QUERIES = 2
        private const val MAX_LATEST_TOTAL = 24
        private const val MAX_POPULAR_TOTAL = 24
        private const val MAX_PER_REASON = 12
        private const val MAX_TOTAL = 84
        private const val PERSONALIZED_BURST = 2
        private const val POPULAR_BURST = 1
        private const val GENERIC_RELEVANCE = 1_000_000.0
        private const val SOURCE_PENALTY = 0.001
        private const val POSITION_PENALTY = 0.01
        private const val LATEST_REASON = "Latest from selected sources"
        private const val POPULAR_REASON = "Popular from selected sources"
        private val WHITESPACE = Regex("\\s+")
    }
}
