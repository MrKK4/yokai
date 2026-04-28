package yokai.domain.suggestions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yokai.domain.source.browse.filter.SavedSearchRepository

class GetUserSuggestionQueriesUseCase(
    private val getAffinityTags: GetUserAffinityTagsUseCase,
    private val savedSearchRepository: SavedSearchRepository,
) {
    suspend fun execute(): List<SuggestionQuery> {
        val affinityTags = getAffinityTags.execute()
        val savedSearches = savedSearchRepository.findAll()

        return withContext(Dispatchers.Default) {
            val tagQueries = affinityTags.map { tag ->
                SuggestionQuery(
                    query = tag.name,
                    reason = "Because you read ${tag.name}",
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
                        reason = "Because you searched \"$query\"",
                        score = SAVED_SEARCH_SCORE,
                    )
                }
                .toList()

            (tagQueries + savedSearchQueries)
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
        private val WHITESPACE = Regex("\\s+")
    }
}
