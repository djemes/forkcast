package app.forkcast.core.domain

import app.forkcast.core.model.GoalType
import app.forkcast.core.model.Sex
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class GoalPlannerTest {

    private val today = LocalDate.of(2026, 10, 18)
    private val tdee = 2836.5 // male, 90 kg, 180 cm, 40 y, moderate
    private val bmr = 1830.0

    @Test fun `weekly rate gives date and budget`() {
        val plan = GoalPlanner.plan(
            GoalInput(GoalType.LOSE, 90.0, 80.0, GoalPace.ByWeeklyRate(0.5)), tdee, bmr, Sex.MALE, today,
        )
        // 10 kg at 0.5 kg/week = 140 days; 10 * 7700 / 140 = 550 kcal/day
        assertThat(plan.pace!!.days).isEqualTo(140)
        assertThat(plan.pace!!.targetDate).isEqualTo(today.plusDays(140))
        assertThat(plan.dailyDeltaKcal).isWithin(1e-9).of(550.0)
        assertThat(plan.budgetKcal).isEqualTo(2290)
        assertThat(plan.guardrails).isEmpty()
    }

    @Test fun `target date and weekly rate stay in sync`() {
        val byRate = GoalPlanner.resolvePace(90.0, 80.0, GoalPace.ByWeeklyRate(0.5), today)!!
        val byDate = GoalPlanner.resolvePace(90.0, 80.0, GoalPace.ByDate(byRate.targetDate), today)!!
        assertThat(byDate.kgPerWeek).isWithin(1e-9).of(0.5)
        assertThat(byDate.days).isEqualTo(byRate.days)
    }

    @Test fun `gaining adds a surplus`() {
        val plan = GoalPlanner.plan(
            GoalInput(GoalType.GAIN, 70.0, 75.0, GoalPace.ByWeeklyRate(0.25)), 2500.0, 1600.0, Sex.MALE, today,
        )
        // 5 kg at 0.25/week = 140 days; surplus 5 * 7700 / 140 = 275
        assertThat(plan.dailyDeltaKcal).isWithin(1e-9).of(-275.0)
        assertThat(plan.budgetKcal).isEqualTo(2780)
        assertThat(plan.guardrails).isEmpty()
    }

    @Test fun `maintain uses tdee`() {
        val plan = GoalPlanner.plan(GoalInput(GoalType.MAINTAIN, 90.0, 90.0, null), tdee, bmr, Sex.MALE, today)
        assertThat(plan.budgetKcal).isEqualTo(2840)
        assertThat(plan.pace).isNull()
    }

    @Test fun `fast loss warns with the one percent date`() {
        val plan = GoalPlanner.plan(
            GoalInput(GoalType.LOSE, 90.0, 80.0, GoalPace.ByWeeklyRate(1.0)), tdee, bmr, Sex.MALE, today,
        )
        val fast = plan.guardrails.filterIsInstance<Guardrail.FastLoss>().single()
        assertThat(fast.maxKgPerWeek).isWithin(1e-9).of(0.9)
        // 10 kg at 0.9 kg/week = 77.8 weeks... days: ceil(10 / 0.9 * 7) = 78
        assertThat(fast.onePercentDate).isEqualTo(today.plusDays(78))
    }

    @Test fun `exactly one percent per week does not warn`() {
        val plan = GoalPlanner.plan(
            GoalInput(GoalType.LOSE, 100.0, 90.0, GoalPace.ByWeeklyRate(1.0)), 3000.0, 2000.0, Sex.MALE, today,
        )
        assertThat(plan.guardrails.filterIsInstance<Guardrail.FastLoss>()).isEmpty()
    }

    @Test fun `low budget warns below floor and below bmr`() {
        // Female, 60 kg, 165 cm, 30 y: BMR 1320.25, sedentary TDEE 1584.3
        val bmrF = EnergyMath.bmrMifflinStJeor(Sex.FEMALE, 60.0, 165.0, 30)
        val tdeeF = bmrF * 1.2
        val plan = GoalPlanner.plan(
            GoalInput(GoalType.LOSE, 60.0, 50.0, GoalPace.ByWeeklyRate(1.0)), tdeeF, bmrF, Sex.FEMALE, today,
        )
        val low = plan.guardrails.filterIsInstance<Guardrail.LowBudget>().single()
        assertThat(low.floorKcal).isEqualTo(1200)
        assertThat(low.budgetKcal).isEqualTo(480)
        assertThat(plan.guardrails.filterIsInstance<Guardrail.FastLoss>()).hasSize(1)
    }

    @Test fun `budget under bmr warns even above the floor`() {
        val plan = GoalPlanner.plan(
            GoalInput(GoalType.LOSE, 120.0, 110.0, GoalPace.ByWeeklyRate(1.0)), 2600.0, 2200.0, Sex.MALE, today,
        )
        // 10 kg at 1 kg/week = 70 days; deficit 1100 → 1500, above the 1500 floor? equal → not below floor, but below BMR 2200
        assertThat(plan.budgetKcal).isEqualTo(1500)
        assertThat(plan.guardrails.filterIsInstance<Guardrail.LowBudget>()).hasSize(1)
    }

    @Test fun `past or same day target date gives no pace`() {
        assertThat(GoalPlanner.resolvePace(90.0, 80.0, GoalPace.ByDate(today), today)).isNull()
        assertThat(GoalPlanner.resolvePace(90.0, 80.0, GoalPace.ByDate(today.minusDays(3)), today)).isNull()
        assertThat(GoalPlanner.resolvePace(90.0, 80.0, GoalPace.ByWeeklyRate(0.0), today)).isNull()
        assertThat(GoalPlanner.resolvePace(80.0, 80.0, GoalPace.ByWeeklyRate(0.5), today)).isNull()
    }
}
