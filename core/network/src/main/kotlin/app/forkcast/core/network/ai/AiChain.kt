package app.forkcast.core.network.ai

import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.ProviderId
import java.time.Instant
import kotlin.time.TimeSource

/**
 * The profile fallback chain (AI_PROVIDERS.md §2.6). Pure logic: the caller supplies the
 * estimators and persists what [Outcome.attempts] reports (usage, cooldowns, needs-attention).
 */
object AiChain {

    data class Attempt(
        val profile: AiProfile,
        val error: AiError?,
        /** Skip this profile until then (quota window), or null. */
        val cooldownUntil: Long?,
        /** Key rejected: mark the profile and notify once. */
        val needsAttention: Boolean,
        val result: MealEstimateResult? = null,
        /** When the request started (epoch ms) and how long it took, for the review screen's Details. */
        val startedAtMs: Long = 0,
        val durationMs: Long = 0,
    )

    sealed interface Outcome {
        val attempts: List<Attempt>

        data class Success(val profile: AiProfile, val result: MealEstimateResult, override val attempts: List<Attempt>) : Outcome

        /**
         * Nothing produced an estimate. [retryAtMs] is when trying again makes sense (earliest
         * quota reset, or null = as soon as conditions change, e.g. network back).
         * [finalError] is what the user should read; [retryable] false means a retry with the
         * same input will fail the same way (refusal, unusable output) and needs the user.
         */
        data class Failure(
            val finalError: AiError?,
            val retryAtMs: Long?,
            val retryable: Boolean,
            override val attempts: List<Attempt>,
        ) : Outcome
    }

    /** Profiles the chain would try now, in order. [ignoreCooldown]: the user tapped Retry now. */
    fun usable(profiles: List<AiProfile>, now: Instant, ignoreCooldown: Boolean = false): List<AiProfile> =
        profiles.filter { it.enabled && !it.needsAttention && (ignoreCooldown || (it.cooldownUntil ?: 0) <= now.toEpochMilli()) }.sortedBy { it.order }

    /**
     * One pass through the chain. [only]: "Try with another profile" (that profile alone, even if
     * it's paused); [onAttempt] is told before each profile is asked, so the screen can say who.
     */
    suspend fun run(
        profiles: List<AiProfile>,
        now: Instant,
        estimatorFor: (AiProfile) -> MealEstimator,
        requestFor: (AiProfile) -> MealEstimateRequest,
        only: String? = null,
        ignoreCooldown: Boolean = false,
        onAttempt: suspend (AiProfile) -> Unit = {},
        /** Told after each request, so a failure shows ("Trying Flash-Lite…") while the chain goes on. */
        onResult: suspend (Attempt) -> Unit = {},
    ): Outcome {
        val attempts = mutableListOf<Attempt>()
        var reported = 0
        suspend fun report() {
            while (reported < attempts.size) onResult(attempts[reported++])
        }
        val candidates = if (only != null) {
            profiles.filter { it.id == only && !it.needsAttention }
        } else {
            usable(profiles, now, ignoreCooldown)
        }
        if (candidates.isEmpty()) {
            val waiting = profiles.filter { it.enabled && !it.needsAttention }.mapNotNull { it.cooldownUntil }.minOrNull()
            return Outcome.Failure(null, waiting, retryable = waiting != null, attempts)
        }
        var last: AiError? = null
        val startMs = now.toEpochMilli()
        val clock = TimeSource.Monotonic.markNow()
        for (profile in candidates) {
            val estimator = estimatorFor(profile)
            onAttempt(profile)
            val began = clock.elapsedNow().inWholeMilliseconds
            var result = estimator.estimate(requestFor(profile))
            // Unusable output gets one more try on the same profile, then the chain stops.
            if ((result.exceptionOrNull() as? AiError) is AiError.BadResponse) {
                val firstError = result.exceptionOrNull() as AiError
                attempts += Attempt(profile, firstError, null, false, startedAtMs = startMs + began, durationMs = clock.elapsedNow().inWholeMilliseconds - began)
                result = estimator.estimate(requestFor(profile))
            }
            val error = result.exceptionOrNull()?.let { it as? AiError ?: AiError.BadResponse(null, it.message) }
            val at = startMs + began
            val took = clock.elapsedNow().inWholeMilliseconds - began
            if (error == null) {
                val r = result.getOrThrow()
                attempts += Attempt(profile, null, null, false, r, at, took)
                report()
                return Outcome.Success(profile, r, attempts)
            }
            last = error
            when (error) {
                is AiError.InvalidKey -> attempts += Attempt(profile, error, null, needsAttention = true, startedAtMs = at, durationMs = took)
                is AiError.RateLimited, is AiError.QuotaExhausted ->
                    attempts += Attempt(profile, error, cooldownFor(profile, error, now), needsAttention = false, startedAtMs = at, durationMs = took)
                is AiError.Server, is AiError.Timeout, is AiError.ModelNotFound, is AiError.InvalidRequest ->
                    attempts += Attempt(profile, error, null, needsAttention = false, startedAtMs = at, durationMs = took)
                // Offline: every profile would fail the same way. Wait for the network.
                is AiError.Network -> {
                    attempts += Attempt(profile, error, null, false, startedAtMs = at, durationMs = took)
                    report()
                    return Outcome.Failure(error, null, retryable = true, attempts)
                }
                // Another provider would likely fail the same way: show the error.
                is AiError.ContentRefused, is AiError.BadResponse -> {
                    attempts += Attempt(profile, error, null, false, startedAtMs = at, durationMs = took)
                    report()
                    return Outcome.Failure(error, null, retryable = false, attempts)
                }
            }
            report()
        }
        val retryAt = attempts.mapNotNull { it.cooldownUntil }.minOrNull()
        return Outcome.Failure(last, retryAt, retryable = true, attempts)
    }

    fun cooldownFor(profile: AiProfile, error: AiError, now: Instant): Long = when (error) {
        is AiError.RateLimited -> now.toEpochMilli() + (error.retryAfterMs ?: QuotaWindows.SHORT_COOLDOWN_MS)
        is AiError.QuotaExhausted -> error.resetsAtMs ?: if (profile.provider == ProviderId.GEMINI) {
            QuotaWindows.nextMidnightPacific(now).toEpochMilli()
        } else {
            now.toEpochMilli() + 60 * 60_000L
        }
        else -> now.toEpochMilli()
    }
}
