package yokai.domain.suggestions

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SectionPlannerTest {

    @Test
    fun `plan orders discovery followed by every managed tag by affinity`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val planner = SectionPlanner(repository, SuggestionsDebugLog())
        // V2 never pins — affinity ranking only. Pin functionality is V1-only.
        val profiles = listOf(
            profile("romance", recent = 10.0),
            profile("action", recent = 8.0),
            profile("fantasy", recent = 6.0),
            profile("comedy", recent = 4.0),
            profile("drama", recent = 3.0),
        )

        val sections = planner.plan(
            profiles = profiles,
            sortOrder = SuggestionSortOrder.Popular,
            now = 10_000L,
        )

        assertEquals(SectionType.DISCOVERY, sections[0].type)
        assertEquals(
            listOf("romance", "action", "fantasy", "comedy", "drama"),
            sections.drop(1).map { it.canonicalTag },
        )
        assertTrue(sections.drop(1).all { it.type == SectionType.MANAGED_TAG })
    }

    @Test
    fun `precise profile planner excludes blacklisted and zero affinity tags`() {
        val profiles = listOf(
            profile("romance", recent = 10.0),
            profile("action", recent = 8.0, state = TagState.BLACKLISTED),
            profile("zero", recent = 0.0),
            profile("drama", recent = 2.0),
        )

        val planned = SuggestionProfilePlanner.precise(profiles)

        assertEquals(listOf("romance", "drama"), planned.map { it.canonicalTag })
    }

    @Test
    fun `surprise profile planner mixes high and low affinity tags`() {
        val profiles = listOf(
            profile("one", recent = 10.0),
            profile("two", recent = 9.0),
            profile("three", recent = 8.0),
            profile("four", recent = 3.0),
            profile("five", recent = 2.0),
            profile("six", recent = 1.0),
        )

        val planned = SuggestionProfilePlanner.surprise(
            profiles = profiles,
            random = kotlin.random.Random(0),
        )

        assertEquals(6, planned.size)
        assertEquals(
            setOf("one", "two", "three", "four", "five", "six"),
            planned.map { it.canonicalTag }.toSet(),
        )
        assertTrue(planned.take(2).any { it.affinity <= 3.0 })
    }

    @Test
    fun `plan excludes blacklisted and zero affinity managed tags`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val planner = SectionPlanner(repository, SuggestionsDebugLog())
        val profiles = listOf(
            profile("romance", recent = 10.0),
            profile("action", recent = 8.0, state = TagState.BLACKLISTED),
            profile("zero", recent = 0.0),
        )

        val sections = planner.plan(
            profiles = profiles,
            sortOrder = SuggestionSortOrder.Latest,
            now = 10_000L,
        )

        assertFalse(sections.any { it.canonicalTag == "action" })
        assertFalse(sections.any { it.canonicalTag == "zero" })
        assertEquals("Latest from your sources", sections.first().displayReason)
    }

    @Test
    fun `plan uses latest discovery only when there are no interest profiles`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val planner = SectionPlanner(repository, SuggestionsDebugLog())

        val sections = planner.plan(
            profiles = emptyList(),
            sortOrder = SuggestionSortOrder.Latest,
            now = 10_000L,
        )

        assertEquals(1, sections.size)
        assertEquals(SectionType.DISCOVERY, sections.single().type)
        assertEquals(COLD_START_DISCOVERY_SECTION_KEY, sections.single().sectionKey)
        assertEquals("Latest from your sources", sections.single().displayReason)
    }

    @Test
    fun `plan uses popular discovery only when there are no interest profiles`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val planner = SectionPlanner(repository, SuggestionsDebugLog())

        val sections = planner.plan(
            profiles = emptyList(),
            sortOrder = SuggestionSortOrder.Popular,
            now = 10_000L,
        )

        assertEquals(1, sections.size)
        assertEquals(SectionType.DISCOVERY, sections.single().type)
        assertEquals(COLD_START_DISCOVERY_SECTION_KEY, sections.single().sectionKey)
        assertEquals("Popular from your sources", sections.single().displayReason)
    }

    @Test
    fun `plan stays in discovery when profiles have no affinity`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val planner = SectionPlanner(repository, SuggestionsDebugLog())

        val sections = planner.plan(
            profiles = listOf(
                profile("action", recent = 0.0),
                profile("romance", recent = 0.0),
            ),
            sortOrder = SuggestionSortOrder.Popular,
            now = 10_000L,
        )

        assertEquals(listOf(COLD_START_DISCOVERY_SECTION_KEY), sections.map { it.sectionKey })
    }

    @Test
    fun `managed reason strings use affinity tiers`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val planner = SectionPlanner(repository, SuggestionsDebugLog())

        val sections = planner.plan(
            profiles = listOf(
                profile("romance", recent = 10.0),
                profile("fantasy", recent = 4.0),
                profile("drama", recent = 1.0),
            ),
            sortOrder = SuggestionSortOrder.Popular,
            now = 10_000L,
        )

        assertEquals("Because you love Romance", sections[1].displayReason)
        assertEquals("Because you often read Fantasy", sections[2].displayReason)
        assertEquals("Because you read Drama", sections[3].displayReason)
    }

    @Test
    fun `section batcher returns configured sections from start index`() {
        val planned = (0 until 12).map { index ->
            PlannedSection(
                sectionKey = "tag:$index",
                type = SectionType.MANAGED_TAG,
                canonicalTag = "$index",
                displayReason = "Because you read $index",
                searchTerms = listOf("$index"),
                sortOrder = SuggestionSortOrder.Popular,
                rank = index.toLong(),
            )
        }

        assertEquals(listOf("tag:0"), SectionBatcher.nextBatch(planned, 0).map { it.sectionKey })
        assertEquals(listOf("tag:10"), SectionBatcher.nextBatch(planned, 10).map { it.sectionKey })
        assertTrue(SectionBatcher.nextBatch(planned, 12).isEmpty())
    }

    @Test
    fun `section batcher tracks the contiguous loaded prefix`() {
        val planned = (0 until 5).map { index ->
            PlannedSection(
                sectionKey = "tag:$index",
                type = SectionType.MANAGED_TAG,
                canonicalTag = "$index",
                displayReason = "Because you read $index",
                searchTerms = listOf("$index"),
                sortOrder = SuggestionSortOrder.Popular,
                rank = index.toLong(),
            )
        }

        assertEquals(
            2,
            SectionBatcher.contiguousLoadedPrefixSize(
                plannedSections = planned,
                loadedSectionKeys = setOf("tag:0", "tag:1", "tag:3"),
            ),
        )
        assertEquals(
            0,
            SectionBatcher.contiguousLoadedPrefixSize(
                plannedSections = planned,
                loadedSectionKeys = setOf("tag:1"),
            ),
        )
        assertEquals(
            planned.size,
            SectionBatcher.contiguousLoadedPrefixSize(
                plannedSections = planned,
                loadedSectionKeys = planned.map { it.sectionKey }.toSet(),
            ),
        )
    }

    @Test
    fun `section batcher threshold triggers at the loaded section end`() {
        assertFalse(
            SectionBatcher.shouldLoadMore(
                lastVisibleSectionIndex = 3,
                loadedSectionCount = 5,
                isFetchingBatch = false,
                allSectionsLoaded = false,
            ),
        )
        assertTrue(
            SectionBatcher.shouldLoadMore(
                lastVisibleSectionIndex = 4,
                loadedSectionCount = 5,
                isFetchingBatch = false,
                allSectionsLoaded = false,
            ),
        )
        assertFalse(
            SectionBatcher.shouldLoadMore(
                lastVisibleSectionIndex = 4,
                loadedSectionCount = 5,
                isFetchingBatch = true,
                allSectionsLoaded = false,
            ),
        )
    }
}

