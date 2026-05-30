package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal data class SourceTagFilterMatch(
    val filters: FilterList?,
    val matchedLabel: String? = null,
    val matchedKind: String? = null,
    val scannedLabels: Int = 0,
    val textTagFieldName: String? = null,
    val textTagFieldDenied: Boolean = false,
) {
    val matched: Boolean get() = filters != null
}

internal suspend fun CatalogueSource.tryIncludeTagFilter(
    canonicalTag: String,
    tagCanonicalizer: TagCanonicalizer,
): FilterList? =
    tryIncludeTagFilterWithDiagnostics(canonicalTag, tagCanonicalizer).filters

internal suspend fun CatalogueSource.tryIncludeTagFilterWithDiagnostics(
    canonicalTag: String,
    tagCanonicalizer: TagCanonicalizer,
): SourceTagFilterMatch {
    val filters = getFilterList()
    var filterInjected = false
    var matchedLabel: String? = null
    var matchedKind: String? = null
    var scannedLabels = 0
    var textTagFieldName: String? = null
    var textTagFieldDenied = false

    filters.forEach { filter ->
        when (filter) {
            is Filter.Group<*> -> {
                filter.state.forEach { item ->
                    val matched = when (item) {
                        is Filter.CheckBox -> {
                            scannedLabels++
                            if (tagCanonicalizer.matchesCanonicalTag(item.name, canonicalTag, id)) {
                                item.state = true
                                matchedLabel = item.name
                                matchedKind = "CHECKBOX"
                                true
                            } else {
                                false
                            }
                        }
                        is Filter.TriState -> {
                            scannedLabels++
                            if (tagCanonicalizer.matchesCanonicalTag(item.name, canonicalTag, id)) {
                                item.state = Filter.TriState.STATE_INCLUDE
                                matchedLabel = item.name
                                matchedKind = "TRISTATE"
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
                    scannedLabels++
                    tagCanonicalizer.matchesCanonicalTag(value.toString(), canonicalTag, id)
                }
                if (matchIndex >= 0) {
                    filter.state = matchIndex
                    filterInjected = true
                    matchedLabel = filter.values[matchIndex].toString()
                    matchedKind = "SELECT"
                }
            }
            is Filter.Text -> {
                // HentaiNexus and Madara's TagsFilter expose tag
                // search as a free-text input. The filter name signals what the field
                // is for ("Tags", "Genre", "Tag", "Genres" — case/punct-insensitive).
                // Some HentaiHand-theme extensions resolve that text through a broken
                // tag-ID API, so Suggestions must not inject their text tag filter.
                if (filter.name.looksLikeTagInputField()) {
                    textTagFieldName = filter.name
                    if (supportsTextTagFilterInjection()) {
                        filter.state = canonicalTag
                        filterInjected = true
                        matchedLabel = filter.name
                        matchedKind = "TEXT_FIELD"
                    } else {
                        textTagFieldDenied = true
                    }
                }
            }
            else -> {
                // Header, Separator, and Sort do not include a tag directly.
            }
        }
    }

    return SourceTagFilterMatch(
        filters = filters.takeIf { filterInjected },
        matchedLabel = matchedLabel,
        matchedKind = matchedKind,
        scannedLabels = scannedLabels,
        textTagFieldName = textTagFieldName,
        textTagFieldDenied = textTagFieldDenied,
    )
}

private fun String.looksLikeTagInputField(): Boolean {
    val normalized = lowercase().replace(Regex("[^a-z]+"), "")
    return normalized == "tag" || normalized == "tags" ||
        normalized == "genre" || normalized == "genres"
}

private fun CatalogueSource.supportsTextTagFilterInjection(): Boolean =
    TEXT_TAG_FILTER_INJECTION_DENYLIST.none { blockedName ->
        normalizedSourceName().contains(blockedName)
    }

private fun CatalogueSource.normalizedSourceName(): String =
    name.lowercase().replace(NON_ALNUM, "")

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
private val NON_ALNUM = Regex("[^a-z0-9]+")
private val TEXT_TAG_FILTER_INJECTION_DENYLIST = setOf(
    "hentaihand",
    "nhentaicom",
    "nhentai",
)
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
