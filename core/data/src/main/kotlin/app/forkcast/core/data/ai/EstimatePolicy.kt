package app.forkcast.core.data.ai

import app.forkcast.core.data.db.AiComponentEntity
import app.forkcast.core.data.db.ComponentSource
import app.forkcast.core.model.Food
import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.forGrams
import app.forkcast.core.model.kcal

/**
 * When an estimate tries again and when it gives up (owner, 2026-10-23: an estimate must never
 * sit in limbo). One "try" is a pass through the profile chain.
 *
 * - Busy or slow (timeout, 5xx, a per-minute 429): up to [MAX_TRIES] tries, [backoffMs] apart
 *   (or the provider's own delay), within [TRANSIENT_CAP_MS] of the run's start; then Failed.
 * - Offline: waits for a connection; out of daily quota on every profile: waits for the reset.
 *   Both say so with the time, and give up after [HARD_CAP_MS] (a day: the longest quota wait).
 * The user can Retry now, try another profile or log manually at any point.
 */
object EstimatePolicy {
    const val MAX_TRIES = 3
    const val TRANSIENT_CAP_MS = 15 * 60_000L
    const val HARD_CAP_MS = 25 * 60 * 60_000L
    /** A quota wait longer than this is a daily quota, not a busy minute. */
    const val SHORT_WAIT_MS = 10 * 60_000L
    /** A RUNNING estimate with no live job for this long was killed with the app. */
    const val LATE_MS = 10 * 60_000L
    /** Keep the newest records only, so the row stays small. */
    const val MAX_ATTEMPT_RECORDS = 30

    /** 30 s, 2 min, 5 min after the 1st, 2nd and 3rd try. */
    fun backoffMs(tries: Int): Long = when (tries) {
        0, 1 -> 30_000L
        2 -> 120_000L
        else -> 300_000L
    }

    /** Whether another automatic try fits: under [MAX_TRIES] and inside [TRANSIENT_CAP_MS]. */
    fun canRetryTransient(tries: Int, runStartMs: Long, nowMs: Long): Boolean =
        tries < MAX_TRIES && nowMs - runStartMs < TRANSIENT_CAP_MS

    fun pastHardCap(runStartMs: Long, atMs: Long): Boolean = atMs - runStartMs > HARD_CAP_MS
}

/** Review screen edits, applied to a draft copy of the components until Save (owner, 2026-10-23). */
object ComponentEdits {
    fun per100(c: AiComponentEntity): Nutrients =
        if (c.useSource == ComponentSource.USDA && c.usdaPer100g != null) c.usdaPer100g else c.aiPer100g

    fun withGrams(c: AiComponentEntity, grams: Double): AiComponentEntity {
        val g = grams.coerceAtLeast(0.0)
        return c.copy(grams = g, nutrientsUsed = per100(c).forGrams(g))
    }

    fun withSource(c: AiComponentEntity, source: ComponentSource): AiComponentEntity {
        if (source == ComponentSource.USDA && c.usdaPer100g == null) return c
        val next = c.copy(useSource = source)
        return next.copy(nutrientsUsed = per100(next).forGrams(next.grams))
    }

    /** [food] is already cached locally (it has its local id). */
    fun withMatch(c: AiComponentEntity, food: Food): AiComponentEntity = c.copy(
        matchedFoodId = food.id, matchedFdcId = food.sourceId, matchedName = food.name, matchScore = ComponentMatcher.USER_CHOICE_SCORE,
        usdaPer100g = food.nutrientsPer100g, useSource = ComponentSource.USDA,
        nutrientsUsed = food.nutrientsPer100g.forGrams(c.grams),
    )

    data class Totals(val low: Double, val expected: Double, val high: Double)

    /** Expected = sum of components; range = each component's kcal/g × its low/high grams. All × [portion]. */
    fun totals(components: List<AiComponentEntity>, portion: Double = 1.0): Totals {
        var low = 0.0
        var exp = 0.0
        var high = 0.0
        for (c in components) {
            val kcal = c.nutrientsUsed.kcal
            val perG = if (c.grams > 0) kcal / c.grams else 0.0
            exp += kcal
            low += perG * minOf(c.gramsLow, c.grams)
            high += perG * maxOf(c.gramsHigh, c.grams)
        }
        return Totals(low * portion, exp * portion, high * portion)
    }
}
