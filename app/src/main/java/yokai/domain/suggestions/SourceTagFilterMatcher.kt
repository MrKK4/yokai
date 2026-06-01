package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.delay

/** Default backoff between filter-list re-reads while waiting for async genre lists to load. */
internal const val DEFAULT_FILTER_RELOAD_DELAY_MS = 400L

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

/** True once the filter list exposes injectable genre/tag labels (a populated CheckBox/TriState
 *  group or a multi-value Select). Used to detect when an async genre fetch has landed. */
internal fun FilterList.hasInjectableTagLabels(): Boolean =
    any { filter ->
        when (filter) {
            is Filter.Group<*> -> filter.state.any { it is Filter.CheckBox || it is Filter.TriState }
            is Filter.Select<*> -> filter.values.size > 1
            else -> false
        }
    }

/**
 * Re-reads [getFilterList] up to [attempts] times (waiting [intervalMs] between reads) until the
 * source's async genre list has loaded ([hasInjectableTagLabels]). Returns the first loaded list,
 * or the last read if it never loads. Sources whose filters are already populated return on the
 * first read with no waiting.
 */
internal suspend fun CatalogueSource.awaitLoadedFilterList(
    attempts: Int,
    intervalMs: Long,
): FilterList {
    var filters = getFilterList()
    var tries = 0
    while (!filters.hasInjectableTagLabels() && tries < attempts) {
        if (intervalMs > 0) delay(intervalMs)
        tries++
        filters = getFilterList()
    }
    return filters
}

internal suspend fun CatalogueSource.tryIncludeTagFilterWithDiagnostics(
    canonicalTag: String,
    tagCanonicalizer: TagCanonicalizer,
    maxFilterReloads: Int = 0,
    reloadDelayMs: Long = DEFAULT_FILTER_RELOAD_DELAY_MS,
): SourceTagFilterMatch {
    var match = scanFilterListForTag(getFilterList(), canonicalTag, tagCanonicalizer)
    var reloads = 0
    // GalleryAdults-style themes fetch their genre list asynchronously, so the first
    // getFilterList() can return no genre group at all (scannedLabels == 0 and no text tag
    // field). Re-read with backoff to let that async load land before conceding to text
    // fallback. A populated-but-non-matching list (scannedLabels > 0) or a text tag field is a
    // genuine miss, not a load race, so we stop immediately in those cases.
    while (
        !match.matched &&
        match.scannedLabels == 0 &&
        match.textTagFieldName == null &&
        reloads < maxFilterReloads
    ) {
        if (reloadDelayMs > 0) delay(reloadDelayMs)
        reloads++
        match = scanFilterListForTag(getFilterList(), canonicalTag, tagCanonicalizer)
    }
    return match
}

private suspend fun CatalogueSource.scanFilterListForTag(
    filters: FilterList,
    canonicalTag: String,
    tagCanonicalizer: TagCanonicalizer,
): SourceTagFilterMatch {
    var filterInjected = false
    var matchedLabel: String? = null
    var matchedKind: String? = null
    var scannedLabels = 0
    var textTagFieldName: String? = null
    var textTagFieldDenied = false

    for (filter in filters) {
        if (filterInjected) break
        when (filter) {
            is Filter.Group<*> -> {
                for (item in filter.state) {
                    if (filterInjected) break
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
                var matchIndex = -1
                for ((index, value) in filter.values.withIndex()) {
                    scannedLabels++
                    if (tagCanonicalizer.matchesCanonicalTag(value.toString(), canonicalTag, id)) {
                        matchIndex = index
                        break
                    }
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
// Sources whose free-text "Tags" field must NOT be injected because their text→tag resolver is
// broken in practice. HentaiHand's /api/tags?q= lookup throws JsonDecodingException in real use
// (verified 2026-06-01: injecting its Tags field produced only SECTION_DROPPED errors, zero
// results — the popular /api/comics endpoint works but the tag-lookup endpoint does not parse).
// Its TEXT search returns 0 for tag terms, so the chronic-empty cooldown benches it quietly.
// nhentai keeps its own `tag:` query syntax, so its plain Tags field stays denied too.
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
