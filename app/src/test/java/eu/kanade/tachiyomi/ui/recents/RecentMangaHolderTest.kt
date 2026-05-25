package eu.kanade.tachiyomi.ui.recents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecentMangaHolderTest {

    @Test
    fun `history reset button is visible only for history items when enabled`() {
        assertTrue(
            shouldShowHistoryResetButton(
                viewType = RecentsViewType.History,
                showRemoveHistory = true,
                historyId = 1L,
            ),
        )
    }

    @Test
    fun `history reset button is hidden outside history tab`() {
        assertFalse(
            shouldShowHistoryResetButton(
                viewType = RecentsViewType.Updates,
                showRemoveHistory = true,
                historyId = 1L,
            ),
        )
    }

    @Test
    fun `history reset button is hidden when the item has no history row`() {
        assertFalse(
            shouldShowHistoryResetButton(
                viewType = RecentsViewType.History,
                showRemoveHistory = true,
                historyId = null,
            ),
        )
    }

    @Test
    fun `history long press surfaces library action only`() {
        // History removal lives on the per-card eye-minus button; the long-press
        // menu intentionally does not duplicate it. The library toggle is still
        // available so the user can add an interesting history entry to library
        // without navigating into the manga details screen.
        val actions = recentMangaLongPressActions(
            viewType = RecentsViewType.History,
            isFavorite = false,
            historyId = 1L,
        )

        assertTrue(RecentMangaLongPressAction.AddToLibrary in actions)
        assertEquals(1, actions.size)
    }

    @Test
    fun `favorited history long press shows remove from library`() {
        val actions = recentMangaLongPressActions(
            viewType = RecentsViewType.History,
            isFavorite = true,
            historyId = 1L,
        )

        assertTrue(RecentMangaLongPressAction.RemoveFromLibrary in actions)
        assertFalse(RecentMangaLongPressAction.AddToLibrary in actions)
    }

    @Test
    fun `non history long press surfaces only the library action`() {
        val actions = recentMangaLongPressActions(
            viewType = RecentsViewType.Updates,
            isFavorite = false,
            historyId = 1L,
        )

        assertTrue(RecentMangaLongPressAction.AddToLibrary in actions)
        assertEquals(1, actions.size)
    }
}
