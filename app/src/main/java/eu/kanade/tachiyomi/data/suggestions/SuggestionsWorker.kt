package eu.kanade.tachiyomi.data.suggestions

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import yokai.domain.suggestions.CandidateRetriever
import yokai.domain.suggestions.FeedAggregator
import yokai.domain.suggestions.GetUserSuggestionQueriesUseCase
import yokai.domain.suggestions.InterestProfileBuilder
import yokai.domain.suggestions.PlannedSection
import yokai.domain.suggestions.PlannedSectionRepository
import yokai.domain.suggestions.SectionBatcher
import yokai.domain.suggestions.SectionPlanner
import yokai.domain.suggestions.SessionContext
import yokai.domain.suggestions.ShownMangaHistoryRepository
import yokai.domain.suggestions.SuggestedManga
import yokai.domain.suggestions.SuggestionRanker
import yokai.domain.suggestions.SuggestionSeenLogRepository
import yokai.domain.suggestions.SuggestionsConfig
import yokai.domain.suggestions.SuggestionsRepository
import yokai.domain.suggestions.SuggestionsRefreshCoordinator
import yokai.domain.suggestions.SeenEntry
import yokai.domain.suggestions.TagCanonicalizer
import yokai.domain.suggestions.TagProfileRepository
import yokai.domain.suggestions.TagState

class SuggestionsWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val suggestionsRepository: SuggestionsRepository = Injekt.get()
    private val getSuggestionQueries: GetUserSuggestionQueriesUseCase = Injekt.get()
    private val feedAggregator: FeedAggregator = Injekt.get()
    private val preferences: PreferencesHelper = Injekt.get()
    private val interestProfileBuilder: InterestProfileBuilder = Injekt.get()
    private val sectionPlanner: SectionPlanner = Injekt.get()
    private val plannedSectionRepository: PlannedSectionRepository = Injekt.get()
    private val candidateRetriever: CandidateRetriever = Injekt.get()
    private val suggestionRanker: SuggestionRanker = Injekt.get()
    private val suggestionSeenLogRepository: SuggestionSeenLogRepository = Injekt.get()
    private val shownMangaHistoryRepository: ShownMangaHistoryRepository = Injekt.get()
    private val sessionContext: SessionContext = Injekt.get()
    private val tagCanonicalizer: TagCanonicalizer = Injekt.get()
    private val tagProfileRepository: TagProfileRepository = Injekt.get()

    override suspend fun doWork(): Result {
        return try {
            SuggestionsRefreshCoordinator.tryRun {
                val v2AtStart = preferences.suggestionsV2Enabled().get()
                if (v2AtStart) {
                    return@tryRun doV2Work()
                }

                val now = System.currentTimeMillis()
                interestProfileBuilder.buildProfile(now)
                syncLegacyTagStateForV2(now)
                val suggestionQueries = getSuggestionQueries.execute()
                val suggestions = feedAggregator.fetch(suggestionQueries)
                // The user may have flipped V2 on while the network fetch was in flight.
                // Don't write V1 rows under a V2 expectation — the foreground rebuild will
                // produce fresh V2 results, so let it own the table.
                if (preferences.suggestionsV2Enabled().get() != v2AtStart) return@tryRun Result.success()
                if (suggestions.isEmpty()) return@tryRun retryOrFailure()

                preferences.suggestionsResultVersion().set(SuggestionsConfig.RESULT_VERSION_V1)
                suggestionsRepository.replaceAll(suggestions)
                Result.success()
            } ?: retryOrFailure()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            retryOrFailure()
        }
    }

    private suspend fun doV2Work(): Result {
        if (!candidateRetriever.hasActiveNetworkSources()) {
            return Result.success()
        }

        val now = System.currentTimeMillis()
        suggestionSeenLogRepository.deleteOlderThan(now - SuggestionsConfig.SEEN_LOG_TTL_MS)

        var profiles = tagProfileRepository.getAllProfiles()
        if (profiles.isEmpty()) {
            interestProfileBuilder.buildProfile(now)
            syncLegacyTagStateForV2(now)
            profiles = tagProfileRepository.getAllProfiles()
        }
        val plannedSections = sectionPlanner.plan(
            profiles = profiles,
            sortOrder = preferences.suggestionsSortOrder().get(),
            now = now,
        )
        plannedSectionRepository.replaceAll(plannedSections)
        // Belt-and-braces orphan sweep: deletes any rows whose section_key isn't in the new
        // plan, including the empty-string section_key edge case that the per-section loop
        // below would skip.
        suggestionsRepository.deleteOrphanedByPlan()

        val existingSuggestions = suggestionsRepository.getSuggestions()
        val loadedSectionKeys = existingSuggestions.map { it.sectionKey }.toSet()
        val plannedSectionKeys = plannedSections.map { it.sectionKey }.toSet()
        val hasRetainedSuggestions = existingSuggestions.any { it.sectionKey in plannedSectionKeys }
        (loadedSectionKeys - plannedSectionKeys).forEach { staleSectionKey ->
            suggestionsRepository.deleteBySectionKey(staleSectionKey)
        }

        val nextMissingIndex = SectionBatcher.contiguousLoadedPrefixSize(
            plannedSections = plannedSections,
            loadedSectionKeys = loadedSectionKeys,
        )
        val rankingContext = suggestionRanker.buildRankingContext()
        val globalSeenKeys = shownMangaHistoryRepository.getAllKeys()
        var fetchedAny = false
        var committedRefreshId: Long? = null
        var batchStartIndex = nextMissingIndex.takeIf { it < plannedSections.size } ?: 0

        repeat(SuggestionsConfig.BACKGROUND_MAX_SECTION_BATCHES) {
            // Bail mid-run if the foreground toggled the V2 flag — the foreground rebuild owns
            // the table after the toggle, and any further writes here would mix V1/V2 rows.
            if (!preferences.suggestionsV2Enabled().get()) return@repeat
            val sectionsToFetch = SectionBatcher.nextBatch(plannedSections, batchStartIndex)
            if (sectionsToFetch.isEmpty()) return@repeat

            val sectionSeenKeys = suggestionSeenLogRepository.recentKeysForSections(
                sectionKeys = sectionsToFetch.map { it.sectionKey },
                cutoff = now - SuggestionsConfig.SEEN_LOG_TTL_MS,
            )
            val suggestions = suggestionRanker.rankWithContext(
                retrievalResults = candidateRetriever.retrieve(
                    sections = sectionsToFetch,
                    globalSeenKeys = globalSeenKeys,
                    sectionSeenKeys = sectionSeenKeys,
                ),
                context = rankingContext,
                globalSeenKeys = globalSeenKeys,
                sectionSeenKeys = sectionSeenKeys,
                sessionContext = sessionContext,
            )
            if (suggestions.isNotEmpty()) {
                fetchedAny = true
                if (committedRefreshId == null) {
                    committedRefreshId = preferences.suggestionsTotalRefreshCount().get() + 1L
                    preferences.suggestionsTotalRefreshCount().set(committedRefreshId.toInt())
                }
                persistV2Batch(
                    suggestions = suggestions,
                    sectionsToFetch = sectionsToFetch,
                    refreshId = committedRefreshId,
                    now = now,
                )
            }

            batchStartIndex = (batchStartIndex + sectionsToFetch.size).coerceAtMost(plannedSections.size)
            if (batchStartIndex >= plannedSections.size) return@repeat
        }

        return if (fetchedAny || hasRetainedSuggestions) Result.success() else retryOrFailure()
    }

    private suspend fun persistV2Batch(
        suggestions: List<SuggestedManga>,
        sectionsToFetch: List<PlannedSection>,
        refreshId: Long,
        now: Long,
    ) {
        preferences.suggestionsResultVersion().set(SuggestionsConfig.RESULT_VERSION_V2)
        suggestions
            .groupBy { it.sectionKey }
            .forEach { (sectionKey, sectionSuggestions) ->
                suggestionsRepository.deleteBySectionKey(sectionKey)
                suggestionsRepository.insertSuggestions(sectionSuggestions)
            }
        shownMangaHistoryRepository.insertAll(suggestions.map { it.source to it.url })
        suggestionSeenLogRepository.insertSeenBatch(
            suggestions.map { suggestion ->
                SeenEntry(
                    sectionKey = suggestion.sectionKey,
                    mangaKey = "${suggestion.source}:${suggestion.url}",
                    shownAt = now,
                    refreshId = refreshId,
                )
            },
        )
    }

    private suspend fun syncLegacyTagStateForV2(now: Long) {
        tagProfileRepository.resetBlacklistedToManaged(now)
        // Pins are V1-only and not synced to the V2 tag_profile table.
        preferences.suggestionsTagsBlacklist().get().forEach { rawTag ->
            val canonicalTag = tagCanonicalizer.canonicalize(rawTag).canonicalKey
            if (canonicalTag.isNotBlank()) {
                tagProfileRepository.setTagState(canonicalTag, TagState.BLACKLISTED, now)
            }
        }
    }

    private fun retryOrFailure(): Result {
        if (runAttemptCount < MAX_RETRIES) return Result.retry()
        // Bug 8b: persist failure timestamp so the UI can surface a non-intrusive banner
        // if suggestions haven't been refreshed for more than 24 hours.
        preferences.suggestionsWorkerLastFailedAt().set(System.currentTimeMillis())
        return Result.failure()
    }

    companion object {
        private const val TAG = "Suggestions"
        private const val WORK_NAME_AUTO = "Suggestions-auto"
        private const val WORK_NAME_MANUAL = "Suggestions-manual"
        private const val MAX_RETRIES = 3

        fun setupTask(context: Context) {
            val request = PeriodicWorkRequestBuilder<SuggestionsWorker>(
                12,
                TimeUnit.HOURS,
                30,
                TimeUnit.MINUTES,
            )
                .addTag(TAG)
                .addTag(WORK_NAME_AUTO)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_AUTO,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SuggestionsWorker>()
                .addTag(TAG)
                .addTag(WORK_NAME_MANUAL)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_MANUAL,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancelManual(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_MANUAL)
        }

        fun isRunningFlow(context: Context): Flow<Boolean> =
            WorkManager.getInstance(context).getWorkInfosByTagFlow(WORK_NAME_MANUAL)
                .map { list ->
                    list.any {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED
                    }
                }

        private fun networkConstraints(): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
    }
}
