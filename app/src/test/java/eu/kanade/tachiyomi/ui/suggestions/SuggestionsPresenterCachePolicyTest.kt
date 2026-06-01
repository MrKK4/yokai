package eu.kanade.tachiyomi.ui.suggestions

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.suggestions.COLD_START_DISCOVERY_SECTION_KEY
import yokai.domain.suggestions.SectionType
import yokai.domain.suggestions.SuggestedManga
import yokai.domain.suggestions.SuggestionSortOrder

class SuggestionsPresenterCachePolicyTest {

    private fun sm(source: Long, url: String, title: String = url, score: Double = 0.0) =
        SuggestedManga(source = source, url = url, title = title, thumbnailUrl = null, sectionKey = "tag:x", relevanceScore = score)

    @Test
    fun `cache-partial seed is shown first then network fills the target`() {
        val cached = listOf(sm(1, "a"), sm(2, "b"), sm(3, "c"))
        val networkShown = (1..9).map { sm(10L + it, "n$it") }
        val networkSurplus = listOf(sm(99, "s"))

        val merged = mergeCachedSeedWithRanked(cached, networkShown, networkSurplus, target = 9)

        assertEquals(9, merged.shown.size)
        assertEquals(listOf("a", "b", "c"), merged.shown.take(3).map { it.url }, "cached seed shown first")
        // 3 cached + 6 network shown = 9; leftover 3 network shown + 1 surplus = 4 re-cached
        assertEquals(4, merged.surplus.size)
    }

    @Test
    fun `merge dedups overlap by source-url and title`() {
        val cached = listOf(sm(1, "a", title = "Same Title"))
        val network = listOf(sm(1, "a", title = "Same Title"), sm(2, "b", title = "same  title"), sm(3, "c"))

        val merged = mergeCachedSeedWithRanked(cached, network, emptyList(), target = 9)

        // (1,a) exact dup dropped; (2,b) is same normalized title as cached → dropped; only a + c
        assertEquals(listOf("a", "c"), merged.shown.map { it.url })
    }

    @Test
    fun `stored suggestions do not auto refresh before stale window`() {
        assertFalse(
            shouldAutoRefreshStoredSuggestions(
                newestFetchedAt = 1_000L,
                now = 1_499L,
                staleAfterMs = 500L,
            ),
        )
    }

    @Test
    fun `stored suggestions auto refresh when stale window is reached`() {
        assertTrue(
            shouldAutoRefreshStoredSuggestions(
                newestFetchedAt = 1_000L,
                now = 1_500L,
                staleAfterMs = 500L,
            ),
        )
    }

    @Test
    fun `missing stored suggestions do not auto refresh through stale policy`() {
        assertFalse(
            shouldAutoRefreshStoredSuggestions(
                newestFetchedAt = null,
                now = 1_500L,
                staleAfterMs = 500L,
            ),
        )
    }

    @Test
    fun `foreground refresh with rendered suggestions does not become full page loading`() {
        assertFalse(
            shouldShowFullPageRefreshLoading(
                isForegroundRefreshing = true,
                hasRenderedSuggestions = true,
            ),
        )
    }

    @Test
    fun `foreground refresh without rendered suggestions can show loading skeleton`() {
        assertTrue(
            shouldShowFullPageRefreshLoading(
                isForegroundRefreshing = true,
                hasRenderedSuggestions = false,
            ),
        )
    }

    @Test
    fun `refresh lock timeout never shows blocking empty page message`() {
        // The foreground refresh path no longer waits on SuggestionsRefreshCoordinator,
        // so the "another refresh is still running" branch is now unreachable. If this
        // ever flips to true, the user-visible regression is the message reappearing
        // when a pull-to-refresh races a background pagination call.
        assertFalse(shouldShowBlockingRefreshLockMessage())
    }

    @Test
    fun `explicit section refresh targets requested section before visible section`() {
        assertEquals(
            "tag:big breasts",
            resolveManualRefreshTargetSectionKey(
                explicitSectionKey = "tag:big breasts",
                selectedSectionKey = null,
                visibleSectionKey = "discovery",
                loadedSectionKeys = setOf("discovery", "tag:milf", "tag:big breasts"),
                plannedSectionKeys = setOf("discovery", "tag:milf", "tag:big breasts"),
            ),
        )
    }

    @Test
    fun `pull refresh still falls back to visible section when no explicit section is requested`() {
        assertEquals(
            "tag:milf",
            resolveManualRefreshTargetSectionKey(
                explicitSectionKey = null,
                selectedSectionKey = null,
                visibleSectionKey = "tag:milf",
                loadedSectionKeys = setOf("discovery", "tag:milf"),
                plannedSectionKeys = setOf("discovery", "tag:milf", "tag:big breasts"),
            ),
        )
    }

