package app.forkcast.core.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/**
 * Weekly check-in (owner, 2026-10-19): one look back at the last 7 days — how much was logged
 * and eaten against the budget, where the weight trend went compared with the plan, what
 * maintenance and activity did. Pure math; the data layer gathers the inputs.
 */
object WeeklyCheckIn {

    /** Weight trend within this much of the plan counts as on pace (a week's trend is noisy). */
    const val PACE_TOLERANCE_KG = 0.15

    data class Input(
        /** First and last day of the week, inclusive. */
        val start: LocalDate,
        val end: LocalDate,
        /** kcal eaten per day that has entries. */
        val kcalByDay: Map<LocalDate, Double>,
        /** Days that count as complete (SPEC §5.6). */
        val completeDays: Set<LocalDate>,
        /** Budget per day before activity (the current one; past budgets aren't stored). */
        val budgetKcal: Int,
        /** Activity bonus per day (SPEC §5.2). */
        val bonusByDay: Map<LocalDate, Double>,
        /** Weight trend the day before the week and on its last day (null without weigh-ins). */
        val trendBeforeKg: Double?,
        val trendEndKg: Double?,
        val weighIns: Int,
        /** Planned change per week, negative = losing; null for maintain or no pace. */
        val plannedKgPerWeek: Double?,
        val maintenanceKcal: Double?,
        val maintenanceBeforeKcal: Double?,
        val stepsByDay: Map<LocalDate, Long>,
        val activeKcalByDay: Map<LocalDate, Double>,
    )

    enum class Pace { AHEAD, ON_PACE, BEHIND, NO_DATA }

    data class Summary(
        val start: LocalDate,
        val end: LocalDate,
        val daysLogged: Int,
        val completeDays: Int,
        /** Average intake on logged days. */
        val avgIntakeKcal: Double?,
        /** Average budget including activity on those same days. */
        val avgAllowanceKcal: Double?,
        /** Intake − allowance on average (negative = under). */
        val avgDifferenceKcal: Double?,
        val trendChangeKg: Double?,
        val weighIns: Int,
        val plannedKgPerWeek: Double?,
        val pace: Pace,
        val maintenanceKcal: Double?,
        val maintenanceChangeKcal: Double?,
        val avgSteps: Long?,
        val avgActiveKcal: Double?,
        val totalBonusKcal: Double,
    )

    fun summarize(i: Input): Summary {
        val logged = i.kcalByDay.filterKeys { it in i.start..i.end }
        val avgIntake = logged.values.takeIf { it.isNotEmpty() }?.average()
        val avgAllowance = logged.keys.takeIf { it.isNotEmpty() }?.map { i.budgetKcal + (i.bonusByDay[it] ?: 0.0) }?.average()
        val change = if (i.trendBeforeKg != null && i.trendEndKg != null) i.trendEndKg - i.trendBeforeKg else null
        val steps = i.stepsByDay.filterKeys { it in i.start..i.end }.values
        val active = i.activeKcalByDay.filterKeys { it in i.start..i.end }.values
        return Summary(
            start = i.start,
            end = i.end,
            daysLogged = logged.size,
            completeDays = i.completeDays.count { it in i.start..i.end },
            avgIntakeKcal = avgIntake,
            avgAllowanceKcal = avgAllowance,
            avgDifferenceKcal = if (avgIntake != null && avgAllowance != null) avgIntake - avgAllowance else null,
            trendChangeKg = change,
            weighIns = i.weighIns,
            plannedKgPerWeek = i.plannedKgPerWeek,
            pace = pace(change, i.plannedKgPerWeek, i.weighIns),
            maintenanceKcal = i.maintenanceKcal,
            maintenanceChangeKcal = if (i.maintenanceKcal != null && i.maintenanceBeforeKcal != null) i.maintenanceKcal - i.maintenanceBeforeKcal else null,
            avgSteps = steps.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            avgActiveKcal = active.takeIf { it.isNotEmpty() }?.average(),
            totalBonusKcal = i.bonusByDay.filterKeys { it in i.start..i.end }.values.sum(),
        )
    }

    /** Ahead = more change in the planned direction than planned; maintain = steady within the tolerance. */
    fun pace(changeKg: Double?, plannedKgPerWeek: Double?, weighIns: Int): Pace {
        if (changeKg == null || weighIns < 2) return Pace.NO_DATA
        val planned = plannedKgPerWeek ?: 0.0
        val diff = changeKg - planned
        if (abs(diff) <= PACE_TOLERANCE_KG) return Pace.ON_PACE
        return when {
            planned < 0 -> if (diff < 0) Pace.AHEAD else Pace.BEHIND
            planned > 0 -> if (diff > 0) Pace.AHEAD else Pace.BEHIND
            // Maintaining: any drift outside the tolerance is "behind" (off target).
            else -> Pace.BEHIND
        }
    }

    /** The 7 days that end the day before the check-in day ([checkInDay] on or before [today]). */
    fun lastWeek(today: LocalDate, checkInDay: DayOfWeek): ClosedRange<LocalDate> {
        val checkIn = today.with(TemporalAdjusters.previousOrSame(checkInDay))
        return checkIn.minusDays(7)..checkIn.minusDays(1)
    }
}
