package eu.kanade.tachiyomi.ui.suggestions

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
