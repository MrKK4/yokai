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
}
