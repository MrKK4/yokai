package yokai.domain.suggestions

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.domain.manga.models.Manga
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yokai.domain.chapter.ChapterRepository
import yokai.domain.history.HistoryRepository
import yokai.domain.manga.MangaRepository

class InterestProfileBuilder(
    private val mangaRepository: MangaRepository,
    private val historyRepository: HistoryRepository,
    private val chapterRepository: ChapterRepository,
    private val canonicalizer: TagCanonicalizer,
    private val tagProfileRepository: TagProfileRepository,
    private val debugLog: SuggestionsDebugLog,
) {
    suspend fun buildProfile(now: Long = System.currentTimeMillis()): List<TagProfile> {
        val mangaList = mangaRepository.getMangaList()
        if (mangaList.isEmpty()) return emptyList()

        val histories = historyRepository.getAll()
        val chapters = chapterRepository.getAll()
        val historyByMangaId = histories.groupBy(
            keySelector = { it.mangaId },
            valueTransform = { it.history },
        )
        val chaptersByMangaId = chapters
            .mapNotNull { chapter -> chapter.manga_id?.let { mangaId -> mangaId to chapter } }
            .groupBy(
                keySelector = { it.first },
                valueTransform = { it.second },
            )

        val taggedEvents = mutableListOf<TaggedSignal>()
        for (manga in mangaList) {
            val canonicalTags = manga.getOriginalGenres()
                .orEmpty()
                .mapNotNull { rawTag ->
                    canonicalizer.canonicalize(rawTag).takeIf { it.canonicalKey.isNotBlank() }
                }
                .distinctBy { it.canonicalKey }
            if (canonicalTags.isEmpty()) continue

            val mangaId = manga.id
            val mangaHistories = mangaId?.let { historyByMangaId[it].orEmpty() }.orEmpty()
            val mangaChapters = mangaId?.let { chaptersByMangaId[it].orEmpty() }.orEmpty()
            val readSignal = readingSignal(manga, mangaHistories, mangaChapters, now)
            if (readSignal != null) {
                canonicalTags.forEach { tag ->
                    taggedEvents += TaggedSignal(tag, readSignal.signal, readSignal.at)
                }
            }

            if (manga.favorite) {
                val favoriteAt = listOf(manga.date_added, manga.last_update, readSignal?.at ?: 0L)
                    .filter { it > 0L }
                    .maxOrNull()
                    ?: now
                canonicalTags.forEach { tag ->
                    taggedEvents += TaggedSignal(tag, signalWeight(InteractionType.FAVORITED), favoriteAt)
                }
            }
        }

        val profiles = withContext(Dispatchers.Default) {
            taggedEvents
                .sortedBy { it.at }
                .fold(linkedMapOf<String, ProfileAccumulator>()) { acc, event ->
                    val entry = acc.getOrPut(event.tag.canonicalKey) {
                        ProfileAccumulator(
                            canonicalTag = event.tag.canonicalKey,
                            displayName = event.tag.displayName,
                        )
                    }
                    entry.displayName = event.tag.displayName
                    entry.lastSeenAt = max(entry.lastSeenAt, event.at)
                    entry.longTermCount = entry.longTermCount * (1.0 - SuggestionsConfig.LTM_ALPHA) +
                        event.signal * SuggestionsConfig.LTM_ALPHA
                    val daysSince = daysBetween(event.at, now)
                    if (daysSince <= STM_WINDOW_DAYS) {
                        entry.recentCount += event.signal * recencyDecay(daysSince)
                    }
                    when {
                        daysSince <= CURRENT_WEEK_DAYS ->
                            entry.currentWeekCount += event.signal
                        daysSince <= PREVIOUS_WEEK_END_DAYS ->
                            entry.previousWeekCount += event.signal
                    }
                    acc
                }
                .values
                .map { accumulator ->
                    TagProfile(
                        canonicalTag = accumulator.canonicalTag,
                        displayName = accumulator.displayName,
                        longTermCount = accumulator.longTermCount,
                        recentCount = accumulator.recentCount,
                        velocity = accumulator.currentWeekCount - accumulator.previousWeekCount,
                        currentWeekCount = accumulator.currentWeekCount,
                        previousWeekCount = accumulator.previousWeekCount,
                        lastSeenAt = accumulator.lastSeenAt,
                        state = TagState.MANAGED,
                        pinnedAt = null,
                        updatedAt = now,
                    )
                }
        }

        tagProfileRepository.upsertProfiles(profiles)
        debugLog.add(LogType.PROFILE_UPDATE, "Profile rebuilt with ${profiles.size} canonical tags")
        return tagProfileRepository.getAllProfiles()
    }

    private fun readingSignal(
        manga: Manga,
        histories: List<History>,
        chapters: List<Chapter>,
        now: Long,
    ): ReadingSignal? {
        val chaptersRead = chapters.count { it.read || it.last_page_read > 0 }
        if (histories.isEmpty() && chaptersRead == 0) return null

        val lastReadAt = histories.maxOfOrNull { it.last_read }
            ?: manga.last_update.takeIf { it > 0L }
            ?: manga.date_added.takeIf { it > 0L }
            ?: now

        val interaction = InteractionClassifier.classify(
            chaptersRead = chaptersRead,
            totalChapters = chapters.size,
            isFavorited = false,
            lastReadAt = lastReadAt,
        )
        return ReadingSignal(
            signal = signalWeight(interaction),
            at = lastReadAt,
        )
    }

    private fun signalWeight(interaction: InteractionType): Double =
        when (interaction) {
            InteractionType.DROPPED_EARLY -> -0.5
            InteractionType.SAMPLED -> 1.0
            InteractionType.READ -> 2.0
            InteractionType.COMPLETED -> 3.0
            InteractionType.FAVORITED -> 4.0
        }

    private fun recencyDecay(daysSince: Double): Double =
        exp(-LN_2 / SuggestionsConfig.STM_HALF_LIFE_DAYS * daysSince)

    private fun daysBetween(then: Long, now: Long): Double =
        max(0L, now - then) / MILLIS_PER_DAY

    private data class ReadingSignal(
        val signal: Double,
        val at: Long,
    )

    private data class TaggedSignal(
        val tag: CanonicalTag,
        val signal: Double,
        val at: Long,
    )

    private data class ProfileAccumulator(
        val canonicalTag: String,
        var displayName: String,
        var longTermCount: Double = 0.0,
        var recentCount: Double = 0.0,
        var currentWeekCount: Double = 0.0,
        var previousWeekCount: Double = 0.0,
        var lastSeenAt: Long = 0L,
    )

    private companion object {
        private const val STM_WINDOW_DAYS = 14.0
        private const val CURRENT_WEEK_DAYS = 7.0
        private const val PREVIOUS_WEEK_END_DAYS = 14.0
        private const val MILLIS_PER_DAY = 86_400_000.0
        private const val LN_2 = 0.6931471805599453
    }
}
