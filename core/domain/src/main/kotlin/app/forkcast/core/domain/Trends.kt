package app.forkcast.core.domain

import app.forkcast.core.domain.NutritionConstants.KCAL_PER_G_CARB
import app.forkcast.core.domain.NutritionConstants.KCAL_PER_G_FAT
import app.forkcast.core.domain.NutritionConstants.KCAL_PER_G_PROTEIN
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.kcal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Trends (SPEC §4.10): calories against the budget, weight trend and goal date, macro split,
 * nutrient report, days logged. Pure math over one period; the data layer gathers the days.
 *
 * Averages use complete days (SPEC §5.6), or every logged day when none is complete, like the
 * weekly check-in: a half-logged day would make intake look low. Today is shown but never
 * averaged, because it isn't over.
 */
object Trends {

    enum class Period(val days: Int) { WEEK(7), MONTH(30), QUARTER(90) }

    /** One logged (or empty) day of the period. */
    data class Day(
        val date: LocalDate,
        /** Sum of the day's entries; null = nothing logged. */
        val nutrients: Nutrients?,
        /** Budget plus that day's activity bonus. */
        val allowanceKcal: Double,
        val complete: Boolean,
    ) {
        val kcal: Double? get() = nutrients?.kcal
    }

    /** The days [today]-period+1 .. [today]. */
    fun range(period: Period, today: LocalDate): ClosedRange<LocalDate> = today.minusDays(period.days - 1L)..today

    /** The days the averages use: complete past days, or every logged past day if none is complete. */
    fun averagedDays(days: List<Day>, today: LocalDate): AveragedDays {
        val logged = days.filter { it.nutrients != null && it.date.isBefore(today) }
        val complete = logged.filter { it.complete }
        return if (complete.isNotEmpty()) AveragedDays(complete, true) else AveragedDays(logged, false)
    }

    data class AveragedDays(val days: List<Day>, val completeOnly: Boolean)

    data class Calories(
        val avgKcal: Double,
        val avgAllowanceKcal: Double,
        /** Intake − allowance (negative = under). */
        val avgDifferenceKcal: Double,
        val daysAveraged: Int,
        val completeOnly: Boolean,
        /** Days over their allowance among the averaged ones. */
        val daysOver: Int,
    )

    fun calories(days: List<Day>, today: LocalDate): Calories? {
        val a = averagedDays(days, today)
        if (a.days.isEmpty()) return null
        val avg = a.days.map { it.kcal ?: 0.0 }.average()
        val allowance = a.days.map { it.allowanceKcal }.average()
        return Calories(
            avgKcal = avg,
            avgAllowanceKcal = allowance,
            avgDifferenceKcal = avg - allowance,
            daysAveraged = a.days.size,
            completeOnly = a.completeOnly,
            daysOver = a.days.count { (it.kcal ?: 0.0) > it.allowanceKcal },
        )
    }

    /** Average intake per calendar week (Monday start) over the averaged days; weeks without any are left out. */
    data class WeekAverage(val start: LocalDate, val avgKcal: Double, val avgAllowanceKcal: Double, val days: Int)

