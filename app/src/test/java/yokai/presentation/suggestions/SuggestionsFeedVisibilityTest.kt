package yokai.presentation.suggestions

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionsFeedVisibilityTest {

    @Test
    fun `empty planned section does not show grid`() {
        assertFalse(
            shouldShowSuggestionsGrid(
                hasVisibleSuggestions = false,
                hasLoadingPlannedSection = false,
            ),
        )
    }

    @Test
    fun `loading planned section shows grid skeleton`() {
        assertTrue(
            shouldShowSuggestionsGrid(
                hasVisibleSuggestions = false,
                hasLoadingPlannedSection = true,
            ),
        )
    }

    @Test
    fun `loaded suggestions show grid`() {
        assertTrue(
            shouldShowSuggestionsGrid(
                hasVisibleSuggestions = true,
                hasLoadingPlannedSection = false,
            ),
        )
    }
}
