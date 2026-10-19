package app.forkcast.core.network.ai

import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.ProviderId
import java.time.Instant

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

    /** Profiles the chain would try now, in order. */
    fun usable(profiles: List<AiProfile>, now: Instant): List<AiProfile> =
        profiles.filter { it.enabled && !it.needsAttention && (it.cooldownUntil ?: 0) <= now.toEpochMilli() }.sortedBy { it.order }

    suspend fun run(
        profiles: List<AiProfile>,
        now: Instant,
        estimatorFor: (AiProfile) -> MealEstimator,
        requestFor: (AiProfile) -> MealEstimateRequest,
    ): Outcome {
        val attempts = mutableListOf<Attempt>()
        val candidates = usable(profiles, now)
        if (candidates.isEmpty()) {
            val waiting = profiles.filter { it.enabled && !it.needsAttention }.mapNotNull { it.cooldownUntil }.minOrNull()
            return Outcome.Failure(null, waiting, retryable = waiting != null, attempts)
        }
        var last: AiError? = null
        for (profile in candidates) {
            val estimator = estimatorFor(profile)
            var result = estimator.estimate(requestFor(profile))
            // Unusable output gets one more try on the same profile, then the chain stops.
            if ((result.exceptionOrNull() as? AiError) is AiError.BadResponse) result = estimator.estimate(requestFor(profile))
            val error = result.exceptionOrNull()?.let { it as? AiError ?: AiError.BadResponse(null, it.message) }
            if (error == null) {
                val r = result.getOrThrow()
                attempts += Attempt(profile, null, null, false, r)
                return Outcome.Success(profile, r, attempts)
            }
            last = error
            when (error) {
                is AiError.InvalidKey -> attempts += Attempt(profile, error, null, needsAttention = true)
                is AiError.RateLimited, is AiError.QuotaExhausted ->
                    attempts += Attempt(profile, error, cooldownFor(profile, error, now), needsAttention = false)
                is AiError.Server, is AiError.Timeout, is AiError.ModelNotFound, is AiError.InvalidRequest ->
                    attempts += Attempt(profile, error, null, needsAttention = false)
                // Offline: every profile would fail the same way. Wait for the network.
                is AiError.Network -> {
                    attempts += Attempt(profile, error, null, false)
                    return Outcome.Failure(error, null, retryable = true, attempts)
                }
                // Another provider would likely fail the same way: show the error.
                is AiError.ContentRefused, is AiError.BadResponse -> {
                    attempts += Attempt(profile, error, null, false)
                    return Outcome.Failure(error, null, retryable = false, attempts)
                }
            }
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