    fun weeklyAverages(days: List<Day>, today: LocalDate): List<WeekAverage> =
        averagedDays(days, today).days
            .groupBy { it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            .toSortedMap()
            .map { (start, list) ->
                WeekAverage(start, list.map { it.kcal ?: 0.0 }.average(), list.map { it.allowanceKcal }.average(), list.size)
            }

    /** Share of energy from each macro, in percent (sums to ~100 when all three are known). */
    data class MacroSplit(val proteinPct: Double, val carbsPct: Double, val fatPct: Double)

    fun macroSplit(proteinG: Double, carbsG: Double, fatG: Double): MacroSplit? {
        val p = proteinG * KCAL_PER_G_PROTEIN
        val c = carbsG * KCAL_PER_G_CARB
        val f = fatG * KCAL_PER_G_FAT
        val total = p + c + f
        if (total <= 0) return null
        return MacroSplit(p / total * 100, c / total * 100, f / total * 100)
    }

    data class Macros(
        val avgProteinG: Double,
        val avgCarbsG: Double,
        val avgFatG: Double,
        val split: MacroSplit?,
        val targetSplit: MacroSplit?,
    )

    fun macros(days: List<Day>, today: LocalDate, targets: Map<NutrientKey, NutrientTarget>): Macros? {
        val a = averagedDays(days, today).days
        if (a.isEmpty()) return null
        fun avg(key: NutrientKey) = a.map { it.nutrients?.get(key) ?: 0.0 }.average()
        val p = avg(NutrientKey.PROTEIN_G)
        val c = avg(NutrientKey.CARBS_G)
        val f = avg(NutrientKey.FAT_G)
        val target = macroSplit(
            targets[NutrientKey.PROTEIN_G]?.amount ?: 0.0,
            targets[NutrientKey.CARBS_G]?.amount ?: 0.0,
            targets[NutrientKey.FAT_G]?.amount ?: 0.0,
        )
        return Macros(p, c, f, macroSplit(p, c, f), target)
    }

    /** [NO_DATA]: none of the averaged entries report the nutrient, so there is nothing to compare. */
    enum class Standing { UNDER, ON_TARGET, OVER, NO_DATA }

    data class NutrientLine(
        val key: NutrientKey,
        val avg: Double,
        val target: NutrientTarget,
        /** avg / target (1.0 = exactly on target). */
        val ratio: Double,
        val standing: Standing,
        /**
         * Share of the averaged energy that came from entries reporting this nutrient. Quick adds and
         * many packaged foods list only a few nutrients, so a low share means the average is too low.
         */
        val coverage: Double,
    ) {
        /** How far from the target this is in the direction that matters: 0 = fine. */
        val miss: Double get() = when (standing) {
            Standing.ON_TARGET -> 0.0
            Standing.NO_DATA -> -1.0
            else -> abs(ratio - 1.0)
        }
    }

    /** Within this of a goal counts as reached (a goal is a floor; 95 % is close enough). */
    const val GOAL_TOLERANCE = 0.05

    /**
     * Every nutrient with a target, average vs target, most off first. A goal is missed only
     * when under it, a limit only when over it. Energy is left out (the calorie chart covers it).
     * Nutrients no averaged entry reports go last, as [Standing.NO_DATA].
     */
    fun nutrientReport(
        days: List<Day>,
        today: LocalDate,
        targets: Map<NutrientKey, NutrientTarget>,
        /** Nutrients each averaged day's entries reported, with the energy those entries carried. */
        coverage: Map<NutrientKey, Double> = emptyMap(),
    ): List<NutrientLine> {
        val a = averagedDays(days, today).days
        if (a.isEmpty()) return emptyList()
        return targets.values
            .filter { it.key != NutrientKey.ENERGY_KCAL && it.amount > 0 }
            .map { t ->
                val avg = a.map { it.nutrients?.get(t.key) ?: 0.0 }.average()
                val ratio = avg / t.amount
                val covered = coverage[t.key] ?: 1.0
                val standing = when {
                    covered <= 0.0 -> Standing.NO_DATA
                    else -> when (t.kind) {
                    TargetKind.GOAL -> if (ratio < 1 - GOAL_TOLERANCE) Standing.UNDER else Standing.ON_TARGET
                    TargetKind.LIMIT -> if (ratio > 1) Standing.OVER else Standing.ON_TARGET
                    }
                }
                NutrientLine(t.key, avg, t, ratio, standing, covered)
            }
            .sortedWith(compareByDescending<NutrientLine> { it.miss }.thenBy { it.key.ordinal })
    }

    /**
     * For each nutrient, the share of energy from entries that report it (0..1), over [entryNutrients]
     * (one map per log entry in the averaged days).
     */
    fun coverage(entryNutrients: List<Nutrients>): Map<NutrientKey, Double> {
        val total = entryNutrients.sumOf { it.kcal }
        if (total <= 0) return emptyMap()
        return NutrientKey.entries.associateWith { key ->
            entryNutrients.filter { key in it }.sumOf { it.kcal } / total
        }
    }

    data class Logging(val daysLogged: Int, val periodDays: Int, val currentStreak: Int, val longestStreak: Int)

    /**
     * Days with at least one entry in the period, and the run of consecutive logged days ending
     * today (or yesterday, so an evening that hasn't been logged yet doesn't break it).
     */
    fun logging(loggedDates: Set<LocalDate>, period: ClosedRange<LocalDate>, today: LocalDate): Logging {
        val inPeriod = loggedDates.count { it in period }
        val periodDays = ChronoUnit.DAYS.between(period.start, period.endInclusive).toInt() + 1
        var day = if (today in loggedDates) today else today.minusDays(1)
        var current = 0
        while (day in loggedDates) {
            current++
            day = day.minusDays(1)
        }
        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in loggedDates.sorted()) {
            run = if (prev != null && d == prev.plusDays(1)) run + 1 else 1
            longest = maxOf(longest, run)
            prev = d
        }
        return Logging(inPeriod, periodDays, current, longest)
    }

    sealed interface GoalProjection {
        data class By(val date: LocalDate) : GoalProjection
        /** At this rate the goal is more than [MAX_PROJECTION_DAYS] away. */
        data object TooFar : GoalProjection
        /** The trend is moving away from the goal (or not moving). */
        data object NotMovingToward : GoalProjection
        data object Reached : GoalProjection
    }

    /** Projections further out than this aren't worth a date. */
    const val MAX_PROJECTION_DAYS = 730L

    /** Within this of the target weight counts as reached. */
    const val REACHED_KG = 0.25

    /** When the trend reaches [targetKg] at the current [weeklyRateKg] (SPEC §4.10). */
    fun projectGoal(trendKg: Double, weeklyRateKg: Double?, targetKg: Double, today: LocalDate): GoalProjection? {
        if (abs(trendKg - targetKg) <= REACHED_KG) return GoalProjection.Reached
        val rate = weeklyRateKg ?: return null
        val toGo = targetKg - trendKg
        if (abs(rate) < 0.01 || toGo * rate < 0) return GoalProjection.NotMovingToward
        val days = ceil(toGo / rate * 7).toLong()
        return if (days > MAX_PROJECTION_DAYS) GoalProjection.TooFar else GoalProjection.By(today.plusDays(days))
    }
}
