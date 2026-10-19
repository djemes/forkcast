package app.forkcast.core.domain

import app.forkcast.core.domain.NutritionConstants.KCAL_PER_KG
import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.DayCompleteness
import app.forkcast.core.model.TdeeConfidence
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** One day's activity and what it adds to that day's budget (SPEC §5.2). */
data class ActivityBonus(
    /** Active calories Health Connect reported for the day (null = none). */
    val activeKcal: Double?,
    /** Average daily active calories over the days before (null until there are enough days). */
    val usualKcal: Double?,
    /** Days of synced activity behind [usualKcal] (up to 28). */
    val baselineDays: Int,
    /** Active calories above usual, never below 0. */
    val extraKcal: Double,
    /** The part counted toward the budget: half the extra, capped. 0 when the bonus is off. */
    val bonusKcal: Double,
    /** The activity bonus setting. */
    val enabled: Boolean,
) {
    /** A usual exists but rests on fewer than 28 days. */
    val partialBaseline: Boolean get() = usualKcal != null && baselineDays < NutritionConstants.ACTIVITY_BASELINE_DAYS

    companion object {
        val NONE = ActivityBonus(null, null, 0, 0.0, 0.0, enabled = false)
    }
}

/**
 * Activity bonus (SPEC §5.2, owner 2026-10-19). The base budget (activity level, or adaptive
 * maintenance) already covers a usual day, so only activity above usual adds budget:
 * bonus = 50 % × (active − usual), ≥ 0, ≤ 500 kcal, usual = the average over the 28 days before.
 */
object ActivityBonusMath {

    /** @param activeByDate synced active calories per day (days without data are absent). */
    fun forDay(date: LocalDate, activeByDate: Map<LocalDate, Double>, enabled: Boolean): ActivityBonus {
        val active = activeByDate[date]
        val from = date.minusDays(NutritionConstants.ACTIVITY_BASELINE_DAYS.toLong())
        val history = activeByDate.filterKeys { !it.isBefore(from) && it.isBefore(date) }.values
        val usual = if (history.size >= NutritionConstants.ACTIVITY_BASELINE_MIN_DAYS) history.average() else null
        val extra = if (active != null && usual != null) (active - usual).coerceAtLeast(0.0) else 0.0
        val bonus = if (enabled) (extra * NutritionConstants.ACTIVITY_BONUS_SHARE).coerceAtMost(NutritionConstants.ACTIVITY_BONUS_CAP_KCAL) else 0.0
        return ActivityBonus(active, usual, history.size, extra.roundToInt().toDouble(), bonus.roundToInt().toDouble(), enabled)
    }

    /**
     * Average daily bonus over [window] (missing days count as 0). Adaptive maintenance already
     * contains the activity of the days it measured, bonus days included, so the budget takes
     * this average out of it once and adds each day's own bonus back.
     */
    fun averageBonus(window: ClosedRange<LocalDate>, activeByDate: Map<LocalDate, Double>, enabled: Boolean): Double {
        if (!enabled) return 0.0
        var d = window.start
        var sum = 0.0
        var days = 0
        while (!d.isAfter(window.endInclusive)) {
            sum += forDay(d, activeByDate, true).bonusKcal
            days++
            d = d.plusDays(1)
        }
        return if (days == 0) 0.0 else sum / days
    }
}

/**
 * Which activity level matches synced activity (SPEC §5.2): total ≈ (resting + active) plus
 * about 10 % for digesting food, so the factor ≈ 1.1 × (1 + active / BMR); the nearest level wins.
 */
object ActivityLevelAdvice {
    const val MIN_DAYS = 14

    fun factorFor(bmrKcal: Double, avgActiveKcal: Double): Double = 1.1 * (1 + avgActiveKcal / bmrKcal)

    fun suggest(bmrKcal: Double, avgActiveKcal: Double): ActivityLevel {
        val factor = factorFor(bmrKcal, avgActiveKcal)
        return ActivityLevel.entries.minBy { kotlin.math.abs(it.factor - factor) }
    }
}

