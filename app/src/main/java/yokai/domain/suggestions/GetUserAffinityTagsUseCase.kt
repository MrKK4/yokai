package yokai.domain.suggestions

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yokai.domain.chapter.ChapterRepository
import yokai.domain.history.HistoryRepository
import yokai.domain.manga.MangaRepository

class GetUserAffinityTagsUseCase(
    private val mangaRepository: MangaRepository,
    private val historyRepository: HistoryRepository,
    private val chapterRepository: ChapterRepository,
    private val preferences: PreferencesHelper,
) {
    suspend fun execute(): List<AffinityTag> {
        val mangaList = mangaRepository.getMangaList()
        if (mangaList.isEmpty()) return emptyList()
        val histories = historyRepository.getAll()
        val chapters = chapterRepository.getAll()
        val blacklistedTags = preferences.suggestionsTagsBlacklist().get().normalizedTagKeys()

        return withContext(Dispatchers.Default) {
            val tagNames = mutableMapOf<String, String>()
            val tagFrequency = mangaList
                .asSequence()
                .map { manga -> manga.tags(blacklistedTags).map { it.key }.distinct() }
                .flatten()
                .onEach { key -> tagNames.putIfAbsent(key, key.toDisplayTag()) }
                .groupingBy { it }
                .eachCount()

            val totalDocs = mangaList.count { it.tags(blacklistedTags).isNotEmpty() }.coerceAtLeast(1)
            val scoreMap = mutableMapOf<String, Double>()
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

            for (manga in mangaList) {
                val tags = manga.tags(blacklistedTags)
                if (tags.isEmpty()) continue
                tags.forEach { tagNames.putIfAbsent(it.key, it.name) }

                val mangaId = manga.id
                if (mangaId != null) {
                    addHistorySignal(
                        manga = manga,
                        histories = historyByMangaId[mangaId].orEmpty(),
                        chapters = chaptersByMangaId[mangaId].orEmpty(),
                        tags = tags,
                        totalDocs = totalDocs,
                        tagFrequency = tagFrequency,
                        scoreMap = scoreMap,
                    )
                }

                if (manga.favorite) {
                    addSignal(
                        tags = tags,
                        signal = InteractionClassifier.baseWeight(InteractionType.FAVORITED),
                        totalDocs = totalDocs,
                        tagFrequency = tagFrequency,
                        scoreMap = scoreMap,
                    )
                }
            }

            scoreMap
                .filterValues { it > 0.0 }
                .entries
                .sortedByDescending { it.value }
                .take(TOP_N)
                .map { AffinityTag(name = tagNames[it.key] ?: it.key.toDisplayTag(), score = it.value) }
        }
    }

    private fun addHistorySignal(
        manga: Manga,
        histories: List<History>,
        chapters: List<Chapter>,
        tags: List<NormalizedTag>,
        totalDocs: Int,
        tagFrequency: Map<String, Int>,
        scoreMap: MutableMap<String, Double>,
    ) {
        val chaptersRead = chapters.count { it.read || it.last_page_read > 0 }

        if (histories.isEmpty() && chaptersRead == 0) return

        val lastReadAt = histories.maxOfOrNull { it.last_read }
            ?: manga.last_update.takeIf { it > 0L }
            ?: manga.date_added

        val interaction = InteractionClassifier.classify(
            chaptersRead = chaptersRead,
            totalChapters = chapters.size,
            isFavorited = false,
            lastReadAt = lastReadAt,
        )
        val signal = InteractionClassifier.baseWeight(interaction) * recencyDecay(lastReadAt)

        addSignal(
            tags = tags,
            signal = signal,
            totalDocs = totalDocs,
            tagFrequency = tagFrequency,
            scoreMap = scoreMap,
        )
    }

    private fun addSignal(
        tags: List<NormalizedTag>,
        signal: Double,
        totalDocs: Int,
        tagFrequency: Map<String, Int>,
        scoreMap: MutableMap<String, Double>,
    ) {
        tags.forEach { tag ->
            val idf = idf(tagFrequency[tag.key] ?: 1, totalDocs)
            scoreMap[tag.key] = (scoreMap[tag.key] ?: 0.0) + (signal * idf)
        }
    }

    private fun recencyDecay(lastReadAt: Long): Double {
        if (lastReadAt <= 0L) return 1.0
        val days = max(0L, System.currentTimeMillis() - lastReadAt) / MILLIS_PER_DAY
        return exp(-LN_2 / HALF_LIFE_DAYS * days)
    }

    private fun idf(tagFrequency: Int, totalDocs: Int): Double =
        ln(totalDocs.toDouble() / (1.0 + tagFrequency)).coerceAtLeast(MIN_IDF)

    private fun Manga.tags(blacklistedTags: Set<String>): List<NormalizedTag> =
        genre
            ?.split(",")
            ?.mapNotNull { raw ->
                val tag = raw.trim()
                tag.takeIf { it.isNotBlank() }?.let { NormalizedTag(name = it, key = it.normalizedTagKey()) }
            }
            ?.filterNot { it.key in blacklistedTags }
            ?.distinctBy { it.key }
            .orEmpty()

    private fun Set<String>.normalizedTagKeys(): Set<String> =
        map { it.normalizedTagKey() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun String.normalizedTagKey(): String =
        lowercase().trim().replace(WHITESPACE, " ")

    private fun String.toDisplayTag(): String =
        split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }

    private data class NormalizedTag(
        val name: String,
        val key: String,
    )

    private companion object {
        private const val HALF_LIFE_DAYS = 14.0
        private const val LN_2 = 0.6931471805599453
        private const val MILLIS_PER_DAY = 86_400_000.0
        private const val MIN_IDF = 0.05
        private const val TOP_N = 50
        private val WHITESPACE = Regex("\\s+")
    }
}
