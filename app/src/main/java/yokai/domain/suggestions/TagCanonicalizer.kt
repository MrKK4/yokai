package yokai.domain.suggestions

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class CanonicalTag(
    val canonicalKey: String,
    val displayName: String,
)

class TagCanonicalizer(
    private val repository: TagProfileRepository,
) {
    private val defaultsSeeded = AtomicBoolean(false)

    suspend fun canonicalize(rawTag: String, sourceId: Long? = null): CanonicalTag {
        ensureDefaultAliases()

        val displayName = rawTag.trim()
        val rawKey = normalizeToLookupKey(rawTag)
        if (rawKey.isBlank()) {
            return CanonicalTag(canonicalKey = "", displayName = displayName)
        }

        val alias = repository.findAlias(rawKey, sourceId)
        val canonicalKey = alias?.canonicalTag
            ?: depluralizeIfKnown(rawKey)
            ?: rawKey

        repository.recordVariant(canonicalKey, displayName)
        val bestDisplayName = repository.bestDisplayName(canonicalKey)
            ?: displayName.takeIf { it.isNotBlank() }
            ?: canonicalKey.toDisplayTag()

        return CanonicalTag(
            canonicalKey = canonicalKey,
            displayName = bestDisplayName,
        )
    }

    fun normalizeToLookupKey(rawTag: String): String {
        val normalized = Normalizer.normalize(rawTag.trim(), Normalizer.Form.NFKC)
            .replace(DECORATIVE_SYMBOLS, "")
            .trim(PUNCTUATION_TO_TRIM::contains)
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase(Locale.US)

        return normalized.trim()
    }

    private suspend fun depluralizeIfKnown(rawKey: String): String? {
        if (!rawKey.endsWith("s") || rawKey.length <= MIN_DEPLURALIZED_LENGTH) return null
        val singular = rawKey.dropLast(1)
        return singular.takeIf { repository.aliasOrProfileExists(it) }
    }

    private suspend fun ensureDefaultAliases() {
        if (defaultsSeeded.get()) return
        if (repository.aliasCount() == 0L) {
            repository.seedAliases(DEFAULT_ALIASES.map { (rawTag, canonicalTag) ->
                TagAlias(
                    rawTag = rawTag,
                    rawKey = normalizeToLookupKey(rawTag),
                    canonicalTag = normalizeToLookupKey(canonicalTag),
                    sourceId = null,
                    sourceKey = GLOBAL_SOURCE_KEY,
                )
            })
        }
        defaultsSeeded.set(true)
    }

    private fun String.toDisplayTag(): String =
        split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }

    companion object {
        private const val GLOBAL_SOURCE_KEY = -1L
        private const val MIN_DEPLURALIZED_LENGTH = 4
        private val WHITESPACE = Regex("\\s+")
        private val DECORATIVE_SYMBOLS = Regex("[♀♂⚥★☆♡♥•·]")
        private val PUNCTUATION_TO_TRIM = setOf('-', '_', '[', ']', '(', ')', '{', '}', '<', '>', ':', ';', ',', '.', '"', '\'')

        val DEFAULT_ALIASES: List<Pair<String, String>> = listOf(
            "mother ♀" to "mother",
            "milf" to "milf",
            "milfs" to "milf",
            "milves" to "milf",
            "mom" to "mother",
            "stepmom" to "mother",
            "sci fi" to "science fiction",
            "sci-fi" to "science fiction",
            "scifi" to "science fiction",
            "slice-of-life" to "slice of life",
            "slice of life" to "slice of life",
            "romcom" to "romantic comedy",
            "rom-com" to "romantic comedy",
            "romance comedy" to "romantic comedy",
            "isekai" to "isekai",
            "reincarnation" to "isekai",
            "transported to another world" to "isekai",
            "shounen" to "shonen",
            "shoujo" to "shojo",
            "seinen" to "seinen",
            "josei" to "josei",
            "harem" to "harem",
            "reverse harem" to "reverse harem",
            "bl" to "boys love",
            "boys love" to "boys love",
            "yaoi" to "boys love",
            "gl" to "girls love",
            "girls love" to "girls love",
            "yuri" to "girls love",
            "action" to "action",
            "adventure" to "adventure",
            "fantasy" to "fantasy",
            "isekai fantasy" to "isekai",
            "school life" to "school life",
            "school-life" to "school life",
            "high school" to "school life",
        )
    }
}
