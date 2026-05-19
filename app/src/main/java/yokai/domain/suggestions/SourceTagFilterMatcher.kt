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

private suspend fun TagCanonicalizer.matchesCanonicalTag(
    rawTag: String,
    canonicalTag: String,
    sourceId: Long,
): Boolean =
    canonicalizeToLookupKey(rawTag, sourceId) == canonicalTag
