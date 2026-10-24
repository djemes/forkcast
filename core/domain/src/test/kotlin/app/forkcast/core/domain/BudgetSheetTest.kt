package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class BudgetSheetTest {

    private val saturday = LocalDate.of(2026, 10, 24)

    @Test fun weekSoFarAveragesLoggedDaysBeforeToday() {
        val days = listOf(
            BudgetSheet.Day(saturday.minusDays(6), 1_800.0, 2_000.0), // Sunday: last week when weeks start Monday
            BudgetSheet.Day(saturday.minusDays(5), 1_700.0, 2_000.0),
            BudgetSheet.Day(saturday.minusDays(4), 2_100.0, 2_100.0),
            BudgetSheet.Day(saturday.minusDays(3), 0.0, 2_000.0), // nothing logged
            BudgetSheet.Day(saturday, 900.0, 2_000.0), // today isn't over yet
        )
        val week = BudgetSheet.weekSoFar(days, saturday, DayOfWeek.MONDAY)!!
        assertThat(week.days).isEqualTo(2)
        assertThat(week.averageDifferenceKcal).isWithin(1e-9).of(-150.0)
        assertThat(week.onBudget).isFalse()
        // With Sunday-first weeks, Sunday counts too.
        assertThat(BudgetSheet.weekSoFar(days, saturday, DayOfWeek.SUNDAY)!!.days).isEqualTo(3)
    }

    @Test fun firstDayOfTheWeekHasNoWeekYet() {
        val monday = LocalDate.of(2026, 10, 19)
        assertThat(BudgetSheet.weekSoFar(listOf(BudgetSheet.Day(monday.minusDays(1), 2_000.0, 2_000.0)), monday, DayOfWeek.MONDAY)).isNull()
    }

    private fun c(name: String, kcal: Double, protein: Double) = BudgetSheet.Candidate(name, name, kcal, protein, savedMeal = false)

    private val history = listOf(
        c("Oatmeal", 300.0, 10.0),
        c("Coffee", 2.0, 0.3),
        c("Burrito bowl", 640.0, 46.0),
        c("Greek yogurt", 100.0, 18.0),
        c("oatmeal", 300.0, 10.0),
        c("Apple", 95.0, 0.5),
        c("Almonds", 164.0, 6.0),
        c("Salmon dinner", 600.0, 40.0),
    )

    @Test fun whatFitsKeepsHistoryOrderAndSkipsWhatDoesntFit() {
        val fits = BudgetSheet.whatFits(history, remainingKcal = 400.0, proteinBehind = false)
        assertThat(fits.map { it.name }).containsExactly("Oatmeal", "Greek yogurt", "Apple", "Almonds").inOrder()
    }

    @Test fun behindOnProteinPutsProteinDenseFirst() {
        val fits = BudgetSheet.whatFits(history, remainingKcal = 700.0, proteinBehind = true)
        assertThat(fits.first().name).isEqualTo("Greek yogurt")
        assertThat(fits.map { it.name }).doesNotContain("Apple")
    }

    @Test fun nothingLeftNothingFits() {
        assertThat(BudgetSheet.whatFits(history, remainingKcal = 30.0, proteinBehind = false)).isEmpty()
        assertThat(BudgetSheet.whatFits(history, remainingKcal = -200.0, proteinBehind = false)).isEmpty()
    }

    @Test fun proteinBehindComparesShares() {
        // Half the calories eaten but only a fifth of the protein.
        assertThat(BudgetSheet.proteinBehind(28.0, 140.0, 1_000.0, 2_000.0)).isTrue()
        // On pace.
        assertThat(BudgetSheet.proteinBehind(70.0, 140.0, 1_000.0, 2_000.0)).isFalse()
    }

    private fun targets(tdee: Double, budget: Int, custom: Boolean = false, adaptive: Double? = null) = DailyTargets(
        ageYears = 35, currentKg = 90.0, bmrKcal = 1_800.0, tdeeKcal = tdee, formulaTdeeKcal = tdee, adaptiveTdeeKcal = adaptive,
        plan = BudgetPlan(tdee, tdee - budget, budget, null, emptyList()), budgetKcal = budget, isCustomBudget = custom,
        targets = emptyMap(), guardrails = emptyList(),
    )

    @Test fun equationAddsUpOnScreen() {
        val totals = DayTotals(emptyMap(), eatenKcal = 783.4, budgetKcal = 2_030, exerciseKcal = 180.2)
        val e = BudgetSheet.equation(targets(2_540.4, 2_030, adaptive = 2_600.0), totals)
        assertThat(e.maintenanceKcal).isEqualTo(2_540)
        assertThat(e.adaptive).isTrue()
        assertThat(e.goalDeltaKcal).isEqualTo(510)
        assertThat(e.maintenanceKcal!! - e.goalDeltaKcal!!).isEqualTo(e.budgetKcal)
        assertThat(e.activityBonusKcal).isEqualTo(180)
        assertThat(e.remainingKcal).isEqualTo(2_030 + 180 - 783)
    }

    @Test fun customBudgetReplacesMaintenanceAndGoal() {
        val e = BudgetSheet.equation(targets(2_540.0, 1_900, custom = true), DayTotals(emptyMap(), 0.0, 1_900, 0.0))
        assertThat(e.maintenanceKcal).isNull()
        assertThat(e.goalDeltaKcal).isNull()
        assertThat(e.customBudget).isTrue()
    }
}
