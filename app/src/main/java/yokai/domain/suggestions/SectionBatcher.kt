package yokai.domain.suggestions

object SectionBatcher {
    fun nextBatch(
        plannedSections: List<PlannedSection>,
        startIndex: Int,
        batchSize: Int = SuggestionsConfig.SECTION_BATCH_SIZE,
    ): List<PlannedSection> {
        if (startIndex < 0 || startIndex >= plannedSections.size || batchSize <= 0) {
            return emptyList()
        }
        return plannedSections.drop(startIndex).take(batchSize)
    }

    fun shouldLoadMore(
        lastVisibleSectionIndex: Int,
        loadedSectionCount: Int,
        isFetchingBatch: Boolean,
        allSectionsLoaded: Boolean,
        threshold: Int = SuggestionsConfig.LOAD_MORE_SECTION_THRESHOLD,
    ): Boolean {
        if (isFetchingBatch || allSectionsLoaded || loadedSectionCount <= 0) return false
        if (lastVisibleSectionIndex < 0) return false
        return lastVisibleSectionIndex >= loadedSectionCount - threshold
    }
}
