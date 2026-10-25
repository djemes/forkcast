package app.forkcast.ui.trends

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.domain.NutrientTarget
import app.forkcast.core.domain.TargetKind
import app.forkcast.core.domain.TrendPoint
import app.forkcast.core.domain.Trends
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TrendsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 19)

    private fun state(): TrendsUiState {
        val days = (6L downTo 0L).map { back ->
            val kcal = if (back == 3L) null else 1900.0 + back * 10
            Trends.Day(
                today.minusDays(back),
                kcal?.let { mapOf(NutrientKey.ENERGY_KCAL to it, NutrientKey.PROTEIN_G to 120.0, NutrientKey.CARBS_G to 200.0, NutrientKey.FAT_G to 60.0, NutrientKey.FIBER_G to 15.0) },
                2100.0,
                complete = kcal != null,
            )
        }
        val targets = mapOf(
            NutrientKey.PROTEIN_G to NutrientTarget(NutrientKey.PROTEIN_G, 150.0, TargetKind.GOAL),
            NutrientKey.CARBS_G to NutrientTarget(NutrientKey.CARBS_G, 220.0, TargetKind.GOAL),
            NutrientKey.FAT_G to NutrientTarget(NutrientKey.FAT_G, 65.0, TargetKind.GOAL),
            NutrientKey.FIBER_G to NutrientTarget(NutrientKey.FIBER_G, 30.0, TargetKind.GOAL),
        )
        val weight = (6L downTo 0L).map { TrendPoint(today.minusDays(it), 80.0 - (6 - it) * 0.1, if (it % 2 == 0L) 80.0 - (6 - it) * 0.1 else null) }
        return TrendsUiState(
            period = Trends.Period.WEEK,
            today = today,
            units = UnitPrefs.METRIC,
            days = days,
            calories = Trends.calories(days, today),
            weeks = emptyList(),
            macros = Trends.macros(days, today, targets),
            nutrients = Trends.nutrientReport(days, today, targets),
            logging = Trends.logging(days.filter { it.nutrients != null }.map { it.date }.toSet(), Trends.range(Trends.Period.WEEK, today), today),
            weight = weight,
            hasWeighIns = true,
            weeklyRateKg = -0.5,
            goalKg = 75.0,
            projection = Trends.projectGoal(79.4, -0.5, 75.0, today),
        )
    }

    @Test fun showsEachSectionAndSwitchesPeriod() {
        var period: Trends.Period? = null
        compose.setContent { ForkcastTheme { TrendsScreen(state(), {}, { period = it }, {}) } }
        // Past days 1,910–1,960 without the gap, today (1,900) not averaged: 1,936 vs 2,100.
        compose.onNodeWithText("1,936", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("a day under budget on average", substring = true).assertExists()
        compose.onNodeWithText("Tap a bar to see that day.").performScrollTo()
        compose.onNodeWithText("At this pace you reach", substring = true).performScrollTo()
        compose.onNodeWithText("· 50 %", substring = true, useUnmergedTree = true).performScrollTo()
        compose.onNodeWithText("6 of 7", useUnmergedTree = true).performScrollTo()
        compose.onNodeWithText("Month").performScrollTo().performClick()
        assertThat(period).isEqualTo(Trends.Period.MONTH)
    }

    /** The owner's crash (2026-10-25): 3 months, then Week, laid out the old 90-day model with a blank label. */
    @Test fun goingFromThreeMonthsToAWeekDoesNotCrash() {
        val week = state()
        val quarterDays = (89L downTo 0L).map { back -> week.days.last().copy(date = today.minusDays(back)) }
        val quarter = week.copy(period = Trends.Period.QUARTER, days = quarterDays, calories = Trends.calories(quarterDays, today))
        var shown by mutableStateOf(quarter)
        compose.setContent { ForkcastTheme { TrendsScreen(shown, {}, {}, {}) } }
        compose.waitForIdle()
        shown = week
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Tap a bar to see that day.").performScrollTo()
        shown = week.copy(period = Trends.Period.MONTH, days = quarterDays.takeLast(30))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Tap a bar to see that day.").performScrollTo()
    }

    @Test fun emptyPeriodSaysSo() {
        val empty = state().let { s ->
            s.copy(days = s.days.map { it.copy(nutrients = null, complete = false) }, calories = null, macros = null, nutrients = emptyList(), hasWeighIns = false, weight = emptyList())
        }
        compose.setContent { ForkcastTheme { TrendsScreen(empty, {}, {}, {}) } }
        compose.onNodeWithText("Nothing logged in this period yet", substring = true).assertExists()
        compose.onNodeWithText("No weigh-ins yet", substring = true).assertExists()
    }
}
