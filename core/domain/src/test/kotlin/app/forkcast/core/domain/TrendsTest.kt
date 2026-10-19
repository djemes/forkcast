package app.forkcast.core.domain

import app.forkcast.core.domain.Trends.GoalProjection
import app.forkcast.core.domain.Trends.Standing
import app.forkcast.core.model.NutrientKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class TrendsTest {

    private val today = LocalDate.of(2026, 10, 19) // Monday

    private fun day(offset: Long, kcal: Double?, complete: Boolean = true, allowance: Double = 2000.0, extra: Map<NutrientKey, Double> = emptyMap()) =
        Trends.Day(today.minusDays(offset), kcal?.let { mapOf(NutrientKey.ENERGY_KCAL to it) + extra }, allowance, complete)

    @Test fun rangeEndsToday() {
        val r = Trends.range(Trends.Period.WEEK, today)
        assertThat(r.start).isEqualTo(today.minusDays(6))
        assertThat(r.endInclusive).isEqualTo(today)
    }

    @Test fun caloriesAverageCompletePastDaysAndSkipToday() {
        val days = listOf(
            day(0, 500.0), // today: shown, never averaged
            day(1, 2200.0),
            day(2, 1800.0, allowance = 2200.0),
            day(3, 900.0, complete = false),
            day(4, null),
        )
        val c = Trends.calories(days, today)!!
        assertThat(c.daysAveraged).isEqualTo(2)
        assertThat(c.completeOnly).isTrue()
        assertThat(c.avgKcal).isEqualTo(2000.0)
        assertThat(c.avgAllowanceKcal).isEqualTo(2100.0)
        assertThat(c.avgDifferenceKcal).isEqualTo(-100.0)
        assertThat(c.daysOver).isEqualTo(1)
    }

    @Test fun caloriesFallBackToLoggedDaysWhenNoneIsComplete() {
        val c = Trends.calories(listOf(day(1, 800.0, complete = false), day(2, 1200.0, complete = false)), today)!!
        assertThat(c.completeOnly).isFalse()
        assertThat(c.avgKcal).isEqualTo(1000.0)
    }

    @Test fun nothingLoggedHasNoAverages() {
        assertThat(Trends.calories(listOf(day(0, 400.0), day(1, null)), today)).isNull()
        assertThat(Trends.macros(listOf(day(1, null)), today, emptyMap())).isNull()
        assertThat(Trends.nutrientReport(emptyList(), today, emptyMap())).isEmpty()
    }

    @Test fun weeklyAveragesGroupByMondayWeeks() {
        // Oct 12–18 is one week (Mon–Sun); Oct 11 belongs to the week before.
        val weeks = Trends.weeklyAverages(listOf(day(1, 2000.0), day(7, 1800.0), day(8, 1600.0)), today)
        assertThat(weeks.map { it.start }).containsExactly(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12)).inOrder()
        assertThat(weeks[1].avgKcal).isEqualTo(1900.0)
        assertThat(weeks[1].days).isEqualTo(2)
    }

    @Test fun macroSplitIsShareOfEnergy() {
        // 100 g protein = 400, 200 g carbs = 800, 80 g fat = 720 → 1,920 kcal.
        val s = Trends.macroSplit(100.0, 200.0, 80.0)!!
        assertThat(s.proteinPct).isWithin(0.01).of(20.83)
        assertThat(s.carbsPct).isWithin(0.01).of(41.67)
        assertThat(s.fatPct).isWithin(0.01).of(37.5)
        assertThat(Trends.macroSplit(0.0, 0.0, 0.0)).isNull()
    }

    @Test fun macrosCompareWithTargets() {
        val m = Trends.macros(
            listOf(day(1, 2000.0, extra = mapOf(NutrientKey.PROTEIN_G to 100.0, NutrientKey.CARBS_G to 250.0, NutrientKey.FAT_G to 60.0))),
            today,
            mapOf(
                NutrientKey.PROTEIN_G to NutrientTarget(NutrientKey.PROTEIN_G, 150.0, TargetKind.GOAL),
                NutrientKey.CARBS_G to NutrientTarget(NutrientKey.CARBS_G, 200.0, TargetKind.GOAL),
                NutrientKey.FAT_G to NutrientTarget(NutrientKey.FAT_G, 60.0, TargetKind.GOAL),
            ),
        )!!
        assertThat(m.avgProteinG).isEqualTo(100.0)
        assertThat(m.split!!.proteinPct).isLessThan(m.targetSplit!!.proteinPct)
    }

    @Test fun nutrientReportSortsMostOffFirstAndRespectsGoalsAndLimits() {
        val targets = mapOf(
            NutrientKey.ENERGY_KCAL to NutrientTarget(NutrientKey.ENERGY_KCAL, 2000.0, TargetKind.LIMIT),
            NutrientKey.FIBER_G to NutrientTarget(NutrientKey.FIBER_G, 30.0, TargetKind.GOAL),
            NutrientKey.SODIUM_MG to NutrientTarget(NutrientKey.SODIUM_MG, 2300.0, TargetKind.LIMIT),
            NutrientKey.PROTEIN_G to NutrientTarget(NutrientKey.PROTEIN_G, 100.0, TargetKind.GOAL),
            NutrientKey.SAT_FAT_G to NutrientTarget(NutrientKey.SAT_FAT_G, 20.0, TargetKind.LIMIT),
        )
        val days = listOf(
            day(
                1, 2000.0,
                extra = mapOf(
                    NutrientKey.FIBER_G to 15.0, // 50 % of the goal: under
                    NutrientKey.SODIUM_MG to 2990.0, // 130 % of the limit: over
                    NutrientKey.PROTEIN_G to 140.0, // above a goal is fine
                    NutrientKey.SAT_FAT_G to 10.0, // under a limit is fine
                ),
            ),
        )
        val report = Trends.nutrientReport(days, today, targets)
        assertThat(report.map { it.key }).containsExactly(
            NutrientKey.FIBER_G, NutrientKey.SODIUM_MG, NutrientKey.PROTEIN_G, NutrientKey.SAT_FAT_G,
        ).inOrder()
        assertThat(report[0].standing).isEqualTo(Standing.UNDER)
        assertThat(report[1].standing).isEqualTo(Standing.OVER)
        assertThat(report[2].standing).isEqualTo(Standing.ON_TARGET)
        assertThat(report[3].standing).isEqualTo(Standing.ON_TARGET)
    }

    @Test fun goalWithinFivePercentCountsAsReached() {
        val t = mapOf(NutrientKey.FIBER_G to NutrientTarget(NutrientKey.FIBER_G, 30.0, TargetKind.GOAL))
        val line = Trends.nutrientReport(listOf(day(1, 2000.0, extra = mapOf(NutrientKey.FIBER_G to 28.8))), today, t).single()
        assertThat(line.standing).isEqualTo(Standing.ON_TARGET)
    }

    @Test fun nutrientsNoEntryReportsGoLastAsNoData() {
        val t = mapOf(
            NutrientKey.FIBER_G to NutrientTarget(NutrientKey.FIBER_G, 30.0, TargetKind.GOAL),
            NutrientKey.VITAMIN_D_UG to NutrientTarget(NutrientKey.VITAMIN_D_UG, 20.0, TargetKind.GOAL),
        )
        val report = Trends.nutrientReport(
            listOf(day(1, 2000.0, extra = mapOf(NutrientKey.FIBER_G to 29.0))), today, t,
            coverage = mapOf(NutrientKey.FIBER_G to 1.0, NutrientKey.VITAMIN_D_UG to 0.0),
        )
        assertThat(report.map { it.key }).containsExactly(NutrientKey.FIBER_G, NutrientKey.VITAMIN_D_UG).inOrder()
        assertThat(report[1].standing).isEqualTo(Standing.NO_DATA)
    }

    @Test fun coverageIsTheEnergyShareOfEntriesReportingTheNutrient() {
        val c = Trends.coverage(
            listOf(
                mapOf(NutrientKey.ENERGY_KCAL to 300.0, NutrientKey.FIBER_G to 4.0),
                mapOf(NutrientKey.ENERGY_KCAL to 700.0), // a quick add: calories only
            ),
        )
        assertThat(c[NutrientKey.FIBER_G]).isWithin(1e-9).of(0.3)
        assertThat(c[NutrientKey.ENERGY_KCAL]).isWithin(1e-9).of(1.0)
        assertThat(Trends.coverage(emptyList())).isEmpty()
    }

    @Test fun streakEndsTodayOrYesterday() {
        val logged = setOf(today.minusDays(1), today.minusDays(2), today.minusDays(3), today.minusDays(10), today.minusDays(11))
        val l = Trends.logging(logged, Trends.range(Trends.Period.WEEK, today), today)
        assertThat(l.currentStreak).isEqualTo(3)
        assertThat(l.longestStreak).isEqualTo(3)
        assertThat(l.daysLogged).isEqualTo(3)
        assertThat(l.periodDays).isEqualTo(7)
        assertThat(Trends.logging(logged + today, Trends.range(Trends.Period.WEEK, today), today).currentStreak).isEqualTo(4)
        assertThat(Trends.logging(setOf(today.minusDays(2)), Trends.range(Trends.Period.WEEK, today), today).currentStreak).isEqualTo(0)
    }

    @Test fun projectsTheGoalDateFromTheTrendRate() {
        // 5 kg to lose at 0.5 kg/week = 10 weeks.
        assertThat(Trends.projectGoal(85.0, -0.5, 80.0, today)).isEqualTo(GoalProjection.By(today.plusDays(70)))
        assertThat(Trends.projectGoal(85.0, 0.2, 80.0, today)).isEqualTo(GoalProjection.NotMovingToward)
        assertThat(Trends.projectGoal(85.0, 0.0, 80.0, today)).isEqualTo(GoalProjection.NotMovingToward)
        assertThat(Trends.projectGoal(80.1, -0.5, 80.0, today)).isEqualTo(GoalProjection.Reached)
        assertThat(Trends.projectGoal(120.0, -0.05, 80.0, today)).isEqualTo(GoalProjection.TooFar)
        assertThat(Trends.projectGoal(85.0, null, 80.0, today)).isNull()
        // Gaining toward a higher goal works the same way.
        assertThat(Trends.projectGoal(60.0, 0.25, 62.0, today)).isEqualTo(GoalProjection.By(today.plusDays(56)))
    }
}
