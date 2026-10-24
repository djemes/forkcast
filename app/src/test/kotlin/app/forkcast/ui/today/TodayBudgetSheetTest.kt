package app.forkcast.ui.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.domain.BudgetPlan
import app.forkcast.core.domain.BudgetSheet
import app.forkcast.core.domain.DailyTargets
import app.forkcast.core.domain.DayTotals
import app.forkcast.core.domain.Forecast
import app.forkcast.core.domain.NutrientTarget
import app.forkcast.core.domain.TargetKind
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.UnitPrefs
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.TestData
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Today's budget" (owner, 2026-10-24): the sum, the outlook, what fits. */
@RunWith(AndroidJUnit4::class)
class TodayBudgetSheetTest {

    @get:Rule val compose = createComposeRule()
    private val f = UnitFormatter(UnitPrefs.US)
    private val targets = DailyTargets(
        ageYears = 40, currentKg = 90.0, bmrKcal = 1_800.0, tdeeKcal = 2_540.0, formulaTdeeKcal = 2_540.0, adaptiveTdeeKcal = null,
        plan = BudgetPlan(2_540.0, 510.0, 2_030, null, emptyList()), budgetKcal = 2_030, isCustomBudget = false,
        targets = mapOf(NutrientKey.PROTEIN_G to NutrientTarget(NutrientKey.PROTEIN_G, 143.0, TargetKind.GOAL)), guardrails = emptyList(),
    )

    private fun state(eaten: Double, fits: List<FitSuggestion> = emptyList(), forecast: Forecast.Outlook? = null, week: BudgetSheet.WeekSoFar? = null, proteinBehind: Boolean = false): Pair<TodayUiState, DayTotals> {
        val slots = FakeMealSlotRepository.DEFAULT
        val entries = listOf(TestData.entry("Oats", slots[0].id, eaten * 0.4), TestData.entry("Sandwich", slots[1].id, eaten * 0.6))
        val totals = DayTotals(mapOf(NutrientKey.ENERGY_KCAL to eaten, NutrientKey.PROTEIN_G to 40.0), eaten, 2_030, 180.0)
        val s = TodayUiState(
            date = TestData.TODAY, today = TestData.TODAY, defaultMealId = slots[2].id, units = UnitPrefs.US, targets = targets, totals = totals,
            meals = slots.map { slot -> MealSection(slot, entries.filter { it.mealSlotId == slot.id }, entries.filter { it.mealSlotId == slot.id }.sumOf { it.nutrients[NutrientKey.ENERGY_KCAL]!! }) },
            forecast = forecast, forecastFromMinute = 12 * 60 + 25, weekSoFar = week, fits = fits, proteinBehind = proteinBehind,
        )
        return s to totals
    }

    private fun show(s: TodayUiState, totals: DayTotals, onLog: (FitSuggestion, String) -> Unit = { _, _ -> }) = compose.setContent {
        ForkcastTheme { TodayBudgetSheet(s, totals, targets, f, onDismiss = {}, onLogFit = onLog) }
    }

    private fun fit(name: String, kcal: Double, protein: Double) = FitSuggestion(BudgetSheet.Candidate(name, name, kcal, protein, savedMeal = false))

    @Test fun normalDayShowsTheSumOutlookAndWhatFits() {
        var logged: Pair<FitSuggestion, String>? = null
        val yogurt = fit("Greek yogurt", 100.0, 18.0)
        val (s, totals) = state(
            eaten = 800.0,
            fits = listOf(yogurt, fit("Apple", 95.0, 0.5)),
            forecast = Forecast.outlook(800.0, 2_210.0, 1_100.0),
            week = BudgetSheet.WeekSoFar(5, -350.0),
        )
        show(s, totals) { fit, meal -> logged = fit to meal }
        compose.onNodeWithText("Today's budget").assertIsDisplayed()
        compose.onNodeWithText("Maintenance, from the formula").assertIsDisplayed()
        compose.onNodeWithText("Goal deficit").assertIsDisplayed()
        compose.onNodeWithText("Activity bonus").assertIsDisplayed()
        compose.onNodeWithText("Left").assertIsDisplayed()
        compose.onNodeWithText("By meal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("you'll likely end near", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("under budget on average (5 days)", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("What fits in", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Greek yogurt").performScrollTo().performClick()
        assertThat(logged).isEqualTo(yogurt to FakeMealSlotRepository.DEFAULT[2].id)
    }

    @Test fun overBudgetSaysOverAndHidesWhatFits() {
        val (s, totals) = state(eaten = 2_500.0)
        show(s, totals)
        compose.onNodeWithText("Over").assertIsDisplayed()
        compose.onNodeWithText("What fits in", substring = true).assertDoesNotExist()
    }

    @Test fun behindOnProteinSaysSo() {
        val (s, totals) = state(eaten = 1_200.0, fits = listOf(fit("Chicken breast", 165.0, 31.0)), proteinBehind = true)
        show(s, totals)
        compose.onNodeWithText("Higher protein first", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun newUserWithoutHistoryHasNoOutlook() {
        val (s, totals) = state(eaten = 300.0)
        show(s, totals)
        compose.onNodeWithText("Outlook").assertDoesNotExist()
        compose.onNodeWithText("Budget").assertIsDisplayed()
    }
}
