package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.model.SManga
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yokai.domain.manga.MangaRepository

class SuggestionRanker(
    private val mangaRepository: MangaRepository,
    private val tagCanonicalizer: TagCanonicalizer,
    private val tagProfileRepository: TagProfileRepository,
    private val debugLog: SuggestionsDebugLog,
    private val random: Random = Random.Default,
) {
    suspend fun rank(
        retrievalResults: List<CandidateRetrievalResult>,
        globalSeenKeys: Set<String>,
        sectionSeenKeys: Map<String, Set<String>>,
        sessionContext: SessionContext,
    ): List<SuggestedManga> {
        val localManga = mangaRepository.getMangaList()
        val localKeys = localManga.map { it.source to it.url }.toSet()
        val localTitles = localManga.map { it.title.normalizedTitle() }.toSet()
        val profiles = tagProfileRepository.getAllProfiles().associateBy { it.canonicalTag }
        val blacklistedTags = profiles.values
            .filter { it.isBlacklisted }
            .map { it.canonicalTag }
            .toSet()
        val recentSessionTags = sessionContext.getRecentTags()

        val rankedBySection = retrievalResults.flatMap { result ->
            rankSection(
                result = result,
                localKeys = localKeys,
                localTitles = localTitles,
                globalSeenKeys = globalSeenKeys,
                sectionSeenKeys = sectionSeenKeys[result.section.sectionKey].orEmpty(),
                profiles = profiles,
                blacklistedTags = blacklistedTags,
                recentSessionTags = recentSessionTags,
            )
        }

        return rankedBySection
            .capSuggestionsBySource(SuggestionsConfig.MAX_PER_SOURCE_FEED)
            .mapIndexed { index, suggestion -> suggestion.copy(displayRank = index.toLong()) }
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
    ): List<SuggestedManga> {
        val bestByTitle = linkedMapOf<String, ScoredCandidate>()
        for (candidate in result.candidates) {
            val mangaKey = candidate.mangaKey()
            val titleKey = candidate.manga.title.normalizedTitle()
            when {
                candidate.sourceId to candidate.manga.url in localKeys -> {
                    debugLog.add(LogType.ITEM_FILTERED, "[$mangaKey] filtered - already in library")
                    continue
                }
                mangaKey in globalSeenKeys -> {
                    debugLog.add(LogType.ITEM_FILTERED, "[$mangaKey] filtered - seen globally")
                    continue
                }
                mangaKey in sectionSeenKeys -> {
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
            bestByTitle.values
                .sortedByDescending { it.score }
                .capScoredBySource(SuggestionsConfig.MAX_PER_SOURCE_PER_SECTION)
                .take(SuggestionsConfig.MAX_RESULTS_PER_SECTION)
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

        val tagAffinityScore = bestProfile?.affinity ?: 0.0
        val freshnessScore = if (candidate.mangaKey() in globalSeenKeys) 0.5 else 1.0
        val sessionBoost = if (candidateTags.any { it in recentSessionTags }) 0.15 else 0.0
        val velocityBoost = ((bestProfile?.velocity ?: 0.0).coerceAtLeast(0.0)) * SuggestionsConfig.VELOCITY_WEIGHT
        val explorationNoise = random.nextDouble() * 0.05
        val sourcePenalty = candidate.sourceIndex * SOURCE_PENALTY + candidate.position * POSITION_PENALTY

        return tagAffinityScore * 0.50 +
            freshnessScore * 0.20 +
            sessionBoost +
            velocityBoost * 0.10 +
            explorationNoise -
            sourcePenalty
    }

    private fun SuggestedManga.sourceKey(): Long = source

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
                reason = candidate.section.displayReason,
                relevanceScore = score,
            )
    }

    private fun List<ScoredCandidate>.capScoredBySource(max: Int): List<ScoredCandidate> {
        val counts = mutableMapOf<Long, Int>()
        return filter { scored ->
            val count = counts.getOrDefault(scored.candidate.sourceId, 0)
            if (count < max) {
                counts[scored.candidate.sourceId] = count + 1
                true
            } else {
                false
            }
        }
    }

    private fun List<SuggestedManga>.capSuggestionsBySource(max: Int): List<SuggestedManga> {
        val counts = mutableMapOf<Long, Int>()
        return filter { suggestion ->
            val count = counts.getOrDefault(suggestion.sourceKey(), 0)
            if (count < max) {
                counts[suggestion.sourceKey()] = count + 1
                true
            } else {
                false
            }
        }
    }

    private companion object {
        private const val SOURCE_PENALTY = 0.001
        private const val POSITION_PENALTY = 0.01
        private val TITLE_PUNCTUATION = Regex("[\\p{Punct}]")
        private val WHITESPACE = Regex("\\s+")
    }
}
