package eu.kanade.tachiyomi.ui.recents

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
    fun `history long press shows library and history actions`() {
        val actions = recentMangaLongPressActions(
            viewType = RecentsViewType.History,
            isFavorite = false,
            historyId = 1L,
        )

        assertTrue(RecentMangaLongPressAction.AddToLibrary in actions)
        assertTrue(RecentMangaLongPressAction.RemoveFromHistory in actions)
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
    fun `non history long press does not show history removal`() {
        val actions = recentMangaLongPressActions(
            viewType = RecentsViewType.Updates,
            isFavorite = false,
            historyId = 1L,
        )

        assertFalse(RecentMangaLongPressAction.RemoveFromHistory in actions)
    }
}
