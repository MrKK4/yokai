package yokai.domain.suggestions

import kotlin.random.Random

class SectionPlanner(
    private val tagProfileRepository: TagProfileRepository,
    private val debugLog: SuggestionsDebugLog,
    private val random: Random = Random.Default,
) {
    suspend fun plan(
        profiles: List<TagProfile>,
        sortOrder: SuggestionSortOrder,
        savedSearches: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
        refreshIntervalMillis: Long = SuggestionsConfig.DEFAULT_REFRESH_INTERVAL_MS,
        applyCooldown: Boolean = true,
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

        val managed = visibleProfiles.filter { it.isManaged }
        val guaranteedSlots = (SuggestionsConfig.GUARANTEED_SLOTS - pinned.size).coerceAtLeast(0)
        val nonCooldownManaged = managed.filterNot { it.isOnCooldown(now) }
        val guaranteed = if (guaranteedSlots == 0) {
            emptyList()
        } else {
            val preferred = nonCooldownManaged.take(guaranteedSlots)
            if (preferred.size == guaranteedSlots) {
                preferred
            } else {
                (preferred + managed.filterNot { candidate -> candidate in preferred })
                    .take(guaranteedSlots)
                    .also {
                        if (preferred.size < guaranteedSlots && managed.isNotEmpty()) {
                            debugLog.add(LogType.SECTION_SELECTED, "Cooldown skipped for top managed tags to fill guaranteed slots")
                        }
                    }
            }
        }

        guaranteed.forEachIndexed { index, profile ->
            sections += tagSection(profile, SectionType.GUARANTEED_TAG, sortOrder, now)
            debugLog.add(
                LogType.SECTION_SELECTED,
                "Tag '${profile.canonicalTag}' chosen as guaranteed (affinity=${profile.affinity}, rank=${index + 1})",
            )
        }

        managed
            .filter { it !in guaranteed }
            .filter { it.isOnCooldown(now) }
            .forEach { profile ->
                debugLog.add(LogType.SECTION_DROPPED, "Tag '${profile.canonicalTag}' skipped - on cooldown until ${profile.cooldownUntil}")
            }

        val rotatingCandidates = managed
            .filter { it !in guaranteed }
            .filterNot { it.isOnCooldown(now) }
            .shuffled(random)

        val rotatingSlots = rotatingSlotCount(rotatingCandidates.size)
        val rotating = rotatingCandidates.take(rotatingSlots)
        val cooldownUntil = now + refreshIntervalMillis * SuggestionsConfig.COOLDOWN_MULTIPLIER
        rotating.forEach { profile ->
            sections += tagSection(profile, SectionType.ROTATING_TAG, sortOrder, now)
            if (applyCooldown) {
                tagProfileRepository.updateCooldown(profile.canonicalTag, cooldownUntil, now)
            }
            debugLog.add(
                LogType.SECTION_SELECTED,
                "Tag '${profile.canonicalTag}' chosen as rotating (affinity=${profile.affinity}, cooldown until $cooldownUntil)",
            )
        }

        savedSearches
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_SAVED_SEARCH_SECTIONS)
            .forEach { query ->
                sections += PlannedSection(
                    sectionKey = "search:${query.lowercase()}",
                    type = SectionType.SAVED_SEARCH,
                    canonicalTag = null,
                    displayReason = "Because you searched \"$query\"",
                    searchTerms = listOf(query),
                    sortOrder = sortOrder,
                    plannedAt = now,
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
            SectionType.GUARANTEED_TAG ->
                when {
                    profile.affinity > HIGH_AFFINITY_THRESHOLD -> "Because you love ${profile.displayName}"
                    profile.affinity > MID_AFFINITY_THRESHOLD -> "Because you often read ${profile.displayName}"
                    else -> "Because you read ${profile.displayName}"
                }
            SectionType.ROTATING_TAG -> "Because you read ${profile.displayName}"
            else -> profile.displayName
        }

    private fun rotatingSlotCount(available: Int): Int =
        available
            .coerceAtMost(SuggestionsConfig.ROTATING_SLOTS_MAX)
            .coerceAtMost(SuggestionsConfig.ROTATING_SLOTS_MIN.coerceAtLeast(available))

    private companion object {
        private const val HIGH_AFFINITY_THRESHOLD = 5.0
        private const val MID_AFFINITY_THRESHOLD = 2.0
        private const val MAX_SAVED_SEARCH_SECTIONS = 2
    }
}
