package yokai.domain.suggestions

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TagCanonicalizerTest {

    @Test
    fun `canonicalize applies formatting cleanup and aliases`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)

        assertEquals("mother", canonicalizer.canonicalize("  Mother ♀ 1.5M ").canonicalKey)
        assertEquals("milf", canonicalizer.canonicalize("Milfs").canonicalKey)
        assertEquals("science fiction", canonicalizer.canonicalize("ＳＣＩ－ＦＩ").canonicalKey)
        assertEquals("slice of life", canonicalizer.canonicalize("[Slice-of-Life]").canonicalKey)
    }

    @Test
    fun `canonicalize applies exact prefix and regex registry matches`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)

        assertEquals("shonen", canonicalizer.canonicalize("Shounen(B)").canonicalKey)
        assertEquals("girls love", canonicalizer.canonicalize("Shoujo Ai").canonicalKey)
        assertEquals("boys love", canonicalizer.canonicalize("Shounen-Ai").canonicalKey)
        assertEquals("oneshot", canonicalizer.canonicalize("one-shot").canonicalKey)
        assertEquals("milf", canonicalizer.canonicalize("M.I.L.F.").canonicalKey)
    }

    @Test
    fun `canonicalize gives source alias priority over registry`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)

        repository.seedAliases(
            listOf(
                TagAlias(
                    rawTag = "GL",
                    rawKey = "gl",
                    canonicalTag = "gaming league",
                    sourceId = 42L,
                    sourceKey = 42L,
                ),
            ),
        )

        assertEquals("gaming league", canonicalizer.canonicalize("GL", sourceId = 42L).canonicalKey)
        assertEquals("girls love", canonicalizer.canonicalize("GL").canonicalKey)
    }

    @Test
    fun `canonicalize only depluralizes when singular form is known`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)

        repository.upsertProfile(profile("romance"))

        assertEquals("romance", canonicalizer.canonicalize("romances").canonicalKey)
        assertEquals("class", canonicalizer.canonicalize("class").canonicalKey)
    }

    @Test
    fun `canonicalize leaves unknown tags raw`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)

        assertEquals("quiet tea club", canonicalizer.canonicalize("Quiet Tea Club").canonicalKey)
    }

    @Test
    fun `canonicalize uses most frequent raw variant as display name`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)

        canonicalizer.canonicalize("Romance")
        canonicalizer.canonicalize("romance")
        val result = canonicalizer.canonicalize("romance")

        assertEquals("romance", result.displayName)
    }
}
