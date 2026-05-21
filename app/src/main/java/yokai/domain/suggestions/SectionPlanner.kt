package yokai.domain.suggestions

class SectionPlanner(
    private val tagProfileRepository: TagProfileRepository,
    private val debugLog: SuggestionsDebugLog,
) {
    suspend fun plan(
        profiles: List<TagProfile>,
        sortOrder: SuggestionSortOrder,
        now: Long = System.currentTimeMillis(),
    ): List<PlannedSection> {
        val sections = mutableListOf<PlannedSection>()
        sections += discoverySection(sortOrder, now)

        // Pin functionality lives entirely in the V1 path (pref-driven queries). V2 only
        // ranks tags by inferred affinity from reading history.
        val visibleProfiles = SuggestionProfilePlanner.precise(profiles)

        if (visibleProfiles.isEmpty()) {
            return listOf(discoverySection(sortOrder, now, coldStart = true))
        }

        visibleProfiles
            .forEachIndexed { index, profile ->
                sections += tagSection(profile, SectionType.MANAGED_TAG, sortOrder, now)
                debugLog.add(
                    LogType.SECTION_SELECTED,
                    "Tag '${profile.canonicalTag}' chosen as managed (affinity=${profile.affinity}, rank=${index + 1})",
                )
            }

        return sections.mapIndexed { index, section -> section.copy(rank = index.toLong()) }
    }

    private fun discoverySection(
        sortOrder: SuggestionSortOrder,
        now: Long,
        coldStart: Boolean = false,
    ): PlannedSection {
        val displayReason = when (sortOrder) {
            SuggestionSortOrder.Latest -> "Latest from your sources"
            SuggestionSortOrder.Popular -> "Popular from your sources"
        }
        return PlannedSection(
            sectionKey = if (coldStart) COLD_START_DISCOVERY_SECTION_KEY else "discovery",
            type = SectionType.DISCOVERY,
            canonicalTag = null,
            displayReason = displayReason,
            searchTerms = emptyList(),
            sortOrder = sortOrder,
            plannedAt = now,
        )
    }

    private suspend fun tagSection(
        profile: TagProfile,
        sectionType: SectionType,
        sortOrder: SuggestionSortOrder,
        now: Long,
    ): PlannedSection {
        val searchTerms = tagProfileRepository.getSearchTerms(profile.canonicalTag)
            .ifEmpty { listOf(profile.displayName, profile.canonicalTag) }
            .distinctBy { it.lowercase() }

        return PlannedSection(
            sectionKey = "tag:${profile.canonicalTag}",
            type = sectionType,
            canonicalTag = profile.canonicalTag,
            displayReason = reasonFor(profile),
            searchTerms = searchTerms,
            sortOrder = sortOrder,
            plannedAt = now,
        )
    }

    private fun reasonFor(profile: TagProfile): String = when {
        profile.affinity > HIGH_AFFINITY_THRESHOLD -> "Because you love ${profile.displayName}"
        profile.affinity > MID_AFFINITY_THRESHOLD -> "Because you often read ${profile.displayName}"
        else -> "Because you read ${profile.displayName}"
    }

    private companion object {
        private const val HIGH_AFFINITY_THRESHOLD = 5.0
        private const val MID_AFFINITY_THRESHOLD = 2.0
    }
}
