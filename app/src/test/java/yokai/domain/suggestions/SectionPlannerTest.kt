package yokai.domain.suggestions

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SectionPlannerTest {

    @Test
    fun `plan orders discovery pinned guaranteed then rotating sections`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val planner = SectionPlanner(repository, SuggestionsDebugLog(), Random(1))
        val now = 10_000L
        val profiles = listOf(
            profile("romance", recent = 10.0),
            profile("action", recent = 8.0, state = TagState.PINNED, pinnedAt = 1L),
            profile("fantasy", recent = 6.0),
            profile("comedy", recent = 4.0),
            profile("drama", recent = 3.0),
        )

        val sections = planner.plan(
            profiles = profiles,
            sortOrder = SuggestionSortOrder.Popular,
            now = now,
            applyCooldown = false,
        )

        assertEquals(SectionType.DISCOVERY, sections[0].type)
        assertEquals(SectionType.PINNED_TAG, sections[1].type)
        assertEquals("action", sections[1].canonicalTag)
        assertEquals(SectionType.GUARANTEED_TAG, sections[2].type)
        assertEquals("romance", sections[2].canonicalTag)
        assertTrue(sections.drop(3).all { it.type == SectionType.ROTATING_TAG })
    }

    @Test
    fun `plan excludes blacklisted tags and skips rotating cooldown tags`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val planner = SectionPlanner(repository, SuggestionsDebugLog(), Random(1))
        val now = 10_000L
        val profiles = listOf(
            profile("romance", recent = 10.0),
            profile("action", recent = 8.0, state = TagState.BLACKLISTED),
            profile("fantasy", recent = 6.0, cooldownUntil = now + 1_000L),
            profile("comedy", recent = 4.0),
            profile("drama", recent = 3.0),
        )

        val sections = planner.plan(
            profiles = profiles,
            sortOrder = SuggestionSortOrder.Latest,
            now = now,
            applyCooldown = false,
        )

        assertFalse(sections.any { it.canonicalTag == "action" })
        assertFalse(sections.any { it.type == SectionType.ROTATING_TAG && it.canonicalTag == "fantasy" })
    }

    @Test
    fun `plan writes cooldown for rotating sections`() = runBlocking {
        val repository = FakeTagProfileRepository()
        val planner = SectionPlanner(repository, SuggestionsDebugLog(), Random(1))
        val now = 10_000L

        planner.plan(
            profiles = listOf(
                profile("romance", recent = 10.0),
                profile("fantasy", recent = 6.0),
                profile("comedy", recent = 4.0),
                profile("drama", recent = 3.0),
            ),
            sortOrder = SuggestionSortOrder.Popular,
            now = now,
            refreshIntervalMillis = 100L,
        )

        assertTrue(repository.cooldowns.values.all { it == now + 200L })
    }
}

internal fun profile(
    canonicalTag: String,
    recent: Double = 1.0,
    longTerm: Double = 0.0,
    state: TagState = TagState.MANAGED,
    pinnedAt: Long? = null,
    cooldownUntil: Long = 0L,
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
        cooldownUntil = cooldownUntil,
        updatedAt = 0L,
    )

internal class FakeTagProfileRepository : TagProfileRepository {
    private val profiles = linkedMapOf<String, TagProfile>()
    private val aliases = linkedMapOf<Pair<String, Long>, TagAlias>()
    private val variantCounts = linkedMapOf<Pair<String, String>, Int>()
    val cooldowns = linkedMapOf<String, Long>()

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

    override suspend fun updateCooldown(canonicalTag: String, cooldownUntil: Long, now: Long) {
        cooldowns[canonicalTag] = cooldownUntil
        profiles[canonicalTag] = (profiles[canonicalTag] ?: profile(canonicalTag)).copy(
            cooldownUntil = cooldownUntil,
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
