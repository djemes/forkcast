package app.forkcast.core.domain

import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.ServingSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class FoodLoggingTest {

    private val rice = Food(
        "f1", "Rice, white, cooked", null, FoodSource.USDA, "168878", null,
        listOf(ServingSize("1 cup", 158.0)),
        mapOf(NutrientKey.ENERGY_KCAL to 130.0, NutrientKey.PROTEIN_G to 2.69), false, null, null, null,
    )
    private val day = LocalDate.of(2026, 10, 18)

    @Test fun `servings are the food's own plus grams and ounces`() {
        assertThat(FoodLogging.servingOptions(rice).map { it.label }).containsExactly("1 cup", "g", "oz").inOrder()
    }

    @Test fun `entry snapshots nutrients for the amount`() {
        val e = FoodLogging.entry(rice, rice.servingSizes[0], 1.5, day, "lunch", Instant.EPOCH)
        assertThat(e.grams).isWithin(1e-9).of(237.0)
        assertThat(e.nutrients[NutrientKey.ENERGY_KCAL]).isWithin(1e-9).of(308.1)
        assertThat(e.foodId).isEqualTo("f1")
        assertThat(e.source).isEqualTo(FoodSource.USDA)
    }

    @Test fun `ounces convert to grams`() {
        val oz = FoodLogging.servingOptions(rice).first { it.label == "oz" }
        val e = FoodLogging.entry(rice, oz, 2.0, day, "lunch", Instant.EPOCH)
        assertThat(e.grams).isWithin(1e-6).of(56.69904625)
    }

    @Test fun `default serving prefers last used`() {
        assertThat(FoodLogging.defaultServing(rice).let { it.first.label to it.second }).isEqualTo("1 cup" to 1.0)
        val used = rice.copy(lastServingLabel = "g", lastQuantity = 180.0)
        assertThat(FoodLogging.defaultServing(used).let { it.first.label to it.second }).isEqualTo("g" to 180.0)
        val noServings = rice.copy(servingSizes = emptyList())
        assertThat(FoodLogging.defaultServing(noServings).let { it.first.label to it.second }).isEqualTo("g" to 100.0)
    }

    @Test fun `quick add carries only what was entered`() {
        val e = FoodLogging.quickAdd("Coffee", 45.0, null, 5.0, null, day, "b", Instant.EPOCH)
        assertThat(e.nutrients).containsExactly(NutrientKey.ENERGY_KCAL, 45.0, NutrientKey.CARBS_G, 5.0)
        assertThat(e.source).isEqualTo(FoodSource.QUICK_ADD)
        assertThat(e.grams).isNull()
    }

    @Test fun `per serving to per 100 g`() {
        val per100 = FoodLogging.perServingToPer100g(mapOf(NutrientKey.ENERGY_KCAL to 110.0), 28.0)
        assertThat(per100[NutrientKey.ENERGY_KCAL]).isWithin(1e-9).of(392.857142857)
    }
}
