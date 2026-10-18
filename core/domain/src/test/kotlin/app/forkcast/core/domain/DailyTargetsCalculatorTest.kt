package app.forkcast.core.domain

import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.Goal
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Sex
import app.forkcast.core.model.UserProfile
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class DailyTargetsCalculatorTest {

    private val today = LocalDate.of(2026, 10, 18)
    private val profile = UserProfile(Sex.MALE, LocalDate.of(1986, 10, 18), 180.0, ActivityLevel.MODERATE)
    private val goal = Goal("g", GoalType.LOSE, today, 90.0, 80.0, targetDate = null, weeklyRateKg = 0.5)

    @Test fun `end to end budget for the reference person`() {
        val t = DailyTargetsCalculator.compute(profile, goal, currentKg = 90.0, today = today)
        assertThat(t.ageYears).isEqualTo(40)
        assertThat(t.bmrKcal).isWithin(1e-9).of(1830.0)
        assertThat(t.tdeeKcal).isWithin(1e-9).of(2836.5)
        assertThat(t.budgetKcal).isEqualTo(2290)
        assertThat(t.isCustomBudget).isFalse()
        assertThat(t.targets.getValue(NutrientKey.ENERGY_KCAL).amount).isEqualTo(2290.0)
    }

    @Test fun `weekly-rate goal keeps the same deficit as weight drops`() {
        val later = DailyTargetsCalculator.compute(profile, goal, currentKg = 85.0, today = today.plusDays(70))
        assertThat(later.plan.dailyDeltaKcal).isWithin(1e-6).of(550.0)
    }

    @Test fun `date goal spreads the remaining loss over the remaining days`() {
        val byDate = goal.copy(weeklyRateKg = null, targetDate = today.plusDays(140))
        val t = DailyTargetsCalculator.compute(profile, byDate, currentKg = 85.0, today = today.plusDays(70))
        // 5 kg left over 70 days → 550 kcal/day
        assertThat(t.plan.dailyDeltaKcal).isWithin(1e-6).of(550.0)
    }

    @Test fun `budget override is custom and re-checks guardrails`() {
        val t = DailyTargetsCalculator.compute(
            profile, goal, 90.0, today, overrides = mapOf(NutrientKey.ENERGY_KCAL to 1403.0, NutrientKey.PROTEIN_G to 170.0),
        )
        assertThat(t.isCustomBudget).isTrue()
        assertThat(t.budgetKcal).isEqualTo(1400)
        assertThat(t.guardrails.filterIsInstance<Guardrail.LowBudget>()).hasSize(1)
        assertThat(t.targets.getValue(NutrientKey.PROTEIN_G).amount).isEqualTo(170.0)
        // Fat follows the overridden budget
        assertThat(t.targets.getValue(NutrientKey.FAT_G).amount).isWithin(1e-9).of(1400 * 0.3 / 9)
    }

    @Test fun `maintain ignores pace`() {
        val t = DailyTargetsCalculator.compute(profile, goal.copy(goalType = GoalType.MAINTAIN, targetWeightKg = 90.0), 90.0, today)
        assertThat(t.budgetKcal).isEqualTo(2840)
    }
}
