package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal suspend fun CatalogueSource.tryIncludeTagFilter(
    canonicalTag: String,
    tagCanonicalizer: TagCanonicalizer,
): FilterList? {
    val filters = getFilterList()
    var filterInjected = false

    filters.forEach { filter ->
        when (filter) {
            is Filter.Group<*> -> {
                filter.state.forEach { item ->
                    val matched = when (item) {
                        is Filter.CheckBox -> {
                            if (tagCanonicalizer.matchesCanonicalTag(item.name, canonicalTag, id)) {
                                item.state = true
                                true
                            } else {
                                false
                            }
                        }
                        is Filter.TriState -> {
                            if (tagCanonicalizer.matchesCanonicalTag(item.name, canonicalTag, id)) {
                                item.state = Filter.TriState.STATE_INCLUDE
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                    if (matched) filterInjected = true
                }
            }
            is Filter.Select<*> -> {
                val matchIndex = filter.values.indexOfFirst { value ->
                    tagCanonicalizer.matchesCanonicalTag(value.toString(), canonicalTag, id)
                }
                if (matchIndex >= 0) {
                    filter.state = matchIndex
                    filterInjected = true
                }
            }
            else -> {
                // Header, Separator, Text, and Sort do not include a tag directly.
            }
        }
    }

    return if (filterInjected) filters else null
}

internal fun FilterList.tryApplySuggestionSort(sortOrder: SuggestionSortOrder): Boolean {
    var sortApplied = false
    forEach { filter ->
        if (filter.tryApplySuggestionSort(sortOrder)) {
            sortApplied = true
        }
    }
    return sortApplied
}

private fun Filter<*>.tryApplySuggestionSort(sortOrder: SuggestionSortOrder): Boolean {
    return when (this) {
        is Filter.Sort -> {
            val matchIndex = values.indexOfFirst { value ->
                value.matchesSortOrder(sortOrder)
            }
            if (matchIndex >= 0) {
                state = Filter.Sort.Selection(matchIndex, ascending = false)
                true
            } else {
                false
            }
        }
        is Filter.Select<*> -> {
            if (!name.isSortSelectName()) return false
            val matchIndex = values.indexOfFirst { value ->
                value.toString().matchesSortOrder(sortOrder)
            }
            if (matchIndex >= 0) {
                state = matchIndex
                true
            } else {
                false
            }
        }
        is Filter.Group<*> -> {
            var sortApplied = false
            state.forEach { item ->
                if (item is Filter<*> && item.tryApplySuggestionSort(sortOrder)) {
                    sortApplied = true
                }
            }
            sortApplied
        }
        else -> false
    }
}

private suspend fun TagCanonicalizer.matchesCanonicalTag(
    rawTag: String,
    canonicalTag: String,
    sourceId: Long,
): Boolean =
    canonicalizeToLookupKey(rawTag, sourceId) == canonicalTag

private fun String.isSortSelectName(): Boolean {
    val normalized = normalizedSortText()
    return normalized.contains("sort") ||
        normalized.contains("order") ||
        normalized.contains("ranking")
}

private fun String.matchesSortOrder(sortOrder: SuggestionSortOrder): Boolean {
    val normalized = normalizedSortText()
    val terms = when (sortOrder) {
        SuggestionSortOrder.Latest -> LATEST_SORT_TERMS
        SuggestionSortOrder.Popular -> POPULAR_SORT_TERMS
    }
    return terms.any { term ->
        normalized == term || normalized.contains(term)
    }
}

private fun String.normalizedSortText(): String =
    lowercase()
        .replace(SORT_PUNCTUATION, " ")
        .replace(WHITESPACE, " ")
        .trim()

private val SORT_PUNCTUATION = Regex("[^a-z0-9]+")
private val WHITESPACE = Regex("\\s+")
private val LATEST_SORT_TERMS = listOf(
    "latest",
    "last update",
    "updated",
    "update",
    "newest",
    "new",
    "date added",
    "created",
)
private val POPULAR_SORT_TERMS = listOf(
    "popular",
    "popularity",
    "most viewed",
    "views",
    "view count",
    "follow",
    "follows",
    "rating",
    "score",
    "rank",
)
