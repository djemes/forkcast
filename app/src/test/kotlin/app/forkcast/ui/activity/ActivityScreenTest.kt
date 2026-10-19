package app.forkcast.ui.activity

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.domain.ActivityBonus
import app.forkcast.core.model.ActivityDay
import app.forkcast.core.model.ExerciseSession
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.theme.ForkcastTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ActivityScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 19)
    private val run = ExerciseSession("s", "Evening run", 56, 0, 40 * 60_000L, 320.0)

    @Test fun showsReportedUsualExtraCountedAndRecentDays() {
        val state = ActivityUiState(
            units = UnitPrefs.METRIC,
            bonusOn = true,
            days = listOf(
                ActivityDayView(today, ActivityDay(today, 12000, 700.0, 2900.0, 40, listOf(run)), ActivityBonus(700.0, 400.0, 12, 300.0, 150.0, true)),
                ActivityDayView(today.minusDays(1), ActivityDay(today.minusDays(1), 6000, 380.0, null, null, emptyList()), ActivityBonus(380.0, 400.0, 11, 0.0, 0.0, true)),
                ActivityDayView(today.minusDays(2), null, ActivityBonus.NONE),
            ),
        )
        compose.setContent { ForkcastTheme { ActivityScreen(state, onBack = {}) } }
        compose.onNodeWithText("Only activity above your usual adds budget", substring = true).assertIsDisplayed()
        compose.onNodeWithText("12,000").assertIsDisplayed()
        compose.onNodeWithText("400 kcal (from 12 days)").assertIsDisplayed()
        compose.onNodeWithText("300 kcal").assertIsDisplayed()
        compose.onNodeWithText("+150 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Evening run · 40 min · 320 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("6,000 steps · 380 kcal active").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No data").performScrollTo().assertIsDisplayed()
    }

    @Test fun bonusOffExplainsShowOnly() {
        val state = ActivityUiState(UnitPrefs.METRIC, listOf(ActivityDayView(today, null, ActivityBonus.NONE)), bonusOn = false)
        compose.setContent { ForkcastTheme { ActivityScreen(state, onBack = {}) } }
        compose.onNodeWithText("The activity bonus is off", substring = true).assertIsDisplayed()
    }
}