internal fun profile(
    canonicalTag: String,
    recent: Double = 1.0,
    longTerm: Double = 0.0,
    state: TagState = TagState.MANAGED,
    pinnedAt: Long? = null,
): TagProfile =
    TagProfile(
        canonicalTag = canonicalTag,
        displayName = canonicalTag.replaceFirstChar { it.titlecase() },
        longTermCount = longTerm,
        recentCount = recent,
        velocity = 0.0,
        currentWeekCount = 0.0,
        previousWeekCount = 0.0,
        lastSeenAt = 0L,
        state = state,
        pinnedAt = pinnedAt,
        updatedAt = 0L,
    )

internal class FakeTagProfileRepository : TagProfileRepository {
    private val profiles = linkedMapOf<String, TagProfile>()
    private val aliases = linkedMapOf<Pair<String, Long>, TagAlias>()
    private val variantCounts = linkedMapOf<Pair<String, String>, Int>()

    override suspend fun getAllProfiles(): List<TagProfile> = profiles.values.toList()

    override suspend fun getNonBlacklistedProfiles(): List<TagProfile> =
        profiles.values.filterNot { it.isBlacklisted }

    override suspend fun getProfile(canonicalTag: String): TagProfile? =
        profiles[canonicalTag]

    override suspend fun upsertProfile(profile: TagProfile) {
        profiles[profile.canonicalTag] = profile
    }

