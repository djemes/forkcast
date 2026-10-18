package app.forkcast.core.domain

import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.kcal
import app.forkcast.core.model.sumNutrients
import kotlin.math.max

/** Ring color states (DESIGN.md §2): pine below 80 %, apricot from 80 %, plum when over. */
enum class BudgetState { ON_TRACK, APPROACHING, OVER }

data class DayTotals(
    val nutrients: Nutrients,
    val eatenKcal: Double,
    val budgetKcal: Int,
    val exerciseKcal: Double,
) {
    /** What's available today: budget plus any exercise added back (Phase 3). */
    val allowanceKcal: Double get() = budgetKcal + exerciseKcal
    val remainingKcal: Double get() = allowanceKcal - eatenKcal
    val overKcal: Double get() = max(0.0, -remainingKcal)
    /** Fraction of the allowance eaten, uncapped (1.2 = 20 % over). */
    val fraction: Double get() = if (allowanceKcal <= 0) 0.0 else eatenKcal / allowanceKcal
    val state: BudgetState get() = when {
        eatenKcal > allowanceKcal -> BudgetState.OVER
        fraction >= APPROACHING_FRACTION -> BudgetState.APPROACHING
        else -> BudgetState.ON_TRACK
    }

    operator fun get(key: NutrientKey): Double = nutrients[key] ?: 0.0

    companion object {
        const val APPROACHING_FRACTION = 0.8
    }
}

object DayMath {
    fun totals(entries: List<LogEntry>, budgetKcal: Int, exerciseKcal: Double = 0.0): DayTotals {
        val n = entries.map { it.nutrients }.sumNutrients()
        return DayTotals(n, n.kcal, budgetKcal, exerciseKcal)
    }

    fun mealKcal(entries: List<LogEntry>, mealSlotId: String): Double =
        entries.filter { it.mealSlotId == mealSlotId }.sumOf { it.nutrients.kcal }

    /**
     * Rescales an entry's nutrient snapshot to a new amount. Uses grams when both are known,
     * otherwise the quantity ratio. Never reads the food again, so past days stay as logged.
     */
    fun rescale(entry: LogEntry, newQuantity: Double, newGrams: Double?): Nutrients {
        val ratio = when {
            entry.grams != null && newGrams != null && entry.grams!! > 0 -> newGrams / entry.grams!!
            entry.quantity > 0 -> newQuantity / entry.quantity
            else -> 1.0
        }
        return entry.nutrients.mapValues { (_, v) -> v * ratio }
    }
}
