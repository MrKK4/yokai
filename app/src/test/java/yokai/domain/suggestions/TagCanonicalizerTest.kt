package yokai.domain.suggestions

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TagCanonicalizerTest {

    @Test
    fun `canonicalize applies formatting cleanup and aliases`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)

        assertEquals("mother", canonicalizer.canonicalize("  Mother ♀ ").canonicalKey)
        assertEquals("milf", canonicalizer.canonicalize("Milfs").canonicalKey)
        assertEquals("science fiction", canonicalizer.canonicalize("ＳＣＩ－ＦＩ").canonicalKey)
        assertEquals("slice of life", canonicalizer.canonicalize("[Slice-of-Life]").canonicalKey)
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
    fun `canonicalize uses most frequent raw variant as display name`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val canonicalizer = TagCanonicalizer(repository)

        canonicalizer.canonicalize("Romance")
        canonicalizer.canonicalize("romance")
        val result = canonicalizer.canonicalize("romance")

        assertEquals("romance", result.displayName)
    }
}
