package yokai.domain.suggestions

object SuggestionsModeStoragePolicy {
    fun shouldClearStoredResults(
        isV2Enabled: Boolean,
        storedResultVersion: Int,
        storedSuggestionCount: Long,
        plannedSectionCount: Int,
        hasInvalidSectionKeys: Boolean = false,
    ): Boolean {
        if (storedSuggestionCount <= 0L) return false
        if (hasInvalidSectionKeys) return true

        val expectedVersion = if (isV2Enabled) {
            SuggestionsConfig.RESULT_VERSION_V2
        } else {
            SuggestionsConfig.RESULT_VERSION_V1
        }
        if (storedResultVersion != expectedVersion) return true

        return false
    }
}
