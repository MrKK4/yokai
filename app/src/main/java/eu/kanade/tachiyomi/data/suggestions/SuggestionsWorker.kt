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
import yokai.domain.suggestions.FeedAggregator
import yokai.domain.suggestions.GetUserSuggestionQueriesUseCase
import yokai.domain.suggestions.SuggestionsRepository

class SuggestionsWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val suggestionsRepository: SuggestionsRepository = Injekt.get()
    private val getSuggestionQueries: GetUserSuggestionQueriesUseCase = Injekt.get()
    private val feedAggregator: FeedAggregator = Injekt.get()

    override suspend fun doWork(): Result {
        return try {
            val suggestionQueries = getSuggestionQueries.execute()
            val suggestions = feedAggregator.fetch(suggestionQueries)
            if (suggestions.isEmpty()) return retryOrFailure()

            suggestionsRepository.replaceAll(suggestions)
            Result.success()
        } catch (_: Exception) {
            retryOrFailure()
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
