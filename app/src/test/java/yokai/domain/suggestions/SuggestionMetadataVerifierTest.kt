package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SuggestionMetadataVerifierTest {

    @Test
    fun `detail metadata blocks aliased blacklisted tag`() = runBlocking {
        val source = DetailSource(
            id = 1L,
            detailGenres = "boys love, males only",
        )
        val verifier = verifierWith(source)
        val blacklist = verifier.canonicalizeBlacklist(setOf("yaoi"))

        val filtered = verifier.filterSuggestions(
            suggestions = listOf(suggestion(source = 1L, url = "/blocked")),
            blacklistedTags = blacklist,
        )

        assertEquals(emptyList<SuggestedManga>(), filtered)
        assertEquals(1, source.detailCalls)
    }

    @Test
    fun `detail failure skips unknown candidate while blacklist is active`() = runBlocking {
        val source = DetailSource(
            id = 2L,
            detailGenres = null,
            failDetails = true,
        )
        val verifier = verifierWith(source)
        val blacklist = verifier.canonicalizeBlacklist(setOf("yaoi"))

        val filtered = verifier.filterSuggestions(
            suggestions = listOf(suggestion(source = 2L, url = "/unknown")),
            blacklistedTags = blacklist,
        )

        assertEquals(emptyList<SuggestedManga>(), filtered)
        assertEquals(1, source.detailCalls)
    }

    @Test
    fun `strong list metadata does not fetch details`() = runBlocking {
        val source = DetailSource(
            id = 3L,
            detailGenres = "yaoi",
        )
        val verifier = verifierWith(source)
        val blacklist = verifier.canonicalizeBlacklist(setOf("yaoi"))
        val manga = SManga.create().apply {
            url = "/allowed"
            title = "Allowed"
            genre = "Action"
            initialized = true
        }

        val filtered = verifier.filterByBlacklist(
            items = listOf(manga),
            blacklistedTags = blacklist,
            sourceId = { 3L },
            manga = { it },
        )

        assertEquals(listOf(manga), filtered)
        assertEquals(0, source.detailCalls)
    }

    @Test
    fun `weak list metadata fetches details before allowing`() = runBlocking {
        val source = DetailSource(
            id = 4L,
            detailGenres = "boys love, males only",
        )
        val verifier = verifierWith(source)
        val blacklist = verifier.canonicalizeBlacklist(setOf("yaoi"))
        val manga = SManga.create().apply {
            url = "/weak-list"
            title = "Weak List"
            genre = "Doujinshi, English"
            initialized = true
        }

        val filtered = verifier.filterByBlacklist(
            items = listOf(manga),
            blacklistedTags = blacklist,
            sourceId = { 4L },
            manga = { it },
        )

        assertEquals(emptyList<SManga>(), filtered)
        assertEquals(1, source.detailCalls)
    }

    private fun verifierWith(source: Source): SuggestionMetadataVerifier {
        val sourceManager = mockk<SourceManager>()
        every { sourceManager.get(source.id) } returns source
        return SuggestionMetadataVerifier(
            sourceManager = sourceManager,
            tagCanonicalizer = TagCanonicalizer(FakeTagProfileRepository()),
            debugLog = SuggestionsDebugLog(),
        )
    }

    private fun suggestion(source: Long, url: String): SuggestedManga =
        SuggestedManga(
            source = source,
            url = url,
            title = "Suggestion $url",
            thumbnailUrl = null,
            sectionKey = "tag:action",
            relevanceScore = 1.0,
            displayRank = 0L,
        )
}

private class DetailSource(
    override val id: Long,
    private val detailGenres: String?,
    private val failDetails: Boolean = false,
) : Source {
    var detailCalls = 0
        private set

    override val name: String = "Detail Source $id"

    override suspend fun getMangaDetails(manga: SManga): SManga {
        detailCalls++
        if (failDetails) error("details unavailable")
        return manga.copy().apply {
            genre = detailGenres
        }
    }
}
