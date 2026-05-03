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

        val visibleProfiles = profiles
            .filterNot { it.isBlacklisted }
            .sortedByDescending { it.affinity }

        val pinned = visibleProfiles
            .filter { it.isPinned }
            .sortedWith(compareBy<TagProfile> { it.pinnedAt ?: Long.MAX_VALUE }.thenBy { it.canonicalTag })

        pinned.forEach { profile ->
            sections += tagSection(profile, SectionType.PINNED_TAG, sortOrder, now)
            debugLog.add(LogType.SECTION_SELECTED, "Tag '${profile.canonicalTag}' chosen as pinned")
        }

        visibleProfiles
            .filter { it.isManaged && it.affinity > 0.0 }
            .forEachIndexed { index, profile ->
                sections += tagSection(profile, SectionType.MANAGED_TAG, sortOrder, now)
                debugLog.add(
                    LogType.SECTION_SELECTED,
                    "Tag '${profile.canonicalTag}' chosen as managed (affinity=${profile.affinity}, rank=${index + 1})",
                )
            }

        return sections.mapIndexed { index, section -> section.copy(rank = index.toLong()) }
    }

    private fun discoverySection(sortOrder: SuggestionSortOrder, now: Long): PlannedSection {
        val reason = when (sortOrder) {
            SuggestionSortOrder.Latest -> "Latest from your sources"
            SuggestionSortOrder.Popular -> "Popular from your sources"
        }
        return PlannedSection(
            sectionKey = "discovery",
            type = SectionType.DISCOVERY,
            canonicalTag = null,
            displayReason = reason,
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
            displayReason = reasonFor(profile, sectionType),
            searchTerms = searchTerms,
            sortOrder = sortOrder,
            plannedAt = now,
        )
    }

    private fun reasonFor(profile: TagProfile, sectionType: SectionType): String =
        when (sectionType) {
            SectionType.PINNED_TAG -> "Pinned: ${profile.displayName}"
            SectionType.MANAGED_TAG ->
                when {
                    profile.affinity > HIGH_AFFINITY_THRESHOLD -> "Because you love ${profile.displayName}"
                    profile.affinity > MID_AFFINITY_THRESHOLD -> "Because you often read ${profile.displayName}"
                    else -> "Because you read ${profile.displayName}"
                }
            SectionType.DISCOVERY -> profile.displayName
        }

    private companion object {
        private const val HIGH_AFFINITY_THRESHOLD = 5.0
        private const val MID_AFFINITY_THRESHOLD = 2.0
    }
}
