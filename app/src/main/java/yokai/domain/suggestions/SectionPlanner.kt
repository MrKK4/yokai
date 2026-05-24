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
        // Keep labels short. Long sentences like "Latest from your sources" used to truncate
        // when paired with wide tag chips on smaller screens. Icons carry the intent.
        val displayReason = when (sortOrder) {
            SuggestionSortOrder.Latest -> "🆕 Latest"
            SuggestionSortOrder.Popular -> "🔥 Popular"
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

    // Affinity still drives section ordering (high-affinity tags sort first), but the
    // visible label stays short and identical across tiers. Long "Because you …" sentences
    // would push wide tag names off-screen on smaller devices.
    private fun reasonFor(profile: TagProfile): String =
        "⭐ ${profile.displayName}"
}
