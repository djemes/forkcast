package app.forkcast.core.domain

import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.DayCompleteness
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

    /** Active calories for the 28 days before [today]: [usual] every day. */
    private fun history(usual: Double, days: Int = 28) = (1..days).associate { today.minusDays(it.toLong()) to usual }

    @Test fun noExerciseNormalDayAndBigWorkoutDay() {
        val usual = history(400.0)
        // No exercise: below usual, never negative.
        assertThat(ActivityBonusMath.forDay(today, usual + (today to 150.0), true).bonusKcal).isEqualTo(0.0)
        // A normal day: at usual, nothing extra.
        val normal = ActivityBonusMath.forDay(today, usual + (today to 400.0), true)
        assertThat(normal.extraKcal).isEqualTo(0.0)
        assertThat(normal.bonusKcal).isEqualTo(0.0)
        // A workout day: 700 active = 300 above usual, half counts.
        val workout = ActivityBonusMath.forDay(today, usual + (today to 700.0), true)
        assertThat(workout.usualKcal).isEqualTo(400.0)
        assertThat(workout.extraKcal).isEqualTo(300.0)
        assertThat(workout.bonusKcal).isEqualTo(150.0)
        // A huge day is capped at 500.
        assertThat(ActivityBonusMath.forDay(today, usual + (today to 2400.0), true).bonusKcal).isEqualTo(500.0)
    }

    @Test fun bonusOffShowsActivityOnly() {
        val b = ActivityBonusMath.forDay(today, history(400.0) + (today to 700.0), enabled = false)
        assertThat(b.extraKcal).isEqualTo(300.0)
        assertThat(b.bonusKcal).isEqualTo(0.0)
        assertThat(b.enabled).isFalse()
    }

    @Test fun usualUsesWhatIsAvailableAndSaysSo() {
        val few = ActivityBonusMath.forDay(today, history(400.0, days = 2) + (today to 700.0), true)
        assertThat(few.usualKcal).isNull()
        assertThat(few.bonusKcal).isEqualTo(0.0)
        val some = ActivityBonusMath.forDay(today, history(400.0, days = 5) + (today to 700.0), true)
        assertThat(some.usualKcal).isEqualTo(400.0)
        assertThat(some.baselineDays).isEqualTo(5)
        assertThat(some.partialBaseline).isTrue()
        // Days older than 28 and the day itself are not part of the usual.
        val old = (29..40).associate { today.minusDays(it.toLong()) to 2000.0 }
        val full = ActivityBonusMath.forDay(today, history(400.0) + old + (today to 700.0), true)
        assertThat(full.usualKcal).isEqualTo(400.0)
        assertThat(full.baselineDays).isEqualTo(28)
        assertThat(full.partialBaseline).isFalse()
    }

    @Test fun averageBonusOverTheWindow() {
        // 56 days at 400 with one 700 day inside the window: that day's bonus is 150; average 150/28.
        val active = (1..56).associate { today.minusDays(it.toLong()) to 400.0 } + (today.minusDays(3) to 700.0)
        val avg = ActivityBonusMath.averageBonus(AdaptiveTdee.window(today), active, true)
        assertThat(avg).isWithin(0.5).of(150.0 / 28)
        assertThat(ActivityBonusMath.averageBonus(AdaptiveTdee.window(today), active, false)).isEqualTo(0.0)
    }

    @Test fun activityLevelAdvice() {
        // BMR 1,800: ~1.1 × (1 + active/1,800). 100 → 1.16 sedentary; 450 → 1.375 light; 800 → 1.59 moderate.
        assertThat(ActivityLevelAdvice.suggest(1800.0, 100.0)).isEqualTo(ActivityLevel.SEDENTARY)
        assertThat(ActivityLevelAdvice.suggest(1800.0, 450.0)).isEqualTo(ActivityLevel.LIGHT)
        assertThat(ActivityLevelAdvice.suggest(1800.0, 800.0)).isEqualTo(ActivityLevel.MODERATE)
    }

    private val goal = Goal("g", GoalType.LOSE, today, 90.0, 80.0, null, 0.5)

    @Test fun formulaUsesTheChosenActivityLevel() {
        val t = DailyTargetsCalculator.compute(profile, goal, 90.0, today)
        assertThat(t.tdeeKcal).isWithin(0.01).of(t.bmrKcal * ActivityLevel.MODERATE.factor)
        assertThat(t.tdeeKcal - t.budgetKcal).isWithin(10.0).of(550.0)
    }

    @Test fun adaptiveReplacesFormulaAndKeepsTheDeficit() {
        val t = DailyTargetsCalculator.compute(profile, goal, 90.0, today, adaptiveKcal = 2900.0)
        assertThat(t.tdeeKcal).isEqualTo(2900.0)
        assertThat(t.adaptiveTdeeKcal).isEqualTo(2900.0)
        assertThat(t.budgetKcal).isEqualTo(2350)
    }

    /** Adaptive maintenance already holds the measured days' activity: the average bonus comes out once. */
    @Test fun adaptiveTakesTheAverageBonusOut() {
        val t = DailyTargetsCalculator.compute(profile, goal, 90.0, today, adaptiveKcal = 2900.0, avgExerciseBonusKcal = 60.0)
        assertThat(t.tdeeKcal).isEqualTo(2840.0)
        assertThat(t.budgetKcal).isEqualTo(2290)
        // The formula is a guess, not a measurement: nothing to take out.
        assertThat(DailyTargetsCalculator.compute(profile, goal, 90.0, today, avgExerciseBonusKcal = 60.0).tdeeKcal)
            .isWithin(0.01).of(DailyTargetsCalculator.compute(profile, goal, 90.0, today).tdeeKcal)
    }
}
