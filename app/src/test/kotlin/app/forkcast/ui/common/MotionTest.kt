package app.forkcast.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.domain.DailyTargetsCalculator
import app.forkcast.core.domain.DayMath
import app.forkcast.core.model.UnitPrefs
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.TestData
import app.forkcast.ui.theme.ForkcastTheme
import app.forkcast.ui.today.MealSection
import app.forkcast.ui.today.TodayScreen
import app.forkcast.ui.today.TodayUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** DESIGN.md §8: entrances play once (app open, day change), never on coming back. */
@RunWith(AndroidJUnit4::class)
class MotionTest {

    @get:Rule val compose = createComposeRule()

    private val today = TestData.TODAY
    private val entries = listOf(TestData.entry("Greek yogurt", "b", 146.0, 20.0).copy(id = "y"))
    private val targets = DailyTargetsCalculator.compute(TestData.PROFILE, TestData.GOAL, 90.0, today)
    private val state = TodayUiState(
        date = today,
        today = today,
        meals = FakeMealSlotRepository.DEFAULT.map { MealSection(it, entries.filter { e -> e.mealSlotId == it.id }, DayMath.mealKcal(entries, it.id)) },
        defaultMealId = "l",
        units = UnitPrefs.US,
        targets = targets,
        totals = DayMath.totals(entries, targets.budgetKcal),
    )

    @Test fun entranceClaimsEachEffectOnce() {
        val e = Entrance(active = true, rise = true)
        assertThat(e.claim("ring")).isTrue()
        assertThat(e.claim("ring")).isFalse()
        assertThat(e.claimRise("water")).isTrue()
        assertThat(e.claimRise("water")).isFalse()
        val back = Entrance(active = false, rise = false)
        assertThat(back.claim("ring")).isFalse()
        assertThat(back.claimRise("water")).isFalse()
        // A screen entrance fills charts but doesn't rise cards.
        assertThat(Entrance(active = true, rise = false).claimRise("card")).isFalse()
    }

    @Test fun ringCountsUpOnOpenButNotWhenComingBack() {
        compose.mainClock.autoAdvance = false
        var shown by mutableStateOf(true)
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            ForkcastTheme {
                holder.SaveableStateProvider("today") { if (shown) TodayScreen(state, onEvent = {}) }
            }
        }
        // App open: the numbers start from 0 and count up to the real values.
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Eaten 0 · Budget 2,290").assertExists()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Eaten 146 · Budget 2,290").assertExists()

        // Away to another screen and back: the current values show at once.
        shown = false
        compose.mainClock.advanceTimeBy(500)
        shown = true
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Eaten 146 · Budget 2,290").assertExists()
    }
}
