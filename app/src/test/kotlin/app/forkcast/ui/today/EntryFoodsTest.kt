package app.forkcast.ui.today

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.repository.EntryFoods
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.ServingSize
import app.forkcast.fakes.FakeAiEstimateRepository
import app.forkcast.fakes.FakeFoodRepository
import app.forkcast.fakes.FakeLogRepository
import app.forkcast.fakes.TestData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** "Save as food" and ★ Favorite share one food per entry (owner, 2026-10-30). */
@RunWith(AndroidJUnit4::class)
class EntryFoodsTest {
    private val foods = FakeFoodRepository()
    private val log = FakeLogRepository()
    private val estimates = FakeAiEstimateRepository()
    private val entryFoods = EntryFoods(foods, log, estimates)

    @Test fun saveAsFoodThenFavoriteStarsTheSameFood() = runTest {
        val id = log.add(listOf(TestData.entry("Quick add", "l", 300.0).copy(source = FoodSource.QUICK_ADD))).single()
        val foodId = entryFoods.saveAsFood(log.get(id)!!)!!
        assertThat(log.get(id)!!.foodId).isEqualTo(foodId)
        assertThat(foods.foods.value[foodId]!!.isFavorite).isFalse()

        assertThat(entryFoods.favorite(log.get(id)!!)).isEqualTo(foodId)
        assertThat(foods.foods.value).hasSize(1)
        assertThat(foods.foods.value[foodId]!!.isFavorite).isTrue()
    }

    @Test fun favoriteThenSaveAsFoodMakesNoSecondFood() = runTest {
        val id = log.add(listOf(TestData.entry("Quick add", "l", 300.0).copy(source = FoodSource.QUICK_ADD))).single()
        val foodId = entryFoods.favorite(log.get(id)!!)!!
        assertThat(foods.foods.value[foodId]!!.favoriteServingLabel).isEqualTo(FoodLogging.SERVING_LABEL)
        assertThat(entryFoods.saveAsFood(log.get(id)!!)).isEqualTo(foodId)
        assertThat(foods.foods.value).hasSize(1)
    }

    @Test fun anAiEntryIsSavedFromItsEstimate() = runTest {
        val id = log.add(listOf(TestData.entry("Chipotle chicken bowl", "l", 900.0).copy(source = FoodSource.AI, aiEstimateId = "a1"))).single()
        entryFoods.saveAsFood(log.get(id)!!)
        assertThat(estimates.calls).contains("food:Chipotle chicken bowl")
    }

    @Test fun aDatabaseFoodIsStarredWithTheEntrysAmount() = runTest {
        val food = Food(
            "", "Banana", null, FoodSource.USDA, "123", null, listOf(ServingSize("1 medium", 118.0)), emptyMap(),
            isFavorite = false, lastUsedAt = null, lastQuantity = null, lastServingLabel = null,
        )
        val foodId = foods.saveCustom(food)
        val id = log.add(listOf(TestData.entry("Banana", "s", 158.0).copy(foodId = foodId, quantity = 1.5, servingLabel = "1 medium"))).single()
        entryFoods.favorite(log.get(id)!!)
        val starred = foods.foods.value[foodId]!!
        assertThat(starred.favoriteQuantity).isEqualTo(1.5)
        assertThat(starred.favoriteServingLabel).isEqualTo("1 medium")
    }
}
