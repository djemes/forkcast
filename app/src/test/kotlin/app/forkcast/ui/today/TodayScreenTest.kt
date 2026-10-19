package app.forkcast.ui.today

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.domain.DailyTargetsCalculator
import app.forkcast.core.domain.DayMath
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.data.db.LoggedDay
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.TestData
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = TestData.TODAY
    private val entries = listOf(
        TestData.entry("Greek yogurt", "b", 146.0, 20.0).copy(id = "y"),
        TestData.entry("Banana", "b", 105.0, 1.0).copy(id = "n"),
    )
    private val targets = DailyTargetsCalculator.compute(TestData.PROFILE, TestData.GOAL, 90.0, today)
    private val state = TodayUiState(
        date = today,
        today = today,
        meals = FakeMealSlotRepository.DEFAULT.map { slot ->
            val list = entries.filter { it.mealSlotId == slot.id }
            MealSection(slot, list, DayMath.mealKcal(entries, slot.id))
        },
        defaultMealId = "l",
        units = UnitPrefs.US,
        targets = targets,
        totals = DayMath.totals(entries, targets.budgetKcal),
    )

    /** Lazy rows only exist once scrolled into view. */
    private fun row(text: String) = compose.onNode(hasScrollToNodeAction())
        .performScrollToNode(hasText(text, substring = true))
        .let { compose.onNode(hasText(text, substring = true) and hasClickAction()) }

    @Test fun showsRingMacrosAndEntries() {
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = {}) } }
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Sun, Oct 18").assertIsDisplayed()
        // On today there's no "Today" button, just the calendar.
        compose.onNodeWithContentDescription("Go to today").assertDoesNotExist()
        // 2,290 budget - 251 eaten = 2,039 left (the ring is one merged node with a spoken summary)
        compose.onNodeWithContentDescription("2,039 kcal left", substring = true).assertIsDisplayed()
        row("Greek yogurt").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("251 kcal"))
        compose.onNodeWithText("251 kcal").assertIsDisplayed()
    }

    @Test fun logButtonOpensSheetForDefaultMealAndChipsSwitch() {
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = {}) } }
        compose.onNodeWithContentDescription("Log").performClick()
        compose.onNodeWithText("Log to Lunch").assertIsDisplayed()
        compose.onAllNodesWithText("Dinner").filterToOne(hasClickAction()).performClick()
        compose.onNodeWithText("Log to Dinner").assertIsDisplayed()
    }

    @Test fun longPressSelects() {
        val events = mutableListOf<TodayEvent>()
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = { events += it }) } }
        row("Banana").performTouchInput { longClick() }
        assertThat(events).contains(TodayEvent.ToggleSelect("n"))
    }

    @Test fun tapOpensEditor() {
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = {}) } }
        row("Banana").performClick()
        compose.onNodeWithText("Edit entry").assertIsDisplayed()
    }

    @Test fun selectionBarDeletes() {
        val events = mutableListOf<TodayEvent>()
        compose.setContent { ForkcastTheme { TodayScreen(state.copy(selection = setOf("n")), onEvent = { events += it }) } }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete").performClick()
        assertThat(events).contains(TodayEvent.DeleteSelected)
    }

    @Test fun arrowsSendDayEvents() {
        val events = mutableListOf<TodayEvent>()
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = { events += it }) } }
        compose.onNodeWithContentDescription("Previous day").performClick()
        compose.onNodeWithContentDescription("Next day").performClick()
        assertThat(events).containsExactly(TodayEvent.PreviousDay, TodayEvent.NextDay).inOrder()
    }

    @Test fun worksAtDoubleFontScale() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                ForkcastTheme { TodayScreen(state, onEvent = {}) }
            }
        }
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Log").assertIsDisplayed()
        row("Banana").assertIsDisplayed()
    }

    @Test fun otherDayIsObviousAndTodayIsOneTapAway() {
        val events = mutableListOf<TodayEvent>()
        val past = state.copy(date = today.minusDays(2), meals = state.meals.map { it.copy(entries = emptyList(), kcal = 0.0) })
        compose.setContent { ForkcastTheme { TodayScreen(past, onEvent = { events += it }) } }
        compose.onNodeWithText("Fri, Oct 16").assertIsDisplayed()
        compose.onNodeWithText("2 days ago").assertIsDisplayed()
        compose.onNodeWithContentDescription("Go to today").performClick()
        assertThat(events).contains(TodayEvent.GoToToday)
    }

    @Test fun emptyDayPointsToTheLastLog() {
        val events = mutableListOf<TodayEvent>()
        val empty = state.copy(
            meals = state.meals.map { it.copy(entries = emptyList(), kcal = 0.0) },
            lastLogged = LoggedDay(today.minusDays(1), 6),
        )
        compose.setContent { ForkcastTheme { TodayScreen(empty, onEvent = { events += it }) } }
        row("Last logged: Yesterday · 6 entries").assertIsDisplayed()
        compose.onNodeWithText("Show").performClick()
        assertThat(events).contains(TodayEvent.PickDate(today.minusDays(1)))
    }

    @Test fun dayWithEntriesHasNoLastLoggedHint() {
        compose.setContent { ForkcastTheme { TodayScreen(state.copy(lastLogged = LoggedDay(today.minusDays(1), 6)), onEvent = {}) } }
        compose.onNodeWithText("Last logged", substring = true).assertDoesNotExist()
    }
}
