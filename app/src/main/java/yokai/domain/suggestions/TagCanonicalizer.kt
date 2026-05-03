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
        repository.seedAliases(DEFAULT_ALIASES.map { (rawTag, canonicalTag) ->
            TagAlias(
                rawTag = rawTag,
                rawKey = normalizeToLookupKey(rawTag),
                canonicalTag = normalizeToLookupKey(canonicalTag),
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

    companion object {
        private const val GLOBAL_SOURCE_KEY = -1L
        private const val MIN_DEPLURALIZED_LENGTH = 4
        private val WHITESPACE = Regex("\\s+")
        private val DECORATIVE_SYMBOLS = Regex("[♀♂⚥★☆♡♥•·]")
        private val PUNCTUATION_TO_TRIM = setOf('-', '_', '[', ']', '(', ')', '{', '}', '<', '>', ':', ';', ',', '.', '"', '\'')

        val DEFAULT_ALIASES: List<Pair<String, String>> = listOf(
            "mother ♀" to "mother",
            "mother♀" to "mother",
            "mom" to "mother",
            "stepmom" to "mother",
            "step-mom" to "mother",
            "step mom" to "mother",
            "stepmother" to "mother",
            "step-mother" to "mother",
            "step mother" to "mother",
            "milf ♀" to "milf",
            "milf♀" to "milf",
            "milfs" to "milf",
            "milves" to "milf",
            "m.i.l.f" to "milf",
            "m.i.l.f." to "milf",
            "romance ♥" to "romance",
            "romance♥" to "romance",
            "romcom" to "romance",
            "rom-com" to "romance",
            "romantic comedy" to "romance",
            "love story" to "romance",
            "love stories" to "romance",
            "action ⚔" to "action",
            "action⚔" to "action",
            "fantasy ✦" to "fantasy",
            "fantasy✦" to "fantasy",
            "high fantasy" to "fantasy",
            "dark fantasy" to "fantasy",
            "isekai" to "isekai",
            "reincarnation" to "isekai",
            "transferred to another world" to "isekai",
            "transported to another world" to "isekai",
            "summoned to another world" to "isekai",
            "tensei" to "isekai",
            "sci fi" to "science fiction",
            "sci-fi" to "science fiction",
            "scifi" to "science fiction",
            "sf" to "science fiction",
            "slice-of-life" to "slice of life",
            "slice of life ☀" to "slice of life",
            "slice of life" to "slice of life",
            "daily life" to "slice of life",
            "school-life" to "school life",
            "high school" to "school life",
            "academy" to "school life",
            "bl" to "boys love",
            "yaoi" to "boys love",
            "shounen ai" to "boys love",
            "shonen ai" to "boys love",
            "gl" to "girls love",
            "yuri" to "girls love",
            "shoujo ai" to "girls love",
            "shojo ai" to "girls love",
            "shounen" to "shonen",
            "shoujo" to "shojo",
            "harem ♥" to "harem",
            "harem♥" to "harem",
            "harem (male protagonist)" to "harem",
            "reverse-harem" to "reverse harem",
            "reverse harem ♥" to "reverse harem",
            "comedy ☺" to "comedy",
            "comedy☺" to "comedy",
            "humor" to "comedy",
            "humour" to "comedy",
            "horror ☠" to "horror",
            "horror☠" to "horror",
            "mystery ♟" to "mystery",
            "detective" to "mystery",
            "super natural" to "supernatural",
            "super-natural" to "supernatural",
            "psycho" to "psychological",
            "psych" to "psychological",
            "mature ♀" to "mature",
            "mature♀" to "mature",
            "adult" to "mature",
            "drama ♟" to "drama",
            "drama♟" to "drama",
            "martialarts" to "martial arts",
            "martial-arts" to "martial arts",
            "kung fu" to "martial arts",
            "video game" to "game",
            "video games" to "game",
            "gaming" to "game",
            "villain" to "villainess",
            "villainess" to "villainess",
            "office romance" to "office",
            "office lady" to "office",
            "salaryman" to "office",
        )
    }
}
