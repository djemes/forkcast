package app.forkcast.core.domain

import app.forkcast.core.domain.NutritionConstants.KCAL_PER_KG
import app.forkcast.core.domain.NutritionConstants.MAX_WEEKLY_LOSS_FRACTION
import app.forkcast.core.domain.NutritionConstants.MIN_BUDGET_FEMALE_KCAL
import app.forkcast.core.domain.NutritionConstants.MIN_BUDGET_MALE_KCAL
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.Sex
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** How the user expressed the pace of their goal. The other value is derived live. */
sealed interface GoalPace {
    data class ByDate(val targetDate: LocalDate) : GoalPace
    /** Always positive; direction comes from current vs target weight. */
    data class ByWeeklyRate(val kgPerWeek: Double) : GoalPace
}

data class GoalInput(
    val goalType: GoalType,
    val currentKg: Double,
    val targetKg: Double,
    val pace: GoalPace?,
)

/** Target date and weekly rate kept in sync (SPEC §4.1). */
data class ResolvedPace(val targetDate: LocalDate, val kgPerWeek: Double, val days: Long)

sealed interface Guardrail {
    /** Loss faster than 1% of body weight per week; [onePercentDate] is when a 1%/week pace gets there. */
    data class FastLoss(val kgPerWeek: Double, val maxKgPerWeek: Double, val onePercentDate: LocalDate) : Guardrail
    /** Budget under the sex-specific floor or under BMR. */
    data class LowBudget(val budgetKcal: Int, val floorKcal: Int, val bmrKcal: Int) : Guardrail
}

data class BudgetPlan(
    val tdeeKcal: Double,
    /** Positive = deficit (losing), negative = surplus (gaining). */
    val dailyDeltaKcal: Double,
    val budgetKcal: Int,
    val pace: ResolvedPace?,
    val guardrails: List<Guardrail>,
)

/** Goal → daily budget (SPEC §5.3) with guardrails (SPEC §5.4). */
object GoalPlanner {

    fun resolvePace(currentKg: Double, targetKg: Double, pace: GoalPace, today: LocalDate): ResolvedPace? {
        val diff = abs(currentKg - targetKg)
        if (diff < 1e-6) return null
        return when (pace) {
            is GoalPace.ByDate -> {
                val days = ChronoUnit.DAYS.between(today, pace.targetDate)
                if (days <= 0) null else ResolvedPace(pace.targetDate, diff / days * 7.0, days)
            }
            is GoalPace.ByWeeklyRate -> {
                if (pace.kgPerWeek <= 0) null else {
                    val days = max(1L, ceil(diff / pace.kgPerWeek * 7.0).toLong())
                    ResolvedPace(today.plusDays(days), pace.kgPerWeek, days)
                }
            }
        }
    }

    fun plan(input: GoalInput, tdeeKcal: Double, bmrKcal: Double, sex: Sex, today: LocalDate): BudgetPlan {
        val pace = when (input.goalType) {
            GoalType.MAINTAIN -> null
            else -> input.pace?.let { resolvePace(input.currentKg, input.targetKg, it, today) }
        }
        val dailyDelta = when {
            pace == null -> 0.0
            // Signed: positive when the target is below current weight.
            else -> (input.currentKg - input.targetKg) * KCAL_PER_KG / pace.days
        }
        val budget = EnergyMath.roundBudget(tdeeKcal - dailyDelta)
        return BudgetPlan(
            tdeeKcal = tdeeKcal,
            dailyDeltaKcal = dailyDelta,
            budgetKcal = budget,
            pace = pace,
            guardrails = guardrails(input, pace, budget, bmrKcal, sex, today),
        )
    }

    fun guardrails(
        input: GoalInput,
        pace: ResolvedPace?,
        budgetKcal: Int,
        bmrKcal: Double,
        sex: Sex,
        today: LocalDate,
    ): List<Guardrail> = buildList {
        val losing = input.targetKg < input.currentKg
        if (pace != null && losing) {
            val maxRate = input.currentKg * MAX_WEEKLY_LOSS_FRACTION
            if (pace.kgPerWeek > maxRate + 1e-9) {
                add(Guardrail.FastLoss(pace.kgPerWeek, maxRate, onePercentPaceDate(input.currentKg, input.targetKg, today)))
            }
        }
        val floor = if (sex == Sex.MALE) MIN_BUDGET_MALE_KCAL else MIN_BUDGET_FEMALE_KCAL
        val bmr = EnergyMath.roundBudget(bmrKcal)
        if (budgetKcal < floor || budgetKcal < bmr) add(Guardrail.LowBudget(budgetKcal, floor, bmr))
    }

    /**
     * Date a loss of 1% of current body weight per week would reach the target.
     * Uses a fixed rate (1% of today's weight), matching how the warning is worded.
     */
    fun onePercentPaceDate(currentKg: Double, targetKg: Double, today: LocalDate): LocalDate {
        val rate = currentKg * MAX_WEEKLY_LOSS_FRACTION
        val days = ceil((currentKg - targetKg) / rate * 7.0).toLong()
        return today.plusDays(max(1L, days))
    }
}
