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

        // Strip decorative symbols + count suffixes from the display copy so the UI
        // does not render "MILF ◆" or "Action 1.5k" — but keep original casing and
        // word punctuation so "Boys' Love" stays "Boys' Love" instead of collapsing
        // to "boys love".
        val displayName = cleanDisplayName(rawTag)
        val rawKey = normalizeToLookupKey(rawTag)
        if (rawKey.isBlank()) {
            return CanonicalTag(canonicalKey = "", displayName = displayName)
        }

        val registryMatch = TagRegistry.find(rawKey)
        val canonicalKey = resolveCanonicalKey(rawKey, sourceId, registryMatch)

        repository.recordVariant(canonicalKey, displayName)
        val bestDisplayName = repository.bestDisplayName(canonicalKey)
            ?: registryMatch?.displayName
            ?: displayName.takeIf { it.isNotBlank() }
            ?: canonicalKey.toDisplayTag()

        return CanonicalTag(
            canonicalKey = canonicalKey,
            displayName = bestDisplayName,
        )
    }

    private fun cleanDisplayName(rawTag: String): String =
        Normalizer.normalize(rawTag.trim(), Normalizer.Form.NFKC)
            .replace(DECORATIVE_SYMBOLS, "")
            .replace(COUNT_OR_PAREN, "")
            .trim(PUNCTUATION_TO_TRIM::contains)
            .replace(WHITESPACE, " ")
            .trim()

    suspend fun canonicalizeToLookupKey(rawTag: String, sourceId: Long? = null): String {
        ensureDefaultAliases()

        val rawKey = normalizeToLookupKey(rawTag)
        if (rawKey.isBlank()) return ""

        return resolveCanonicalKey(rawKey, sourceId, TagRegistry.find(rawKey))
    }

    fun normalizeToLookupKey(rawTag: String): String {
        val normalized = Normalizer.normalize(rawTag.trim(), Normalizer.Form.NFKC)
            .replace(DECORATIVE_SYMBOLS, "")
            .replace(COUNT_OR_PAREN, " ")
            .replace(INTERNAL_PUNCTUATION, " ")
            .replace(APOSTROPHES, "")
            .trim(PUNCTUATION_TO_TRIM::contains)
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase(Locale.US)

        return normalized
    }

    private suspend fun resolveCanonicalKey(
        rawKey: String,
        sourceId: Long?,
        registryMatch: TagPattern?,
    ): String {
        val alias = repository.findAlias(rawKey, sourceId)
        return alias?.canonicalTag
            ?: registryMatch?.canonicalKey
            ?: depluralizeIfKnown(rawKey)
            ?: rawKey
    }

    private suspend fun depluralizeIfKnown(rawKey: String): String? {
        if (!rawKey.endsWith("s") || rawKey.length <= MIN_DEPLURALIZED_LENGTH) return null
        val singular = rawKey.dropLast(1)
        return singular.takeIf { TagRegistry.isKnown(it) || repository.aliasOrProfileExists(it) }
    }

    private suspend fun ensureDefaultAliases() {
        // compareAndSet so two concurrent canonicalize() callers do not both fire
        // the seedAliases DB write. insertAlias uses INSERT OR REPLACE so duplicate
        // writes would not corrupt data, but the doubled transaction is wasteful.
        if (!defaultsSeeded.compareAndSet(false, true)) return
        try {
            repository.seedAliases(TagRegistry.defaultAliases.map { (rawKey, canonicalTag) ->
                TagAlias(
                    rawTag = rawKey,
                    rawKey = rawKey,
                    canonicalTag = canonicalTag,
                    sourceId = null,
                    sourceKey = GLOBAL_SOURCE_KEY,
                )
            })
        } catch (e: Throwable) {
            // Reset the flag so the next canonicalize() call re-attempts; otherwise a
            // one-time DB error would permanently strand the registry without aliases.
            defaultsSeeded.set(false)
            throw e
        }
    }

    private fun String.toDisplayTag(): String =
        split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }

    private companion object {
        private const val GLOBAL_SOURCE_KEY = -1L
        private const val MIN_DEPLURALIZED_LENGTH = 4
        private val WHITESPACE = Regex("\\s+")
        private val DECORATIVE_SYMBOLS = Regex("[♀♂⚥★☆♡♥•·]")
        private val COUNT_OR_PAREN = Regex(
            "(?i)(?<=\\S)\\s*[\\[(].*?[\\])]\\s*$|\\s+\\d+(?:[.,]\\d+)?[kKmMbB]?\\s*$",
        )
        // Replace any internal punctuation that some sources use as a word separator
        // ("big-breasts", "school:life", "sci-fi") with a space so the lookup key matches
        // the canonical "space-separated" form. PUNCTUATION_TO_TRIM still strips these
        // from the edges of the raw tag.
        private val INTERNAL_PUNCTUATION = Regex("[/_\\-:;,.]")
        private val APOSTROPHES = Regex("[`'’]")
        private val PUNCTUATION_TO_TRIM = setOf(
            '-',
            '_',
            '[',
            ']',
            '(',
            ')',
            '{',
            '}',
            '<',
            '>',
            ':',
            ';',
            ',',
            '.',
            '"',
            '\'',
        )
    }
}
