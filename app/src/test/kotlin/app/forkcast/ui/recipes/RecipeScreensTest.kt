package app.forkcast.ui.recipes

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Recipe
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecipeScreensTest {

    @get:Rule val compose = createComposeRule()

    private val rice = DraftIngredient("i1", "f1", "Rice", null, 2.0, "1 cup", 316.0, mapOf(NutrientKey.ENERGY_KCAL to 408.0, NutrientKey.PROTEIN_G to 8.4))

    private fun editor(draft: RecipeDraft, logAfterSave: Boolean = false, onSave: () -> Unit = {}, onRemove: (Int) -> Unit = {}) =
        compose.setContent {
            ForkcastTheme {
                RecipeEditScreen(
                    state = RecipeEditState(draft, draft.id.isEmpty(), UnitPrefs.METRIC),
                    onBack = {}, onName = {}, onServings = {}, onCookedWeight = {}, onQuantity = { _, _ -> },
                    onRemove = onRemove, onAddIngredient = {}, onScanIngredient = {}, onSave = onSave, onDelete = {},
                    logAfterSave = logAfterSave,
                )
            }
        }

    @Test fun showsPerServingValuesAndSaves() {
        var saved = 0
        var removed = -1
        editor(RecipeDraft(name = "Rice bowl", servings = 4.0, ingredients = listOf(rice)), logAfterSave = true, onSave = { saved++ }, onRemove = { removed = it })
        // 408 kcal over 4 servings; 316 g / 4 = 79 g each.
        compose.onNodeWithText("102", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("One serving is 79", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("2 × 1 cup", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Remove Rice").performScrollTo().performClick()
        assertThat(removed).isEqualTo(0)
        compose.onNodeWithText("Save and log").assertIsEnabled().performClick()
        assertThat(saved).isEqualTo(1)
    }

    @Test fun saveWaitsForANameAndAnIngredient() {
        editor(RecipeDraft(name = "", ingredients = listOf(rice)))
        compose.onNodeWithText("Give it a name").assertExists()
        compose.onNodeWithText("Save recipe").assertIsNotEnabled()
    }

    @Test fun listLogsWhenLoggingAndEditsWithThePencil() {
        val recipe = Recipe("r1", "Rice bowl", 4.0, null, listOf(rice.toModel()), "food1")
        var logged: Recipe? = null
        var edited: Recipe? = null
        compose.setContent {
            ForkcastTheme {
                RecipesScreen(RecipesUiState(listOf(recipe), UnitPrefs.METRIC, emptyMap()), "Lunch", {}, { logged = it }, { edited = it }, {})
            }
        }
        compose.onNodeWithText("Tap a recipe to log it to Lunch.").assertExists()
        compose.onNodeWithText("Rice bowl").performClick()
        assertThat(logged).isEqualTo(recipe)
        compose.onNodeWithContentDescription("Edit Rice bowl").performClick()
        assertThat(edited).isEqualTo(recipe)
    }
}
