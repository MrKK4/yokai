package yokai.data.suggestions

import yokai.data.DatabaseHandler
import yokai.domain.suggestions.PlannedSection
import yokai.domain.suggestions.PlannedSectionRepository
import yokai.domain.suggestions.SectionType
import yokai.domain.suggestions.SuggestionSortOrder

class PlannedSectionRepositoryImpl(
    private val handler: DatabaseHandler,
) : PlannedSectionRepository {

    override suspend fun getPlannedSections(): List<PlannedSection> =
        handler.awaitList { suggestion_planned_sectionQueries.findAll(::mapPlannedSection) }

    override suspend fun replaceAll(sections: List<PlannedSection>) {
        handler.await(inTransaction = true) {
            suggestion_planned_sectionQueries.deleteAll()
            sections.forEach { section ->
                suggestion_planned_sectionQueries.replaceSection(
                    sectionKey = section.sectionKey,
                    sectionRank = section.rank,
                    sectionType = section.type.name,
                    canonicalTag = section.canonicalTag,
                    displayReason = section.displayReason,
                    searchTerms = section.searchTerms.joinToString(SEARCH_TERM_SEPARATOR),
                    sortOrder = section.sortOrder.name,
                    sortFallback = section.sortFallback,
                    plannedAt = section.plannedAt,
                )
            }
        }
    }

    override suspend fun deleteAll() {
        handler.await { suggestion_planned_sectionQueries.deleteAll() }
    }

    private fun mapPlannedSection(
        sectionKey: String,
        sectionRank: Long,
        sectionType: String,
        canonicalTag: String?,
        displayReason: String,
        searchTerms: String,
        sortOrder: String,
        sortFallback: Boolean,
        plannedAt: Long,
    ): PlannedSection =
        PlannedSection(
            sectionKey = sectionKey,
            type = sectionType.toSectionType(),
            canonicalTag = canonicalTag,
            displayReason = displayReason,
            searchTerms = searchTerms
                .split(SEARCH_TERM_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotBlank() },
            sortOrder = runCatching { SuggestionSortOrder.valueOf(sortOrder) }.getOrDefault(SuggestionSortOrder.Popular),
            sortFallback = sortFallback,
            rank = sectionRank,
            plannedAt = plannedAt,
        )

    private companion object {
        private const val SEARCH_TERM_SEPARATOR = "\u001F"

        private fun String.toSectionType(): SectionType =
            when (this) {
                "GUARANTEED_TAG", "ROTATING_TAG" -> SectionType.MANAGED_TAG
                else -> runCatching { SectionType.valueOf(this) }.getOrDefault(SectionType.DISCOVERY)
            }
    }
}
