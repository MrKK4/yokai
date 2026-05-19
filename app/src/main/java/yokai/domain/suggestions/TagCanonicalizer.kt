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
        if (defaultsSeeded.get()) return
        repository.seedAliases(TagRegistry.defaultAliases.map { (rawKey, canonicalTag) ->
            TagAlias(
                rawTag = rawKey,
                rawKey = rawKey,
                canonicalTag = canonicalTag,
                sourceId = null,
                sourceKey = GLOBAL_SOURCE_KEY,
            )
        })
        defaultsSeeded.set(true)
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
        private val INTERNAL_PUNCTUATION = Regex("[/_]")
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
