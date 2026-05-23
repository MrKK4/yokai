package yokai.domain.suggestions

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionsModeStoragePolicyTest {

    @Test
    fun `clears stored rows when stored version does not match selected mode`() {
        assertTrue(
            SuggestionsModeStoragePolicy.shouldClearStoredResults(
                isV2Enabled = true,
                storedResultVersion = SuggestionsConfig.RESULT_VERSION_V1,
                storedSuggestionCount = 12L,
                plannedSectionCount = 3,
            ),
        )
    }

    @Test
    fun `keeps V2 rows when planned sections are missing so a half-completed refresh can resume without wiping cache`() {
        // A process kill mid-rebuild can leave planned_section table empty while stored
        // suggestions remain valid; the next refresh should re-populate plans, not wipe rows.
        assertFalse(
            SuggestionsModeStoragePolicy.shouldClearStoredResults(
                isV2Enabled = true,
                storedResultVersion = SuggestionsConfig.RESULT_VERSION_V2,
                storedSuggestionCount = 12L,
                plannedSectionCount = 0,
            ),
        )
    }

    @Test
    fun `clears rows migrated without stable section keys`() {
        assertTrue(
            SuggestionsModeStoragePolicy.shouldClearStoredResults(
                isV2Enabled = false,
                storedResultVersion = SuggestionsConfig.RESULT_VERSION_V1,
                storedSuggestionCount = 12L,
                plannedSectionCount = 0,
                hasInvalidSectionKeys = true,
            ),
        )
    }

    @Test
    fun `keeps matching V2 rows when planned sections exist`() {
        assertFalse(
            SuggestionsModeStoragePolicy.shouldClearStoredResults(
                isV2Enabled = true,
                storedResultVersion = SuggestionsConfig.RESULT_VERSION_V2,
                storedSuggestionCount = 12L,
                plannedSectionCount = 1,
            ),
        )
    }

    @Test
    fun `does not clear empty storage`() {
        assertFalse(
            SuggestionsModeStoragePolicy.shouldClearStoredResults(
                isV2Enabled = true,
                storedResultVersion = SuggestionsConfig.RESULT_VERSION_V1,
                storedSuggestionCount = 0L,
                plannedSectionCount = 0,
            ),
        )
    }
}
