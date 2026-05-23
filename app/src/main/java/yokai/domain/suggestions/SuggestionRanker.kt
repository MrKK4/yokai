package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.model.SManga
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yokai.domain.manga.MangaRepository

/**
 * Snapshot of the expensive, batch-stable data that [SuggestionRanker] needs to rank
 * candidates. Fetch once per batch with [SuggestionRanker.buildRankingContext] and pass
 * the same instance into every [SuggestionRanker.rankWithContext] call, eliminating
 * redundant full-table DB reads across sections.
 */
data class RankingContext(
    val localKeys: Set<Pair<Long, String>>,
    val localTitles: Set<String>,
    val profiles: Map<String, TagProfile>,
    val blacklistedTags: Set<String>,
)

class SuggestionRanker(
    private val mangaRepository: MangaRepository,
    private val tagCanonicalizer: TagCanonicalizer,
    private val tagProfileRepository: TagProfileRepository,
    private val debugLog: SuggestionsDebugLog,
    private val random: Random = Random.Default,
) {
    /** Build the batch-stable ranking context from DB. Call once per batch, not per section. */
    suspend fun buildRankingContext(): RankingContext {
        val localManga = mangaRepository.getMangaList()
        val profiles = tagProfileRepository.getAllProfiles().associateBy { it.canonicalTag }
        return RankingContext(
            localKeys = localManga.map { it.source to it.url }.toSet(),
            localTitles = localManga.map { it.title.normalizedTitle() }.toSet(),
            profiles = profiles,
            blacklistedTags = profiles.values
                .filter { it.isBlacklisted }
                .map { it.canonicalTag }
                .toSet(),
        )
    }

    /**
     * Rank using a pre-built [RankingContext]. Use this in batch flows to avoid
     * re-fetching local manga + profiles from the DB for every section.
     */
    suspend fun rankWithContext(
        retrievalResults: List<CandidateRetrievalResult>,
        context: RankingContext,
        globalSeenKeys: Set<String>,
        sectionSeenKeys: Map<String, Set<String>>,
        sessionContext: SessionContext,
        maxResults: Int = SuggestionsConfig.MAX_RESULTS_PER_SECTION,
    ): List<SuggestedManga> {
        val recentSessionTags = sessionContext.getRecentTags()
        val rankedBySection = retrievalResults.flatMap { result ->
            rankSection(
                result = result,
                localKeys = context.localKeys,
                localTitles = context.localTitles,
                globalSeenKeys = globalSeenKeys,
                sectionSeenKeys = sectionSeenKeys[result.section.sectionKey].orEmpty(),
                profiles = context.profiles,
                blacklistedTags = context.blacklistedTags,
                recentSessionTags = recentSessionTags,
                maxResults = maxResults,
            )
        }
        return rankedBySection.mapIndexed { index, suggestion -> suggestion.copy(displayRank = index.toLong()) }
    }

    /**
     * Rank expanded "view more" results fetched by [FeedAggregator].
     * Applies title dedup (best score wins) and source diversity (round-robin).
     * Library/seen-URL/blacklisted-tag filtering is already done by FeedAggregator.
     */
    fun rankExpandedResults(
        results: List<SuggestedManga>,
        maxResults: Int = SuggestionsConfig.EXPANDED_MAX_RESULTS,
    ): List<SuggestedManga> {
        val bestByTitle = linkedMapOf<String, SuggestedManga>()
        for (manga in results) {
            val titleKey = manga.title.normalizedTitle()
            val existing = bestByTitle[titleKey]
            if (existing == null || manga.relevanceScore > existing.relevanceScore) {
                bestByTitle[titleKey] = manga
            }
        }
        return bestByTitle.values.roundRobinBySourceSuggested(maxResults)
    }

    /** Legacy overload — fetches context from DB on every call. Use [rankWithContext] in batch flows. */
    suspend fun rank(
        retrievalResults: List<CandidateRetrievalResult>,
        globalSeenKeys: Set<String>,
        sectionSeenKeys: Map<String, Set<String>>,
        sessionContext: SessionContext,
        maxResults: Int = SuggestionsConfig.MAX_RESULTS_PER_SECTION,
    ): List<SuggestedManga> {
        val context = buildRankingContext()
        return rankWithContext(retrievalResults, context, globalSeenKeys, sectionSeenKeys, sessionContext, maxResults)
    }

    private suspend fun rankSection(
        result: CandidateRetrievalResult,
        localKeys: Set<Pair<Long, String>>,
        localTitles: Set<String>,
        globalSeenKeys: Set<String>,
        sectionSeenKeys: Set<String>,
        profiles: Map<String, TagProfile>,
        blacklistedTags: Set<String>,
        recentSessionTags: Set<String>,
        maxResults: Int = SuggestionsConfig.MAX_RESULTS_PER_SECTION,
    ): List<SuggestedManga> {

        val coldStartDiscovery = result.section.isColdStartDiscovery()
        val bestByTitle = linkedMapOf<String, ScoredCandidate>()
        for (candidate in result.candidates) {
            val mangaKey = candidate.mangaKey()
            val titleKey = candidate.manga.title.normalizedTitle()
            when {
                candidate.sourceId to candidate.manga.url in localKeys -> {
                    debugLog.add(LogType.ITEM_FILTERED, "[$mangaKey] filtered - already in library")
                    continue
                }
                !coldStartDiscovery && mangaKey in globalSeenKeys -> {
                    debugLog.add(LogType.ITEM_FILTERED, "[$mangaKey] filtered - seen globally")
                    continue
                }
                !coldStartDiscovery && mangaKey in sectionSeenKeys -> {
                    debugLog.add(LogType.ITEM_FILTERED, "[$mangaKey] filtered - seen in section '${result.section.sectionKey}'")
                    continue
                }
                titleKey in localTitles -> {
                    debugLog.add(LogType.ITEM_FILTERED, "[$mangaKey] filtered - title already in library")
                    continue
                }
            }

            val candidateTags = candidate.canonicalTags(result.section)
            val blacklistedMatch = candidateTags.firstOrNull { it in blacklistedTags }
            if (blacklistedMatch != null) {
                debugLog.add(LogType.ITEM_FILTERED, "[$mangaKey] filtered - tag '$blacklistedMatch' blacklisted")
                continue
            }

            val score = compositeScore(
                candidate = candidate,
                candidateTags = candidateTags,
                profiles = profiles,
                globalSeenKeys = globalSeenKeys,
                recentSessionTags = recentSessionTags,
            )
            val scored = ScoredCandidate(candidate, score)
            val existing = bestByTitle[titleKey]
            if (existing == null || scored.score > existing.score) {
                bestByTitle[titleKey] = scored
            }
        }

        return withContext(Dispatchers.Default) {
            val effectiveMax = if (coldStartDiscovery) {
                SuggestionsConfig.COLD_START_MAX_RESULTS
            } else {
                maxResults
            }
            val selected = bestByTitle.values.roundRobinBySource(effectiveMax)
            selected
                .map { it.toSuggestedManga() }
        }
    }

    private suspend fun SuggestionCandidate.canonicalTags(section: PlannedSection): Set<String> {
        val mangaTags = manga.getGenres()
            .orEmpty()
            .mapNotNull { rawTag ->
                tagCanonicalizer.canonicalize(rawTag, sourceId)
                    .canonicalKey
                    .takeIf { it.isNotBlank() }
            }
        return (mangaTags + section.canonicalTag.orEmpty())
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun compositeScore(
        candidate: SuggestionCandidate,
        candidateTags: Set<String>,
        profiles: Map<String, TagProfile>,
        globalSeenKeys: Set<String>,
        recentSessionTags: Set<String>,
    ): Double {
        val bestProfile = candidateTags
            .mapNotNull { profiles[it] }
            .maxByOrNull { it.affinity }

        if (candidate.section.isColdStartDiscovery()) {
            return random.nextDouble()
        }

        val tagAffinityScore = bestProfile?.affinity ?: 0.0
        val freshnessScore = if (candidate.mangaKey() in globalSeenKeys) FRESHNESS_SEEN_PENALTY else 1.0
        val sessionBoost = if (candidateTags.any { it in recentSessionTags }) SESSION_BOOST else 0.0
        val velocityBoost = ((bestProfile?.velocity ?: 0.0).coerceAtLeast(0.0)) * SuggestionsConfig.VELOCITY_WEIGHT
        val explorationNoise = random.nextDouble() * EXPLORATION_NOISE_CEILING
        val sourcePenalty = candidate.sourceIndex * SOURCE_PENALTY + candidate.position * POSITION_PENALTY

        return tagAffinityScore * TAG_AFFINITY_WEIGHT +
            freshnessScore * FRESHNESS_WEIGHT +
            sessionBoost +
            velocityBoost +
            explorationNoise -
            sourcePenalty
    }

    private fun SuggestionCandidate.mangaKey(): String =
        "$sourceId:${manga.url}"

    private fun String.normalizedTitle(): String =
        lowercase()
            .replace(TITLE_PUNCTUATION, " ")
            .replace(WHITESPACE, " ")
            .trim()

    private data class ScoredCandidate(
        val candidate: SuggestionCandidate,
        val score: Double,
    ) {
        fun toSuggestedManga(): SuggestedManga =
            SuggestedManga(
                source = candidate.sourceId,
                url = candidate.manga.url,
                title = candidate.manga.title,
                thumbnailUrl = candidate.manga.thumbnail_url,
                sectionKey = candidate.section.sectionKey,
                relevanceScore = score,
            )
    }

    private fun Collection<ScoredCandidate>.roundRobinBySource(maxResults: Int): List<ScoredCandidate> {
        return SourceDiversity.roundRobinBySource(
            items = this,
            maxResults = maxResults,
            sourceId = { it.candidate.sourceId },
            sourceIndex = { it.candidate.sourceIndex },
            score = { it.score },
        )
    }

    private fun Collection<SuggestedManga>.roundRobinBySourceSuggested(maxResults: Int): List<SuggestedManga> {
        return SourceDiversity.roundRobinBySource(
            items = this,
            maxResults = maxResults,
            sourceId = { it.source },
            sourceIndex = { 0 }, // no source index available; all treated equally
            score = { it.relevanceScore },
        )
    }

    private companion object {
        private const val SOURCE_PENALTY = 0.001
        private const val POSITION_PENALTY = 0.01
        // Scoring weights for compositeScore()
        private const val TAG_AFFINITY_WEIGHT = 0.50
        private const val FRESHNESS_WEIGHT = 0.20
        private const val FRESHNESS_SEEN_PENALTY = 0.5   // score when manga was seen in this session
        private const val SESSION_BOOST = 0.15            // boost when a tag was recently opened
        private const val EXPLORATION_NOISE_CEILING = 0.05
        private val TITLE_PUNCTUATION = Regex("[\\p{Punct}]")
        private val WHITESPACE = Regex("\\s+")
    }
}
