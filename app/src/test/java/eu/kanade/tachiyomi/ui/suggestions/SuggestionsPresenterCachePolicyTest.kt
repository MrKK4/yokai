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
