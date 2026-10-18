package app.forkcast.ui.today

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.model.MealSlot
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TodayScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 18)
    private val state = TodayUiState(
        date = today,
        today = today,
        meals = listOf(
            MealSlot("b", "Breakfast", 0, 240, 660),
            MealSlot("l", "Lunch", 1, 660, 900),
            MealSlot("d", "Dinner", 2, 1020, 1320),
            MealSlot("s", "Snacks", 3, null, null),
        ),
        defaultMealId = "l",
    )

    @Test fun showsMealsAndOpensLogSheetForDefaultMeal() {
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = {}, onOpenSettings = {}) } }

        compose.onNodeWithText("Breakfast").assertIsDisplayed()
        compose.onNodeWithText("Today, Oct 18").assertIsDisplayed()

        compose.onNodeWithContentDescription("Log").performClick()
        compose.onNodeWithText("Log to Lunch").assertIsDisplayed()

        // Meal chips in the sheet switch the target meal.
        compose.onAllNodesWithText("Dinner").filterToOne(hasClickAction()).performClick()
        compose.onNodeWithText("Log to Dinner").assertIsDisplayed()
    }

    @Test fun mealAddButtonOpensSheetForThatMeal() {
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = {}, onOpenSettings = {}) } }
        compose.onNodeWithContentDescription("Add to Breakfast").performClick()
        compose.onNodeWithText("Log to Breakfast").assertIsDisplayed()
    }

    @Test fun arrowsSendDayEvents() {
        val events = mutableListOf<TodayEvent>()
        compose.setContent { ForkcastTheme { TodayScreen(state, onEvent = { events += it }, onOpenSettings = {}) } }
        compose.onNodeWithContentDescription("Previous day").performClick()
        compose.onNodeWithContentDescription("Next day").performClick()
        assertThat(events).containsExactly(TodayEvent.PreviousDay, TodayEvent.NextDay).inOrder()
    }

    @Test fun otherDaysOfferGoToToday() {
        val events = mutableListOf<TodayEvent>()
        compose.setContent {
            ForkcastTheme { TodayScreen(state.copy(date = today.minusDays(3)), onEvent = { events += it }, onOpenSettings = {}) }
        }
        compose.onNodeWithText("Go to today").performClick()
        assertThat(events).containsExactly(TodayEvent.GoToToday)
    }

    @Test fun worksAtDoubleFontScale() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                ForkcastTheme { TodayScreen(state, onEvent = {}, onOpenSettings = {}) }
            }
        }
        compose.onNodeWithText("Breakfast").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Log").assertIsDisplayed()
    }
}
