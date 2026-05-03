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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import yokai.domain.suggestions.CandidateRetriever
import yokai.domain.suggestions.FeedAggregator
import yokai.domain.suggestions.GetUserSuggestionQueriesUseCase
import yokai.domain.suggestions.InterestProfileBuilder
import yokai.domain.suggestions.PlannedSectionRepository
import yokai.domain.suggestions.SectionBatcher
import yokai.domain.suggestions.SectionPlanner
import yokai.domain.suggestions.SessionContext
import yokai.domain.suggestions.ShownMangaHistoryRepository
import yokai.domain.suggestions.SuggestionRanker
import yokai.domain.suggestions.SuggestionSeenLogRepository
import yokai.domain.suggestions.SuggestionsConfig
import yokai.domain.suggestions.SuggestionsRepository
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
            if (preferences.suggestionsV2Enabled().get()) {
                return doV2Work()
            }

            val suggestionQueries = getSuggestionQueries.execute()
            val suggestions = feedAggregator.fetch(suggestionQueries)
            if (suggestions.isEmpty()) return retryOrFailure()

            suggestionsRepository.replaceAll(suggestions)
            Result.success()
        } catch (_: Exception) {
            retryOrFailure()
        }
    }

    private suspend fun doV2Work(): Result {
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

        val loadedReasons = suggestionsRepository.getSuggestions()
            .map { it.reason }
            .toSet()
        val sectionsToFetch = plannedSections
            .filter { it.displayReason in loadedReasons }
            .ifEmpty { SectionBatcher.nextBatch(plannedSections, 0) }

        val sectionSeenKeys = sectionsToFetch.associate { section ->
            section.sectionKey to suggestionSeenLogRepository.recentKeysForSection(
                sectionKey = section.sectionKey,
                cutoff = now - SuggestionsConfig.SEEN_LOG_TTL_MS,
            )
        }
        val suggestions = suggestionRanker.rank(
            retrievalResults = candidateRetriever.retrieve(sectionsToFetch),
            globalSeenKeys = shownMangaHistoryRepository.getAllKeys(),
            sectionSeenKeys = sectionSeenKeys,
            sessionContext = sessionContext,
        )
        if (suggestions.isEmpty()) return retryOrFailure()

        suggestionsRepository.replaceAll(suggestions)
        shownMangaHistoryRepository.insertAll(suggestions.map { it.source to it.url })
        val sectionKeyByReason = sectionsToFetch.associate { it.displayReason to it.sectionKey }
        val refreshId = preferences.suggestionsTotalRefreshCount().get() + 1L
        preferences.suggestionsTotalRefreshCount().set(refreshId.toInt())
        suggestions.forEach { suggestion ->
            suggestionSeenLogRepository.insertSeen(
                sectionKey = sectionKeyByReason[suggestion.reason] ?: suggestion.reason.lowercase().trim(),
                mangaKey = "${suggestion.source}:${suggestion.url}",
                shownAt = now,
                refreshId = refreshId,
            )
        }
        return Result.success()
    }

    private suspend fun syncLegacyTagStateForV2(now: Long) {
        preferences.suggestionsPinnedTags().get().forEach { rawTag ->
            val canonicalTag = tagCanonicalizer.canonicalize(rawTag).canonicalKey
            if (canonicalTag.isNotBlank()) {
                tagProfileRepository.setTagState(canonicalTag, TagState.PINNED, now)
            }
        }
        preferences.suggestionsTagsBlacklist().get().forEach { rawTag ->
            val canonicalTag = tagCanonicalizer.canonicalize(rawTag).canonicalKey
            if (canonicalTag.isNotBlank()) {
                tagProfileRepository.setTagState(canonicalTag, TagState.BLACKLISTED, now)
            }
        }
    }

    private fun retryOrFailure(): Result =
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()

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