    override suspend fun upsertProfiles(profiles: List<TagProfile>) {
        profiles.forEach { upsertProfile(it) }
    }

    override suspend fun setTagState(canonicalTag: String, state: TagState, now: Long) {
        val existing = profiles[canonicalTag] ?: profile(canonicalTag)
        profiles[canonicalTag] = existing.copy(
            state = state,
            pinnedAt = null,
            updatedAt = now,
        )
    }

    override suspend fun findAlias(rawKey: String, sourceId: Long?): TagAlias? =
        aliases[rawKey to (sourceId ?: -1L)] ?: aliases[rawKey to -1L]

    override suspend fun getSearchTerms(canonicalTag: String): List<String> =
        aliases.values
            .filter { it.canonicalTag == canonicalTag }
            .map { it.rawTag } + canonicalTag

    override suspend fun getExactTermForSource(canonicalTag: String, sourceId: Long): String? =
        aliases.values
            .firstOrNull { it.canonicalTag == canonicalTag && it.sourceKey == sourceId }
            ?.rawTag

    override suspend fun recordSourceVocabulary(rawTag: String, canonicalTag: String, sourceId: Long) {
        aliases[rawTag.lowercase() to sourceId] = TagAlias(
            rawTag = rawTag,
            rawKey = rawTag.lowercase(),
            canonicalTag = canonicalTag,
            sourceId = sourceId,
            sourceKey = sourceId,
        )
    }

    override suspend fun recordSourceVocabularyBatch(entries: List<Triple<String, String, Long>>) {
        entries.forEach { (rawTag, canonicalTag, sourceId) ->
            recordSourceVocabulary(rawTag, canonicalTag, sourceId)
        }
    }

    override suspend fun aliasOrProfileExists(key: String): Boolean =
        key in profiles || aliases.values.any { it.canonicalTag == key || it.rawKey == key }

    override suspend fun aliasCount(): Long =
        aliases.size.toLong()

    override suspend fun seedAliases(aliases: List<TagAlias>) {
        aliases.forEach { alias ->
            this.aliases[alias.rawKey to alias.sourceKey] = alias
        }
    }

    override suspend fun recordVariant(canonicalTag: String, rawTag: String, now: Long) {
        val key = canonicalTag to rawTag
        variantCounts[key] = variantCounts.getOrDefault(key, 0) + 1
    }

    override suspend fun bestDisplayName(canonicalTag: String): String? =
        variantCounts
            .filterKeys { it.first == canonicalTag }
            .maxWithOrNull(compareBy<Map.Entry<Pair<String, String>, Int>> { it.value }.thenBy { it.key.second })
            ?.key
            ?.second

    override suspend fun resetBlacklistedToManaged(now: Long) {
        profiles.forEach { (key, profile) ->
            if (profile.isBlacklisted) {
                profiles[key] = profile.copy(state = TagState.MANAGED, pinnedAt = null, updatedAt = now)
            }
        }
    }
}