    @Test
    fun `explicit section refresh selects only the requested section for network refresh`() {
        assertEquals(
            listOf("tag:solo male"),
            selectSoftRefreshSectionKeys(
                plannedSectionKeys = listOf("discovery", "tag:big breasts", "tag:milf", "tag:solo male"),
                previouslyLoadedCount = 3,
                refreshTargetSectionKey = "tag:solo male",
            ),
        )
    }

    @Test
    fun `tag section refresh anchors at page one instead of a random deep page`() {
        // A random deep page (e.g. 5) of a native tag is sparse/already-seen and starves
        // even hugely popular tags like 'big breasts'. Tag refreshes must use page 1.
        assertEquals(1, refreshPageOffsetForSection(SectionType.MANAGED_TAG, discoveryPageOffset = 5))
        assertEquals(1, refreshPageOffsetForSection(SectionType.MANAGED_TAG, discoveryPageOffset = 1))
    }

    @Test
    fun `discovery section refresh keeps rotating the deep page for variety`() {
        assertEquals(5, refreshPageOffsetForSection(SectionType.DISCOVERY, discoveryPageOffset = 5))
    }

    @Test
    fun `normal pull refresh keeps refreshing the loaded top sections`() {
        assertEquals(
            listOf("discovery", "tag:big breasts", "tag:milf"),
            selectSoftRefreshSectionKeys(
                plannedSectionKeys = listOf("discovery", "tag:big breasts", "tag:milf", "tag:solo male"),
                previouslyLoadedCount = 3,
                refreshTargetSectionKey = null,
            ),
        )
    }

    @Test
    fun `expanded sheet dismiss closes visible sheet`() {
        assertTrue(shouldCloseExpandedSheetOnDismiss(sheetSuppressed = false))
    }

    @Test
    fun `expanded sheet dismiss is ignored while suppressed for navigation`() {
        assertFalse(shouldCloseExpandedSheetOnDismiss(sheetSuppressed = true))
    }

    @Test
    fun `v1 popular section renders with fire icon`() {
        assertEquals("🔥 Popular", sectionKeyToV1DisplayName("popular"))
    }

    @Test
    fun `v1 latest section renders with new icon`() {
        assertEquals("🆕 Latest", sectionKeyToV1DisplayName("latest"))
    }

    @Test
    fun `v1 affinity tag section renders with star icon and capitalization`() {
        assertEquals("⭐ Romance", sectionKeyToV1DisplayName("tag:romance"))
    }

    @Test
    fun `v1 pinned tag section renders with pin icon and no prefix word`() {
        assertEquals("📌 Mecha", sectionKeyToV1DisplayName("pinned:mecha"))
    }

    @Test
    fun `v1 saved search section renders with magnifier icon and no prefix word`() {
        assertEquals("🔍 Cyberpunk", sectionKeyToV1DisplayName("search:cyberpunk"))
    }

    @Test
    fun `v1 multi-word tag is title-cased`() {
        assertEquals("⭐ Slice Of Life", sectionKeyToV1DisplayName("tag:slice of life"))
    }

    @Test
    fun `v1 expanded section keeps capitalized tag without icon for sheet header`() {
        assertEquals("Romance", sectionKeyToV1DisplayName("expanded:romance"))
    }

    @Test
    fun `v1 unknown section key falls through unchanged`() {
        assertEquals("custom_key_x", sectionKeyToV1DisplayName("custom_key_x"))
    }

    @Test
    fun `source sections are expandable with source list sort order`() {
        assertEquals(
            SuggestionSortOrder.Popular,
            sourceSortOrderForExpandableSection("popular", SuggestionSortOrder.Latest),
        )
        assertEquals(
            SuggestionSortOrder.Latest,
            sourceSortOrderForExpandableSection("latest", SuggestionSortOrder.Popular),
        )
        assertEquals(
            SuggestionSortOrder.Latest,
            sourceSortOrderForExpandableSection("discovery", SuggestionSortOrder.Latest),
        )
        assertEquals(
            SuggestionSortOrder.Popular,
            sourceSortOrderForExpandableSection(COLD_START_DISCOVERY_SECTION_KEY, SuggestionSortOrder.Popular),
        )
        assertNull(sourceSortOrderForExpandableSection("tag:romance", SuggestionSortOrder.Popular))
    }
}
