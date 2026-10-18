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
    val tdeeKcal: Double,
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
    ): DailyTargets {
        val age = EnergyMath.ageYears(profile.birthDate, today)
        val bmr = EnergyMath.bmrMifflinStJeor(profile.sex, currentKg, profile.heightCm, age)
        val tdee = EnergyMath.tdee(bmr, profile.activityLevel)
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
            plan = plan,
            budgetKcal = budget,
            isCustomBudget = overrideBudget != null,
            targets = NutrientTargets.withOverrides(defaults, overrides - NutrientKey.ENERGY_KCAL),
            guardrails = guardrails,
        )
    }
}
