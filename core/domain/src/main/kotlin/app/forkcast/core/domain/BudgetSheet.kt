package app.forkcast.core.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The "Today's budget" sheet behind the calorie ring (owner, 2026-10-24): how today's number is
 * built, the outlook (week so far), and what fits in what's left, from the user's own history.
 */
object BudgetSheet {

    /** Within this of the budget on average, the week reads as "on budget". */
    const val ON_BUDGET_MARGIN_KCAL = 25.0
    /** Up to this many "what fits" suggestions. */
    const val MAX_FITS = 4
    /** Smaller things (coffee, a splash of milk) don't make useful suggestions. */
    const val MIN_FIT_KCAL = 50.0

    /**
     * How the budget is built: maintenance minus the goal's daily deficit (plus for a gain) gives
     * the base budget; a custom budget replaces both. The deficit is maintenance minus the budget
     * as shown (both rounded), so the lines always add up on screen.
     */
    data class Equation(
        val maintenanceKcal: Int?,
        /** From the weight trend (adaptive), else the formula. */
        val adaptive: Boolean,
        /** Positive = deficit (losing), negative = surplus (gaining); null with a custom budget or no goal change. */
        val goalDeltaKcal: Int?,
        val budgetKcal: Int,
        val customBudget: Boolean,
        val activityBonusKcal: Int,
        val eatenKcal: Int,
    ) {
        val remainingKcal: Int get() = budgetKcal + activityBonusKcal - eatenKcal
    }

    fun equation(targets: DailyTargets, totals: DayTotals): Equation {
        val maintenance = targets.tdeeKcal.roundToInt()
        val delta = (maintenance - targets.budgetKcal).takeIf { !targets.isCustomBudget && abs(it) >= 1 }
        return Equation(
            maintenanceKcal = maintenance.takeIf { !targets.isCustomBudget },
            adaptive = targets.adaptiveTdeeKcal != null,
            goalDeltaKcal = delta,
            budgetKcal = targets.budgetKcal,
            customBudget = targets.isCustomBudget,
            activityBonusKcal = totals.exerciseKcal.roundToInt(),
            eatenKcal = totals.eatenKcal.roundToInt(),
        )
    }

    /** One past day this week: what was eaten and what was available (budget plus that day's activity bonus). */
    data class Day(val date: LocalDate, val eatenKcal: Double, val allowanceKcal: Double)

    /** The week so far: [days] logged days before today, eating [averageDifferenceKcal] over (+) or under (−) a day. */
    data class WeekSoFar(val days: Int, val averageDifferenceKcal: Double) {
        val onBudget: Boolean get() = abs(averageDifferenceKcal) < ON_BUDGET_MARGIN_KCAL
    }

    /**
     * Days of this week (from [firstDayOfWeek]) before [today] that have anything logged; null when
     * there are none yet (the first day of the week, or nothing logged).
     */
    fun weekSoFar(days: List<Day>, today: LocalDate, firstDayOfWeek: DayOfWeek): WeekSoFar? {
        val start = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val week = days.filter { !it.date.isBefore(start) && it.date.isBefore(today) && it.eatenKcal > 0 }
        if (week.isEmpty()) return null
        return WeekSoFar(week.size, week.map { it.eatenKcal - it.allowanceKcal }.average())
    }

    /** A food or saved meal from the user's history, at the amount it's logged with. */
    data class Candidate(val key: String, val name: String, val kcal: Double, val proteinG: Double, val savedMeal: Boolean)

    /**
     * Protein is behind when a larger share of the protein target is still to go than of the
     * calories: eating as usual from here would leave protein short.
     */
    fun proteinBehind(proteinEatenG: Double, proteinTargetG: Double, eatenKcal: Double, allowanceKcal: Double): Boolean {
        if (proteinTargetG <= 0 || allowanceKcal <= 0) return false
        val proteinLeft = (1 - proteinEatenG / proteinTargetG).coerceIn(0.0, 1.0)
        val kcalLeft = (1 - eatenKcal / allowanceKcal).coerceIn(0.0, 1.0)
        return proteinLeft > kcalLeft + PROTEIN_MARGIN
    }

    /**
     * What fits in [remainingKcal]: candidates of at least [MIN_FIT_KCAL] that fit, one per name.
     * In [candidates]' order (the caller lists recent first), or the most protein per calorie first
     * when [proteinBehind]. Empty when nothing is left.
     */
    fun whatFits(candidates: List<Candidate>, remainingKcal: Double, proteinBehind: Boolean, limit: Int = MAX_FITS): List<Candidate> {
        if (remainingKcal < MIN_FIT_KCAL) return emptyList()
        val fits = candidates
            .filter { it.kcal >= MIN_FIT_KCAL && it.kcal <= remainingKcal }
            .distinctBy { it.name.trim().lowercase() }
        val ordered = if (proteinBehind) fits.sortedByDescending { it.proteinG / it.kcal } else fits
        return ordered.take(limit)
    }

    private const val PROTEIN_MARGIN = 0.1
}
