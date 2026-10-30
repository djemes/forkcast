package app.forkcast.ui.today

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.SavedMeal
import app.forkcast.core.model.SavedMealItem
import app.forkcast.core.model.ServingSize
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Log sheet › Recent | Favorites (roadmap 3.10). */
@RunWith(AndroidJUnit4::class)
class LogSheetTest {

    @get:Rule val compose = createComposeRule()

    private fun food(name: String, favorite: Boolean = false) = Food(
        name, name, null, FoodSource.USDA, null, null, listOf(ServingSize("1 cup", 100.0)),
        mapOf(NutrientKey.ENERGY_KCAL to 150.0), isFavorite = favorite, lastUsedAt = 1, lastQuantity = 1.0, lastServingLabel = "1 cup",
    )

    private val meal = SavedMeal(
        "m1", "Usual breakfast",
        listOf(SavedMealItem("i1", null, "Oats", null, 1.0, "1 cup", 80.0, mapOf(NutrientKey.ENERGY_KCAL to 300.0), FoodSource.USDA)),
    )

    @Test fun recentAndFavoritesTabsLogEditAndUnfavorite() {
        val added = mutableListOf<String>()
        val logged = mutableListOf<String>()
        val edited = mutableListOf<String>()
        val unfavorited = mutableListOf<String>()
        compose.setContent {
            ForkcastTheme {
                LogSheet(
                    meals = listOf(MealSlot("b", "Breakfast", 0, null, null)),
                    selectedMealId = "b",
                    onSelectMeal = {},
                    onDismiss = {},
                    recents = listOf(food("Banana")),
                    favorites = listOf(food("Greek yogurt", favorite = true)),
                    favoriteMeals = listOf(meal),
                    f = UnitFormatter(UnitPrefs.US, Locale.US),
                    onAddRecent = { added += it.name },
                    onLogMeal = { logged += it.name },
                    onEditFavorite = { edited += it.name },
                    onUnfavorite = { unfavorited += it.name },
                )
            }
        }
        compose.onNodeWithText("Banana").performScrollTo().performClick()
        assertThat(added).containsExactly("Banana")

        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        compose.onNodeWithText("Usual breakfast").performScrollTo().performClick()
        assertThat(logged).containsExactly("Usual breakfast")
        compose.onNodeWithText("Meal · 1 item · 300 kcal").assertExists()

        // Foods only: the meal goes.
        compose.onNodeWithText("Foods").performScrollTo().performClick()
        compose.onNodeWithText("Usual breakfast").assertDoesNotExist()
        compose.onNodeWithContentDescription("More options for Greek yogurt").performScrollTo().performClick()
        compose.onNodeWithText("Edit").performClick()
        assertThat(edited).containsExactly("Greek yogurt")
        compose.onNodeWithContentDescription("More options for Greek yogurt").performScrollTo().performClick()
        compose.onNodeWithText("Unfavorite").performClick()
        assertThat(unfavorited).containsExactly("Greek yogurt")

        // Meals only.
        compose.onNodeWithText("Meals").performScrollTo().performClick()
        compose.onNodeWithText("Greek yogurt").assertDoesNotExist()
        compose.onNodeWithText("Usual breakfast").assertExists()
    }

    @Test fun emptyTabsSayHowToFillThem() {
        compose.setContent {
            ForkcastTheme {
                LogSheet(
                    meals = listOf(MealSlot("b", "Breakfast", 0, null, null)),
                    selectedMealId = "b",
                    onSelectMeal = {},
                    onDismiss = {},
                    f = UnitFormatter(UnitPrefs.US, Locale.US),
                )
            }
        }
        compose.onNodeWithText("Foods you log show up here.").performScrollTo().assertExists()
        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        compose.onNodeWithText("No favorites yet. Long-press an entry on Today, or tap the star on a food.").performScrollTo().assertExists()
    }
}
