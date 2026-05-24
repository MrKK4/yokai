package eu.kanade.tachiyomi.ui.suggestions

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.suggestions.COLD_START_DISCOVERY_SECTION_KEY
import yokai.domain.suggestions.SuggestionSortOrder

class SuggestionsPresenterCachePolicyTest {

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
