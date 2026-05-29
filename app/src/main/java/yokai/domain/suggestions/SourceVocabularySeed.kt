package yokai.domain.suggestions

/**
 * Hardcoded seed entries for `tag_alias` keyed by source name.
 *
 * Why this exists:
 *  - `CandidateRetriever.fetchSearchSource` calls `getExactTermForSource(canonicalTag,
 *    sourceId)` to pick the raw query string a given source expects for a canonical tag.
 *  - That lookup is empty on cold start until `learnVocabulary` populates rows from
 *    actual fetch results. For sources whose extension maps `getSearchManga("milf", …)`
 *    to a text-search index that does not contain "milf" (e.g. it indexes "m.i.l.f"),
 *    the first fetch returns 0 results, no genres come back, `learnVocabulary` records
 *    nothing, and the source stays silent forever.
 *
 * Each entry says "for source matching this name, when you want canonical tag X, send
 * the raw query Y". The seeder writes one [TagAlias] per (sourceId, canonical, raw)
 * triple at first refresh; subsequent runs are idempotent.
 *
 * How to add an entry:
 *  1. Run the app with Chucker enabled.
 *  2. Trigger a Suggestions refresh that includes the problematic tag section.
 *  3. Inspect outbound HTTP for each source. If a source returns 0 results for the
 *     canonical query string, find its tag URL by visiting the site manually
 *     (e.g. `hentaihand.com/tag/m-i-l-f/`) and read the slug from the URL.
 *  4. Append the (canonical → raw slug) entry under the source's name below.
 *
 * Name match is case-insensitive and ignores punctuation/whitespace, so "HentaiHand",
 * "hentai-hand", and "Hentai Hand" all collide on the same key.
 */
object SourceVocabularySeed {
    /**
     * Outer map: normalized source name → (canonical tag → raw query the source wants).
     *
     * Keep entries minimal and evidence-backed. A wrong entry will permanently send the
     * wrong query and silently zero-out the section — worse than no entry at all,
     * because `learnVocabulary` writes are idempotent and won't overwrite this seed.
     *
     * Tie-breaker quirk: `findRawTagsBySourceAndCanonical` returns the first row
     * sorted by `raw_tag COLLATE NOCASE ASC`. When `learnVocabulary` later writes a
     * second alias for the same (source, canonical), the lex-earlier raw_tag wins.
     * Most static seeds (e.g. "m.i.l.f", "big-breasts") sort before any natural-text
     * variant ("Milf", "Big Breasts") because punctuation < letters in ASCII. If you
     * need to seed a raw query that begins with a letter past whatever the source
     * actually returns in its genre list, it can be shadowed — verify post-fetch via
     * the debug log.
     */
    private val byNormalizedSourceName: Map<String, Map<String, String>> = mapOf(
        // These HentaiHand-theme sources currently expose a free-text tag filter
        // whose internal tag-id resolver expects `id` as a String, while the API
        // returns it as a number. Seeding source-specific raw terms lets
        // CandidateRetriever use plain search instead of the broken resolver path.
        "hentaihand" to mapOf(
            "milf" to "m.i.l.f",
            "big breasts" to "big-breasts",
        ),
        "nhentaicom" to mapOf(
            "milf" to "milf",
            "big breasts" to "big-breasts",
        ),
        "nhentai" to mapOf(
            "milf" to "milf",
            "big breasts" to "big-breasts",
        ),
    )

    /**
     * Returns the (canonical → raw query) map for a source name, or null if no seed
     * is defined.
     */
    fun seedFor(sourceName: String): Map<String, String>? =
        byNormalizedSourceName[normalize(sourceName)]

    /** Total seed entries across all sources. Useful for debug logging. */
    fun totalEntries(): Int =
        byNormalizedSourceName.values.sumOf { it.size }

    private fun normalize(name: String): String =
        name.lowercase().replace(NON_ALNUM, "")

    private val NON_ALNUM = Regex("[^a-z0-9]+")
}
