package app.forkcast.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Recipe
import app.forkcast.core.model.RecipeIngredient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RecipeRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java).allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-19T16:00:00Z"), ZoneId.of("America/New_York"))
    private val repo = RoomRecipeRepository(db, db.recipeDao(), db.foodDao(), clock)

    @After fun tearDown() = db.close()

    private fun ing(name: String, grams: Double, kcal: Double) =
        RecipeIngredient("", null, name, null, grams, "g", grams, mapOf(NutrientKey.ENERGY_KCAL to kcal))

    @Test fun savingCreatesALoggableFoodPerServing() = runTest {
        val id = repo.save(Recipe("", " Chili ", 4.0, null, listOf(ing("Beans", 400.0, 480.0), ing("Beef", 400.0, 1000.0))))
        val recipe = repo.get(id)!!
        assertThat(recipe.name).isEqualTo("Chili")
        assertThat(recipe.ingredients.map { it.displayName }).containsExactly("Beans", "Beef").inOrder()
        val food = db.foodDao().get(recipe.foodId!!)!!
        assertThat(food.source).isEqualTo(FoodSource.RECIPE)
        assertThat(food.sourceId).isEqualTo(id)
        assertThat(food.servingSizes.single().grams).isEqualTo(200.0)
        assertThat(food.nutrientsPer100g[NutrientKey.ENERGY_KCAL]).isEqualTo(185.0)
        assertThat(repo.forFood(food.id)!!.id).isEqualTo(id)
    }

    @Test fun editingReplacesIngredientsAndUpdatesTheSameFood() = runTest {
        val id = repo.save(Recipe("", "Chili", 4.0, null, listOf(ing("Beans", 400.0, 480.0), ing("Beef", 400.0, 1000.0))))
        val first = repo.get(id)!!
        val kept = first.ingredients.first()
        repo.save(first.copy(servings = 2.0, cookedWeightG = 600.0, ingredients = listOf(kept)))
        val second = repo.get(id)!!
        assertThat(second.ingredients.map { it.id }).containsExactly(kept.id)
        assertThat(second.foodId).isEqualTo(first.foodId)
        val food = db.foodDao().get(second.foodId!!)!!
        assertThat(food.servingSizes.single().grams).isEqualTo(300.0)
        assertThat(food.nutrientsPer100g[NutrientKey.ENERGY_KCAL]).isEqualTo(80.0)
        assertThat(repo.recipes.first().single().ingredients).hasSize(1)
    }

    @Test fun deleteHidesRecipeAndItsFood() = runTest {
        val id = repo.save(Recipe("", "Chili", 4.0, null, listOf(ing("Beans", 400.0, 480.0))))
        val foodId = repo.get(id)!!.foodId!!
        repo.delete(id)
        assertThat(repo.get(id)).isNull()
        assertThat(repo.recipes.first()).isEmpty()
        assertThat(db.foodDao().get(foodId)!!.deletedAt).isNotNull()
    }
}
