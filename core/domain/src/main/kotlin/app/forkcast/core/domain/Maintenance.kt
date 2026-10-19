package app.forkcast.core.domain

import app.forkcast.core.domain.NutritionConstants.KCAL_PER_KG
import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.DayCompleteness
import app.forkcast.core.model.ExerciseMode
import app.forkcast.core.model.TdeeConfidence
import app.forkcast.core.model.UserProfile
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Exercise calories (SPEC §5.2, Phase 3). */
object ExerciseMath {

    /** Activity factor for the formula: Added back starts from sedentary and adds exercise per day. */
    fun activityFactor(profile: UserProfile): Double =
        if (profile.exerciseMode == ExerciseMode.ADDED_BACK) ActivityLevel.SEDENTARY.factor else profile.activityLevel.factor

    /** What a day's synced active calories add to its budget: nothing when Included. */
    fun bonusKcal(profile: UserProfile, activeKcal: Double?): Double =
        if (profile.exerciseMode != ExerciseMode.ADDED_BACK || activeKcal == null) 0.0
        else (activeKcal.coerceAtLeast(0.0) * profile.exerciseAddBackPct / 100.0).roundToInt().toDouble()
}

/**
 * Adaptive maintenance (SPEC §5.6). The estimate is all-in maintenance: what the scale says the
 * body burned, exercise included. In Added-back mode the budget subtracts the average exercise
 * bonus from it, since that bonus is then added back day by day (owner, 2026-10-19: no double
 * counting).
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
