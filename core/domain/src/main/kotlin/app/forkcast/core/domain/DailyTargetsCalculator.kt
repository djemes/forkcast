package app.forkcast.core.domain

import app.forkcast.core.model.Goal
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.UserProfile
import java.time.LocalDate

/** Everything the dashboard and "How we calculated this" need for one day. */
data class DailyTargets(
    val ageYears: Int,
    val currentKg: Double,
    val bmrKcal: Double,
    /** Maintenance the budget is built on (formula, or adaptive minus the average exercise bonus). */
    val tdeeKcal: Double,
    /** The formula's maintenance (BMR × activity level). */
    val formulaTdeeKcal: Double,
    /** The adaptive all-in estimate when it is in use (SPEC §5.6), else null. */
    val adaptiveTdeeKcal: Double? = null,
    /** Average daily activity bonus subtracted from [adaptiveTdeeKcal] (0 when the bonus is off). */
    val avgExerciseBonusKcal: Double = 0.0,
    /** The goal-derived plan, before any user override of the calorie budget. */
    val plan: BudgetPlan,
    /** The budget actually used: the plan's, or the user's override. */
    val budgetKcal: Int,
    val isCustomBudget: Boolean,
    val targets: Map<NutrientKey, NutrientTarget>,
    /** Guardrails re-checked against [budgetKcal]. */
    val guardrails: List<Guardrail>,
)

object DailyTargetsCalculator {

    fun paceOf(goal: Goal): GoalPace? = when {
        goal.goalType == GoalType.MAINTAIN -> null
        goal.weeklyRateKg != null -> GoalPace.ByWeeklyRate(goal.weeklyRateKg!!)
        goal.targetDate != null -> GoalPace.ByDate(goal.targetDate!!)
        else -> null
    }

    fun compute(
        profile: UserProfile,
        goal: Goal,
        currentKg: Double,
        today: LocalDate,
        overrides: Map<NutrientKey, Double> = emptyMap(),
        /** Adaptive all-in maintenance to use instead of the formula (null = formula). */
        adaptiveKcal: Double? = null,
        /** Average daily activity bonus over the estimate's window (0 when the bonus is off). */
        avgExerciseBonusKcal: Double = 0.0,
    ): DailyTargets {
        val age = EnergyMath.ageYears(profile.birthDate, today)
        val bmr = EnergyMath.bmrMifflinStJeor(profile.sex, currentKg, profile.heightCm, age)
        val formula = EnergyMath.tdee(bmr, profile.activityLevel)
        // Adaptive maintenance includes activity; the bonus is added per day, so take its average out once.
        val bonus = if (adaptiveKcal != null) avgExerciseBonusKcal else 0.0
        val tdee = adaptiveKcal?.let { it - bonus } ?: formula
        val input = GoalInput(goal.goalType, currentKg, goal.targetWeightKg, paceOf(goal))
        val plan = GoalPlanner.plan(input, tdee, bmr, profile.sex, today)

        val overrideBudget = overrides[NutrientKey.ENERGY_KCAL]?.let { EnergyMath.roundBudget(it) }
        val budget = overrideBudget ?: plan.budgetKcal
        val guardrails = if (overrideBudget == null) plan.guardrails else
            GoalPlanner.guardrails(input, plan.pace, budget, bmr, profile.sex, today)

        val defaults = NutrientTargets.defaults(budget, currentKg, goal.targetWeightKg)
        return DailyTargets(
            ageYears = age,
            currentKg = currentKg,
            bmrKcal = bmr,
            tdeeKcal = tdee,
            formulaTdeeKcal = formula,
            adaptiveTdeeKcal = adaptiveKcal,
            avgExerciseBonusKcal = bonus,
            plan = plan,
            budgetKcal = budget,
            isCustomBudget = overrideBudget != null,
            targets = NutrientTargets.withOverrides(defaults, overrides - NutrientKey.ENERGY_KCAL),
            guardrails = guardrails,
        )
    }
}