/**
 * Adaptive maintenance (SPEC §5.6). The estimate is all-in maintenance: what the scale says the
 * body burned, activity included. With the activity bonus on, the budget takes the window's
 * average bonus out of it, since each day's bonus is added back on that day (no double counting).
 */
object AdaptiveTdee {

    const val WINDOW_DAYS = 28
    const val MIN_COMPLETE_DAYS = 14
    const val MIN_WEIGH_INS = 6
    const val MAX_STEP_KCAL = 150.0
    const val STEP_FRACTION = 0.3
    const val UPDATE_EVERY_DAYS = 7L

    /** A day's log, for the completeness rule. */
    data class DayLog(val date: LocalDate, val kcal: Double, val mealsLogged: Int, val completeness: DayCompleteness = DayCompleteness.AUTO)

    data class Estimate(
        val completeDays: Int,
        val weighIns: Int,
        /** Null when not eligible. */
        val observedKcal: Double?,
        /** The new maintenance after the weekly step; null when not eligible. */
        val kcal: Double?,
        val confidence: TdeeConfidence?,
    ) {
        val eligible get() = kcal != null
    }

    /** User's mark wins; otherwise ≥ 2 meals and ≥ 1,000 kcal. */
    fun isComplete(day: DayLog): Boolean = when (day.completeness) {
        DayCompleteness.COMPLETE -> true
        DayCompleteness.INCOMPLETE -> false
        DayCompleteness.AUTO -> day.mealsLogged >= 2 && day.kcal >= 1000.0
    }

    /** The trailing window: the 28 days before [today] (today isn't finished). */
    fun window(today: LocalDate): ClosedRange<LocalDate> = today.minusDays(WINDOW_DAYS.toLong())..today.minusDays(1)

    fun isDue(lastEstimateDate: LocalDate?, today: LocalDate): Boolean =
        lastEstimateDate == null || ChronoUnit.DAYS.between(lastEstimateDate, today) >= UPDATE_EVERY_DAYS

    /**
     * @param days logs inside [window] (days without a log may be missing)
     * @param weighIns every weigh-in up to the end of the window (earlier ones seed the trend)
     * @param previousKcal the last estimate, or the formula's all-in maintenance the first time
     */
    fun estimate(today: LocalDate, days: List<DayLog>, weighIns: List<WeighIn>, zone: ZoneId, previousKcal: Double): Estimate {
        val window = window(today)
        val complete = days.filter { it.date in window && isComplete(it) }
        val inWindow = weighIns.filter { it.instant.atZone(zone).toLocalDate() in window }
        val weighInDays = inWindow.map { it.instant.atZone(zone).toLocalDate() }.distinct().size
        if (complete.size < MIN_COMPLETE_DAYS || weighInDays < MIN_WEIGH_INS) {
            return Estimate(complete.size, weighInDays, null, null, null)
        }
        val trend = WeightTrend.daily(weighIns.filter { !it.instant.atZone(zone).toLocalDate().isAfter(window.endInclusive) }, zone, window.endInclusive)
        // Trend at the day before the window, or where the trend starts if it starts inside it.
        val start = trend.lastOrNull { it.date.isBefore(window.start) } ?: trend.first()
        val end = trend.last()
        val span = ChronoUnit.DAYS.between(start.date, end.date).coerceAtLeast(1)
        val avgIntake = complete.sumOf { it.kcal } / complete.size
        val observed = avgIntake - (end.trendKg - start.trendKg) * KCAL_PER_KG / span
        val step = (STEP_FRACTION * (observed - previousKcal)).coerceIn(-MAX_STEP_KCAL, MAX_STEP_KCAL)
        return Estimate(complete.size, weighInDays, observed, previousKcal + step, confidence(complete.size, weighInDays))
    }

    /** By data completeness over the 28 days (DECISIONS.md 2026-10-19). */
    fun confidence(completeDays: Int, weighInDays: Int): TdeeConfidence = when {
        completeDays >= 24 && weighInDays >= 16 -> TdeeConfidence.HIGH
        completeDays >= 20 && weighInDays >= 10 -> TdeeConfidence.MEDIUM
        else -> TdeeConfidence.LOW
    }
}
