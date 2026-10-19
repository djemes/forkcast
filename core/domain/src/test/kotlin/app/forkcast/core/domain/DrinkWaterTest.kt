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
