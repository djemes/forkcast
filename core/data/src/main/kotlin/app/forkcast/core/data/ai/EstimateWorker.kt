package app.forkcast.core.data.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
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
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.NotificationRules
import kotlinx.coroutines.flow.first
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
    private val settings: SettingsRepository,
    private val slots: app.forkcast.core.data.repository.MealSlotRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        // Each outcome schedules its own next run, so the screen can say when (no hidden WorkManager backoff).
        when (val outcome = estimates.run(id)) {
            is RunOutcome.Finished -> notifyReady(outcome.estimateId)
            is RunOutcome.RetryAt -> scheduler.enqueue(id, delayMs = (outcome.atMs - System.currentTimeMillis()).coerceAtLeast(MIN_DELAY_MS))
            // The job only runs when connected; a short pause so a flaky network doesn't spin.
            RunOutcome.RetryWhenOnline -> scheduler.enqueue(id, delayMs = MIN_DELAY_MS)
            is RunOutcome.Failed -> {
                if (outcome.notifyKeyProblem) notifyKeyProblem() else notifyGaveUp(outcome.estimateId)
            }
            RunOutcome.Nothing -> Unit
        }
        return Result.success()
    }

    /** "Lunch wasn't estimated. Tap to see what was tried…", when the user isn't looking (owner, 2026-10-23). */
    private suspend fun notifyGaveUp(id: String) {
        val e = dao.estimate(id) ?: return
        val prefs = settings.settings.first().notifications
        val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        val quiet = NotificationRules.inQuietHours(now, prefs.quietStartMinute, prefs.quietEndMinute)
        if (notifier.appInForeground() || !prefs.estimateReadyEnabled || quiet) return
        val ctx = applicationContext
        val meal = mealNameOf(e)
        notifier.post(
            Channel.ESTIMATE_READY, id.hashCode(), ctx.getString(R.string.estimate_gave_up_title, meal),
            ctx.getString(R.string.estimate_gave_up_notification), open = "estimate:$id",
        )
    }

    private suspend fun mealNameOf(e: app.forkcast.core.data.db.AiEstimateEntity): String =
        e.mealName ?: slots.observeMealSlots().first().firstOrNull { it.id == e.mealSlotId }?.name
            ?: applicationContext.getString(if (e.kind == app.forkcast.core.data.db.EstimateKind.TEXT) R.string.described_meal else R.string.photo_meal)

    /** "Lunch estimated: ~640 kcal (520–760). Tap to review." Only when the app isn't on screen. */
    private suspend fun notifyReady(id: String) {
        val e = dao.estimate(id) ?: return
        val prefs = settings.settings.first().notifications
        val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        val quiet = NotificationRules.inQuietHours(now, prefs.quietStartMinute, prefs.quietEndMinute)
        if (e.notified || notifier.appInForeground() || !prefs.estimateReadyEnabled || quiet) {
            estimates.markNotified(id)
            return
        }
        val ctx = applicationContext
        val n = NumberFormat.getIntegerInstance()
        val meal = mealNameOf(e)
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
        private const val MIN_DELAY_MS = 30_000L
        private const val KEY_PROBLEM_ID = 1002
    }
}

@Singleton
class WorkManagerEstimateScheduler @Inject constructor(@ApplicationContext private val context: Context) : EstimateScheduler {

    override fun enqueue(estimateId: String, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<EstimateWorker>()
            .setInputData(workDataOf(EstimateWorker.KEY_ID to estimateId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
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

    override suspend fun isScheduled(estimateId: String): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name(estimateId)).first().any { !it.state.isFinished }

    private fun name(id: String) = "estimate-$id"

    companion object {
        const val TAG = "estimate"
    }
}
