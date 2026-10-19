package app.forkcast.ui.food

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.network.FoodApiError
import app.forkcast.fakes.FakeFoodRepository
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Add food from search" happy path (SPEC §9), screen by screen with fakes. */
@RunWith(AndroidJUnit4::class)
class FoodScreensTest {

    @get:Rule val compose = createComposeRule()

    private val rice = FakeFoodRepository.food("Rice, white, cooked", kcal = 130.0).copy(id = "r")

    @Test fun searchShowsSectionsAndOpensAResult() {
        var typed = ""
        var opened: String? = null
        val state = SearchUiState(
            query = "rice",
            local = listOf(rice),
            usda = RemoteResults.Done(listOf(FakeFoodRepository.food("Rice, brown", sourceId = "2"))),
            off = RemoteResults.Failed(FoodApiError.Offline()),
            usingDemoKey = true,
        )
        compose.setContent { ForkcastTheme { SearchScreen(state, { typed = it }, { opened = it.name }, {}, {}) } }
        compose.onNode(hasSetTextAction()).performTextReplacement("rice white")
        assertThat(typed).isEqualTo("rice white")
        compose.onNodeWithText("Your foods").assertIsDisplayed()
        // 1 cup = 158 g → 205 kcal per serving
        compose.onAllNodesWithText("205 kcal")[0].assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("No connection. Showing your own foods only."))
        compose.onNodeWithText("No connection. Showing your own foods only.").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Rice, white, cooked"))
        compose.onNodeWithText("Rice, white, cooked").performClick()
        assertThat(opened).isEqualTo("Rice, white, cooked")
    }

    @Test fun detailNamesTheMealAndAdds() {
        var added = false
        val state = FoodDetailState(
            food = rice,
            servings = FoodLogging.servingOptions(rice),
            serving = rice.servingSizes.first(),
            quantity = 1.5,
            meals = FakeMealSlotRepository.DEFAULT,
            mealSlotId = "l",
            units = UnitPrefs.US,
        )
        compose.setContent {
            ForkcastTheme { FoodDetailScreen(state, {}, {}, {}, {}, {}, {}, {}, onAdd = { added = true }) }
        }
        // 1.5 cups = 237 g → 308 kcal
        compose.onNodeWithText("308").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("= 237 g").assertIsDisplayed()
        compose.onNodeWithText("Add to Lunch").assertIsEnabled().performClick()
        assertThat(added).isTrue()
    }

    @Test fun detailNamesTheDayWhenItIsntToday() {
        val state = FoodDetailState(
            food = rice,
            servings = FoodLogging.servingOptions(rice),
            serving = rice.servingSizes.first(),
            quantity = 1.0,
            meals = FakeMealSlotRepository.DEFAULT,
            mealSlotId = "l",
            units = UnitPrefs.US,
            daysFromToday = -1,
            date = java.time.LocalDate.of(2026, 10, 17),
        )
        compose.setContent { ForkcastTheme { FoodDetailScreen(state, {}, {}, {}, {}, {}, {}, {}, onAdd = {}) } }
        compose.onNodeWithText("Add to Lunch · Yesterday").assertIsDisplayed()
    }
}
