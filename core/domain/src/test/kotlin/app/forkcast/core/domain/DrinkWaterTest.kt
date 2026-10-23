package app.forkcast.core.domain

import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.ServingSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DrinkWaterTest {

    private val zero: Nutrients = mapOf(NutrientKey.ENERGY_KCAL to 0.0)
    private fun counts(name: String, n: Nutrients = zero) = DrinkWater.looksLikeWater(name, n)

    @Test fun waterAndPlainTeaCount() {
        assertThat(counts("Water, tap")).isTrue()
        assertThat(counts("Sparkling water, lime")).isTrue()
        assertThat(counts("Seltzer")).isTrue()
        assertThat(counts("Club soda")).isTrue()
        assertThat(counts("Tea, black, brewed", mapOf(NutrientKey.ENERGY_KCAL to 1.0))).isTrue()
        assertThat(counts("Iced tea, unsweetened")).isTrue()
        assertThat(counts("Green tea")).isTrue()
        assertThat(counts("Ginger tea")).isTrue()
        assertThat(counts("Água com gás")).isTrue()
    }

    @Test fun sugaryAlcoholMilkAndShakesDont() {
        assertThat(counts("Sweet tea", mapOf(NutrientKey.ENERGY_KCAL to 35.0, NutrientKey.SUGARS_G to 9.0))).isFalse()
        assertThat(counts("Iced tea, sweetened")).isFalse()
        assertThat(counts("Chai latte")).isFalse()
        assertThat(counts("Bubble tea")).isFalse()
        assertThat(counts("Tonic water", mapOf(NutrientKey.ENERGY_KCAL to 34.0, NutrientKey.SUGARS_G to 8.0))).isFalse()
        assertThat(counts("Coconut water")).isFalse()
        assertThat(counts("Protein shake")).isFalse()
        assertThat(counts("Milk tea")).isFalse()
        assertThat(counts("Hard seltzer", mapOf(NutrientKey.ENERGY_KCAL to 30.0, NutrientKey.ALCOHOL_G to 3.5))).isFalse()
        // Not drinks at all.
        assertThat(counts("Watermelon, raw")).isFalse()
        assertThat(counts("Steak, grilled", mapOf(NutrientKey.ENERGY_KCAL to 250.0))).isFalse()
        assertThat(counts("Orange juice")).isFalse()
        assertThat(counts("Coffee, brewed")).isFalse()
    }

    @Test fun brewedWithWaterIsNotWater() {
        assertThat(counts("Coffee, brewed from grounds, prepared with tap water", mapOf(NutrientKey.ENERGY_KCAL to 1.0))).isFalse()
        assertThat(counts("Tea, black, brewed, prepared with tap water", mapOf(NutrientKey.ENERGY_KCAL to 1.0))).isTrue()
    }

    @Test fun estimateComponentsAndSavedItemsCount() {
        // A photo estimate's "Sparkling water", 355 g with the model's own zero-calorie nutrients.
        assertThat(DrinkWater.snapshotCountsAsWater("Sparkling water", "Water, carbonated", mapOf(NutrientKey.ENERGY_KCAL to 0.0), 355.0)).isTrue()
        // A brand name only: the USDA-style query decides.
        assertThat(DrinkWater.snapshotCountsAsWater("Perrier", "Water, bottled, carbonated", zero, 330.0)).isTrue()
        assertThat(DrinkWater.snapshotCountsAsWater("Large unsweetened iced tea", "Tea, black, brewed", mapOf(NutrientKey.ENERGY_KCAL to 4.0), 900.0)).isTrue()
        // The name's exclusion wins over the query; nutrients still rule out sugary drinks.
        assertThat(DrinkWater.snapshotCountsAsWater("Sweet tea", "Tea, black, brewed", zero, 400.0)).isFalse()
        assertThat(DrinkWater.snapshotCountsAsWater("Lemon water", null, mapOf(NutrientKey.ENERGY_KCAL to 60.0, NutrientKey.SUGARS_G to 14.0), 300.0)).isFalse()
        assertThat(DrinkWater.snapshotCountsAsWater("Rice, white, cooked", "Rice, white, cooked", mapOf(NutrientKey.ENERGY_KCAL to 200.0), 150.0)).isFalse()
        // Quick adds have no grams.
        assertThat(DrinkWater.snapshotCountsAsWater("Water", null, zero, null)).isFalse()
    }

    @Test fun userChoiceWinsAndEntriesSnapshotTheWater() {
        val tea = Food("f", "Tea, black, brewed", null, FoodSource.USDA, null, null, listOf(ServingSize("1 cup", 237.0)),
            mapOf(NutrientKey.ENERGY_KCAL to 1.0), false, null, null, null)
        assertThat(DrinkWater.countsAsWater(tea)).isTrue()
        assertThat(DrinkWater.countsAsWater(tea.copy(countsAsWater = false))).isFalse()
        val juice = tea.copy(name = "Orange juice", nutrientsPer100g = mapOf(NutrientKey.ENERGY_KCAL to 45.0))
        assertThat(DrinkWater.countsAsWater(juice.copy(countsAsWater = true))).isTrue()

        val entry = FoodLogging.entry(tea, tea.servingSizes.first(), 2.0, LocalDate.of(2026, 10, 19), "b", Instant.EPOCH)
        assertThat(entry.waterMl).isEqualTo(474.0)
        assertThat(FoodLogging.entry(juice, tea.servingSizes.first(), 1.0, LocalDate.of(2026, 10, 19), "b", Instant.EPOCH).waterMl).isNull()
    }
}
