package app.forkcast.ui.weekly

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.domain.WeeklyCheckIn
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class WeeklyCheckInScreenTest {

    @get:Rule val compose = createComposeRule()

    private val start = LocalDate.of(2026, 10, 12)
    private val summary = WeeklyCheckIn.Summary(
        start, start.plusDays(6), daysLogged = 6, completeDays = 5, avgIntakeKcal = 2000.0, avgAllowanceKcal = 2150.0,
        avgDifferenceKcal = -150.0, trendChangeKg = -0.5, weighIns = 5, plannedKgPerWeek = -0.9, pace = WeeklyCheckIn.Pace.ON_PACE,
        maintenanceKcal = 2700.0, maintenanceChangeKcal = 40.0, avgSteps = 9000, avgActiveKcal = 420.0, totalBonusKcal = 180.0,
    )

    @Test fun showsTheWeek() {
        var previous = 0
        compose.setContent { ForkcastTheme { WeeklyCheckInScreen(WeeklyUiState(summary, UnitPrefs.METRIC, canGoNext = false), {}, { previous++ }, {}) } }
        compose.onNodeWithText("6 of 7 (5 complete)").assertExists()
        compose.onNodeWithText("150 kcal under your budget", substring = true).assertExists()
        compose.onNodeWithText("On pace with your plan.").performScrollTo()
        compose.onNodeWithText("−0.5 kg").assertExists()
        compose.onNodeWithText("+40 kcal").performScrollTo()
        compose.onNodeWithText("9,000").performScrollTo()
        compose.onNodeWithContentDescription("Next week").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous week").performScrollTo().performClick()
        assertThat(previous).isEqualTo(1)
    }
}
