package app.forkcast.core.data.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.forkcast.core.data.R
import app.forkcast.core.data.db.AiDao
import app.forkcast.core.data.db.EstimateStatus
import app.forkcast.core.data.notify.Channel
import app.forkcast.core.data.notify.Notifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.NumberFormat
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Runs one estimate in the background (SPEC §4.6 step 5): expedited, needs network, retries
 * with backoff when offline, and waits for the earliest quota reset when every profile is out.
 */
@HiltWorker
class EstimateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val estimates: AiEstimateRepository,
    private val scheduler: EstimateScheduler,
    private val notifier: Notifier,
    private val dao: AiDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        return when (val outcome = estimates.run(id)) {
            is RunOutcome.Finished -> {
                notifyReady(outcome.estimateId)
                Result.success()
            }
            is RunOutcome.RetryAt -> {
                scheduler.enqueue(id, delayMs = (outcome.atMs - System.currentTimeMillis()).coerceAtLeast(60_000))
                Result.success()
            }
            RunOutcome.RetryWithBackoff -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            is RunOutcome.Failed -> {
                if (outcome.notifyKeyProblem) notifyKeyProblem()
                Result.success()
            }
            RunOutcome.Nothing -> Result.success()
        }
    }

    /** "Lunch estimated: ~640 kcal (520–760). Tap to review." Only when the app isn't on screen. */
    private suspend fun notifyReady(id: String) {
        val e = dao.estimate(id) ?: return
        if (e.notified || notifier.appInForeground()) {
            estimates.markNotified(id)
            return
        }
        val ctx = applicationContext
        val n = NumberFormat.getIntegerInstance()
        val meal = e.mealName ?: ctx.getString(R.string.photo_meal)
        val text = if (e.status == EstimateStatus.NEEDS_INPUT) {
            ctx.getString(R.string.estimate_question_notification, meal)
        } else {
            ctx.getString(
                R.string.estimate_ready_notification,
                meal,
                n.format((e.kcalExpected ?: 0.0).roundToInt()),
                n.format((e.kcalLow ?: 0.0).roundToInt()),
                n.format((e.kcalHigh ?: 0.0).roundToInt()),
            )
        }
        notifier.post(Channel.ESTIMATE_READY, id.hashCode(), meal, text, open = "estimate:$id")
        estimates.markNotified(id)
    }

    private fun notifyKeyProblem() {
        val ctx = applicationContext
        notifier.post(
            Channel.ESTIMATE_READY, KEY_PROBLEM_ID, ctx.getString(R.string.estimate_failed_title),
            ctx.getString(R.string.estimate_key_problem), open = "settings_ai",
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(Notifier.WORKING_ID, notifier.workingNotification())

    companion object {
        const val KEY_ID = "estimateId"
        private const val MAX_ATTEMPTS = 20
        private const val KEY_PROBLEM_ID = 1002
    }
}

@Singleton
class WorkManagerEstimateScheduler @Inject constructor(@ApplicationContext private val context: Context) : EstimateScheduler {

    override fun enqueue(estimateId: String, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<EstimateWorker>()
            .setInputData(workDataOf(EstimateWorker.KEY_ID to estimateId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .apply {
                // Expedited work can't be delayed; a delayed run is a quota wait anyway.
                if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) else setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(estimateId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(estimateId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(name(estimateId))
    }

    private fun name(id: String) = "estimate-$id"

    companion object {
        const val TAG = "estimate"
    }
}
