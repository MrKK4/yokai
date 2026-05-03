package yokai.domain.suggestions

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SectionPlannerTest {

    @Test
    fun `plan orders discovery pinned then every managed tag by affinity`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val planner = SectionPlanner(repository, SuggestionsDebugLog())
        val profiles = listOf(
            profile("romance", recent = 10.0),
            profile("action", recent = 8.0, state = TagState.PINNED, pinnedAt = 2L),
            profile("fantasy", recent = 6.0),
            profile("comedy", recent = 4.0, state = TagState.PINNED, pinnedAt = 1L),
            profile("drama", recent = 3.0),
        )

        val sections = planner.plan(
            profiles = profiles,
            sortOrder = SuggestionSortOrder.Popular,
            now = 10_000L,
        )

        assertEquals(SectionType.DISCOVERY, sections[0].type)
        assertEquals(listOf("comedy", "action"), sections.drop(1).take(2).map { it.canonicalTag })
        assertEquals(listOf("romance", "fantasy", "drama"), sections.drop(3).map { it.canonicalTag })
        assertTrue(sections.drop(3).all { it.type == SectionType.MANAGED_TAG })
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
    fun `section batcher returns five sections from start index`() {
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

        assertEquals(listOf("tag:0", "tag:1", "tag:2", "tag:3", "tag:4"), SectionBatcher.nextBatch(planned, 0).map { it.sectionKey })
        assertEquals(listOf("tag:10", "tag:11"), SectionBatcher.nextBatch(planned, 10).map { it.sectionKey })
        assertTrue(SectionBatcher.nextBatch(planned, 12).isEmpty())
    }

    @Test
    fun `section batcher threshold triggers within two loaded sections from end`() {
        assertFalse(
            SectionBatcher.shouldLoadMore(
                lastVisibleSectionIndex = 2,
                loadedSectionCount = 5,
                isFetchingBatch = false,
                allSectionsLoaded = false,
            ),
        )
        assertTrue(
            SectionBatcher.shouldLoadMore(
                lastVisibleSectionIndex = 3,
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
            pinnedAt = if (state == TagState.PINNED) now else null,
            updatedAt = now,
        )
    }

    override suspend fun findAlias(rawKey: String, sourceId: Long?): TagAlias? =
        aliases[rawKey to (sourceId ?: -1L)] ?: aliases[rawKey to -1L]

    override suspend fun getSearchTerms(canonicalTag: String): List<String> =
        aliases.values
            .filter { it.canonicalTag == canonicalTag }
            .map { it.rawTag } + canonicalTag

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
}
