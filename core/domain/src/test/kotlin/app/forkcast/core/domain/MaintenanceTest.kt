package app.forkcast.core.domain

import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.DayCompleteness
import app.forkcast.core.model.ExerciseMode
import app.forkcast.core.model.Goal
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.Sex
import app.forkcast.core.model.TdeeConfidence
import app.forkcast.core.model.UserProfile
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MaintenanceTest {

    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 11, 20)
    private val profile = UserProfile(Sex.MALE, LocalDate.of(1985, 1, 1), 180.0, ActivityLevel.MODERATE)

    private fun weighIn(date: LocalDate, kg: Double) = WeighIn(date.atTime(7, 0).atZone(zone).toInstant(), kg)

    /** Steady loss of [kgPerDay] from [startKg], weighed every [every] days for [days] days before today. */
    private fun weighIns(startKg: Double, kgPerDay: Double, days: Int = 60, every: Int = 1) =
        (days downTo 1 step every).map { d -> weighIn(today.minusDays(d.toLong()), startKg - kgPerDay * (days - d)) }

    private fun logs(kcal: Double, days: Int = 28, meals: Int = 3) =
        (1..days).map { AdaptiveTdee.DayLog(today.minusDays(it.toLong()), kcal, meals) }

    @Test fun completenessRuleAndUserMark() {
        val d = today.minusDays(1)
        assertThat(AdaptiveTdee.isComplete(AdaptiveTdee.DayLog(d, 1000.0, 2))).isTrue()
        assertThat(AdaptiveTdee.isComplete(AdaptiveTdee.DayLog(d, 999.0, 3))).isFalse()
        assertThat(AdaptiveTdee.isComplete(AdaptiveTdee.DayLog(d, 2500.0, 1))).isFalse()
        assertThat(AdaptiveTdee.isComplete(AdaptiveTdee.DayLog(d, 500.0, 1, DayCompleteness.COMPLETE))).isTrue()
        assertThat(AdaptiveTdee.isComplete(AdaptiveTdee.DayLog(d, 2500.0, 3, DayCompleteness.INCOMPLETE))).isFalse()
    }

    @Test fun needsFourteenCompleteDaysAndSixWeighIns() {
        val few = AdaptiveTdee.estimate(today, logs(2000.0, days = 13), weighIns(90.0, 0.05), zone, 2600.0)
        assertThat(few.eligible).isFalse()
        assertThat(few.completeDays).isEqualTo(13)
        val noScale = AdaptiveTdee.estimate(today, logs(2000.0), weighIns(90.0, 0.05, days = 28, every = 6), zone, 2600.0)
        assertThat(noScale.weighIns).isEqualTo(5)
        assertThat(noScale.eligible).isFalse()
    }

    @Test fun observedIsIntakePlusTheEnergyOfTheLoss() {
        // 2,000 kcal/day while losing 0.1 kg/day (a long, steady history so the trend has caught up):
        // observed ≈ 2,000 + 0.1 × 7,700 = 2,770.
        val e = AdaptiveTdee.estimate(today, logs(2000.0), weighIns(95.0, 0.1, days = 120), zone, 2600.0)
        assertThat(e.observedKcal!!).isWithin(25.0).of(2770.0)
        // The weekly step moves 30 % of the gap: 2,600 + 0.3 × 170 ≈ 2,651.
        assertThat(e.kcal!!).isWithin(10.0).of(2651.0)
        assertThat(e.confidence).isEqualTo(TdeeConfidence.HIGH)
        // A big gap is capped at 150 per week.
        assertThat(AdaptiveTdee.estimate(today, logs(2000.0), weighIns(95.0, 0.1, days = 120), zone, 2000.0).kcal).isEqualTo(2150.0)
    }

    @Test fun stepIsThirtyPercentWhenSmall() {
        // Stable weight: observed = intake = 2,500; previous 2,450 → +15.
        val e = AdaptiveTdee.estimate(today, logs(2500.0), weighIns(80.0, 0.0), zone, 2450.0)
        assertThat(e.observedKcal!!).isWithin(0.01).of(2500.0)
        assertThat(e.kcal!!).isWithin(0.01).of(2465.0)
    }

    @Test fun confidenceFollowsCompleteness() {
        assertThat(AdaptiveTdee.confidence(14, 6)).isEqualTo(TdeeConfidence.LOW)
        assertThat(AdaptiveTdee.confidence(20, 10)).isEqualTo(TdeeConfidence.MEDIUM)
        assertThat(AdaptiveTdee.confidence(24, 16)).isEqualTo(TdeeConfidence.HIGH)
    }

    @Test fun dueWeekly() {
        assertThat(AdaptiveTdee.isDue(null, today)).isTrue()
        assertThat(AdaptiveTdee.isDue(today.minusDays(6), today)).isFalse()
        assertThat(AdaptiveTdee.isDue(today.minusDays(7), today)).isTrue()
    }

    @Test fun exerciseBonusOnlyWhenAddedBack() {
        assertThat(ExerciseMath.bonusKcal(profile, 400.0)).isEqualTo(0.0)
        val addedBack = profile.copy(exerciseMode = ExerciseMode.ADDED_BACK, exerciseAddBackPct = 50)
        assertThat(ExerciseMath.bonusKcal(addedBack, 401.0)).isEqualTo(201.0)
        assertThat(ExerciseMath.bonusKcal(addedBack, null)).isEqualTo(0.0)
        assertThat(ExerciseMath.activityFactor(addedBack)).isEqualTo(ActivityLevel.SEDENTARY.factor)
        assertThat(ExerciseMath.activityFactor(profile)).isEqualTo(ActivityLevel.MODERATE.factor)
    }

    private val goal = Goal("g", GoalType.LOSE, today, 90.0, 80.0, null, 0.5)

    @Test fun addedBackUsesSedentaryFormula() {
        val included = DailyTargetsCalculator.compute(profile, goal, 90.0, today)
        val addedBack = DailyTargetsCalculator.compute(profile.copy(exerciseMode = ExerciseMode.ADDED_BACK), goal, 90.0, today)
        assertThat(addedBack.tdeeKcal).isWithin(0.01).of(included.bmrKcal * 1.2)
        // Same deficit either way: 0.5 kg/week.
        assertThat(included.tdeeKcal - included.budgetKcal).isWithin(10.0).of(550.0)
        assertThat(addedBack.tdeeKcal - addedBack.budgetKcal).isWithin(10.0).of(550.0)
    }

    @Test fun adaptiveReplacesFormulaAndKeepsTheDeficit() {
        val t = DailyTargetsCalculator.compute(profile, goal, 90.0, today, adaptiveKcal = 2900.0)
        assertThat(t.tdeeKcal).isEqualTo(2900.0)
        assertThat(t.adaptiveTdeeKcal).isEqualTo(2900.0)
        assertThat(t.budgetKcal).isEqualTo(2350)
    }

    @Test fun addedBackTakesTheAverageBonusOutOfAdaptive() {
        val p = profile.copy(exerciseMode = ExerciseMode.ADDED_BACK)
        val t = DailyTargetsCalculator.compute(p, goal, 90.0, today, adaptiveKcal = 2900.0, avgExerciseBonusKcal = 200.0)
        assertThat(t.tdeeKcal).isEqualTo(2700.0)
        assertThat(t.budgetKcal).isEqualTo(2150)
        // Included ignores the bonus.
        assertThat(DailyTargetsCalculator.compute(profile, goal, 90.0, today, adaptiveKcal = 2900.0, avgExerciseBonusKcal = 200.0).tdeeKcal)
            .isEqualTo(2900.0)
    }
}
