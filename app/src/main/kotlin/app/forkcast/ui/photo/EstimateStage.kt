package app.forkcast.ui.photo

import app.forkcast.core.data.ai.AiErrorCodes
import app.forkcast.core.data.ai.EstimatePolicy
import app.forkcast.core.data.db.AiEstimateEntity
import app.forkcast.core.data.db.AttemptRecord
import app.forkcast.core.data.db.EstimatePhase
import app.forkcast.core.data.db.EstimateStatus

/**
 * One plain status for an estimate at a time (owner, 2026-10-23: "Sending → Waiting for Gemini →
 * Waiting to retry → Trying the next profile → Done, or Failed"), shared by the review screen and
 * Today's row. Pure, from the stored row, so it's unit-tested.
 */
sealed interface EstimateStage {
    /** Queued: the job hasn't started yet (it runs once there's a connection). */
    data object Starting : EstimateStage

    /**
     * Talking to [profile]: [sent] = the request is out and we're waiting for the answer, since
     * [sinceMs]. [after] is the profile that failed just before in this try ("Trying Flash-Lite").
     */
    data class Asking(val profile: String?, val sent: Boolean, val sinceMs: Long?, val after: AttemptRecord?) : EstimateStage

    /** The model answered; its foods are being matched to USDA. */
    data object Matching : EstimateStage

    /** A slow or busy try failed; the next one is at [nextAtMs]. [attempt] of [maxAttempts] done. */
    data class RetryWaiting(val reason: String?, val attempt: Int, val maxAttempts: Int, val nextAtMs: Long, val last: AttemptRecord?) : EstimateStage

    /** Every profile is out of quota until [nextAtMs]. */
    data class QuotaWaiting(val nextAtMs: Long, val last: AttemptRecord?) : EstimateStage

    data object Offline : EstimateStage

    /** Stopped: [code] says why; [tried] is what this run asked, oldest first. */
    data class Failed(val code: String?, val detail: String?, val tried: List<AttemptRecord>) : EstimateStage

    data object NeedsAnswer : EstimateStage
    data object Done : EstimateStage

    /** Steps of a run for the labeled progress line: 1 send, 2 answer, 3 match. Null when not running. */
    val step: Int?
        get() = when (this) {
            Starting -> 1
            is Asking -> if (sent) 2 else 1
            Matching -> 3
            else -> null
        }

    val isWorking: Boolean get() = step != null

    companion object {
        const val STEPS = 3

        fun of(e: AiEstimateEntity, nowMs: Long): EstimateStage {
            val runStart = e.runStartedAt ?: e.createdAt
            val thisRun = e.attemptLog.filter { it.atMs >= runStart }
            val lastFailure = thisRun.lastOrNull { it.result != AiErrorCodes.OK }
            return when (e.status) {
                EstimateStatus.DONE -> Done
                EstimateStatus.NEEDS_INPUT -> NeedsAnswer
                EstimateStatus.FAILED -> Failed(e.errorCode, e.errorDetail, thisRun)
                EstimateStatus.RUNNING -> when (e.phase) {
                    EstimatePhase.MATCHING -> Matching
                    EstimatePhase.WAITING -> Asking(e.activeProfileName, sent = true, sinceMs = e.phaseSince, after = earlierFailure(thisRun, e))
                    else -> Asking(e.activeProfileName, sent = false, sinceMs = e.phaseSince, after = earlierFailure(thisRun, e))
                }
                EstimateStatus.PENDING -> {
                    val next = e.retryAfter
                    when {
                        e.errorCode == "Network" -> Offline
                        next != null && next - nowMs > EstimatePolicy.SHORT_WAIT_MS -> QuotaWaiting(next, lastFailure)
                        next != null -> RetryWaiting(e.errorCode, e.tries, EstimatePolicy.MAX_TRIES, next, lastFailure)
                        else -> Starting
                    }
                }
            }
        }

        /** A failure from another profile earlier in the same try: the chain moved on from it. */
        private fun earlierFailure(thisRun: List<AttemptRecord>, e: AiEstimateEntity): AttemptRecord? =
            thisRun.lastOrNull()?.takeIf { it.result != AiErrorCodes.OK && it.profileName != e.activeProfileName && e.activeProfileName != null }
    }
}
