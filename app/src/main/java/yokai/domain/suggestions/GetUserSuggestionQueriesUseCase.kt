package yokai.domain.suggestions

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yokai.domain.source.browse.filter.SavedSearchRepository

class GetUserSuggestionQueriesUseCase(
    private val getAffinityTags: GetUserAffinityTagsUseCase,
    private val savedSearchRepository: SavedSearchRepository,
    private val preferences: PreferencesHelper,
    private val canonicalizer: TagCanonicalizer,
) {
    suspend fun execute(): List<SuggestionQuery> {
        val affinityTags = getAffinityTags.execute()
        val savedSearches = savedSearchRepository.findAll()
        // Pinned tags are V1-only. Resolve to canonical keys so they dedupe against the
        // history-derived affinity tag list (pin wins because PINNED_SCORE > any affinity).
        // canonicalize() is suspend, so this can't be a non-suspend Sequence chain.
        val pinnedCanonicalTags = run {
            val seen = LinkedHashSet<String>()
            for (rawTag in preferences.suggestionsPinnedTags().get()) {
                val key = canonicalizer.canonicalize(rawTag).canonicalKey
                if (key.isNotBlank()) seen.add(key)
            }
            seen.toList()
        }

        return withContext(Dispatchers.Default) {
            val pinnedQueries = pinnedCanonicalTags.map { canonicalTag ->
                SuggestionQuery(
                    query = canonicalTag,
                    sectionKey = "pinned:${canonicalTag.normalizedQuery()}",
                    score = PINNED_SCORE,
                )
            }

            val tagQueries = affinityTags.map { tag ->
                SuggestionQuery(
                    query = tag.canonicalTag,
                    sectionKey = "tag:${tag.canonicalTag.normalizedQuery()}",
                    score = tag.score,
                )
            }

            val savedSearchQueries = savedSearches
                .asSequence()
                .mapNotNull { savedSearch -> savedSearch.query?.trim()?.takeIf { it.isUsefulSearchQuery() } }
                .distinctBy { it.lowercase() }
                .take(MAX_SAVED_SEARCH_QUERIES)
                .map { query ->
                    SuggestionQuery(
                        query = query,
                        sectionKey = "search:${query.normalizedQuery()}",
                        score = SAVED_SEARCH_SCORE,
                    )
                }
                .toList()

            // Order matters: pinnedQueries first so distinctBy(query) keeps the pinned variant
            // when an affinity tag canonicalizes to the same key.
            (pinnedQueries + tagQueries + savedSearchQueries)
                .distinctBy { it.query.normalizedQuery() }
                .sortedByDescending { it.score }
                .take(MAX_TOTAL_QUERIES)
        }
    }

    private fun String.isUsefulSearchQuery(): Boolean =
        length in MIN_QUERY_LENGTH..MAX_QUERY_LENGTH

    private fun String.normalizedQuery(): String =
        lowercase().trim().replace(WHITESPACE, " ")

    private companion object {
        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_QUERY_LENGTH = 64
        private const val MAX_SAVED_SEARCH_QUERIES = 8
        private const val MAX_TOTAL_QUERIES = 64
        private const val SAVED_SEARCH_SCORE = 1.5
        /** Pin score is unconditionally above any affinity / saved-search value so pinned tags
         *  rank at the top of the V1 query list. */
        private const val PINNED_SCORE = 1_000.0
        /** Score above which the tag gets top-priority display */
        private const val HIGH_AFFINITY_THRESHOLD = 5.0
        /** Score above which the tag gets mid-priority display */
        private const val MID_AFFINITY_THRESHOLD = 2.0
        private val WHITESPACE = Regex("\\s+")
    }
}
