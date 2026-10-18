package app.forkcast.core.domain

import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.ServingSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BarcodeChoiceTest {

    private fun food(source: FoodSource, vararg keys: NutrientKey, serving: Boolean = true) = Food(
        "", source.name, null, source, "x", null,
        if (serving) listOf(ServingSize("1 bar", 40.0)) else emptyList(),
        keys.associateWith { 1.0 }, false, null, null, null,
    )

    private val core = arrayOf(NutrientKey.ENERGY_KCAL, NutrientKey.PROTEIN_G, NutrientKey.CARBS_G, NutrientKey.FAT_G)

    @Test fun `more complete record wins`() {
        val usda = food(FoodSource.USDA, *core)
        val off = food(FoodSource.OFF, *core, NutrientKey.FIBER_G, NutrientKey.SODIUM_MG)
        assertThat(BarcodeChoice.choose(usda, off, "00016000275287")!!.source).isEqualTo(FoodSource.OFF)
    }

    @Test fun `tie prefers usda for us products and off elsewhere`() {
        val usda = food(FoodSource.USDA, *core)
        val off = food(FoodSource.OFF, *core)
        assertThat(BarcodeChoice.choose(usda, off, "00016000275287")!!.source).isEqualTo(FoodSource.USDA)
        assertThat(BarcodeChoice.choose(usda, off, "03017624010701")!!.source).isEqualTo(FoodSource.OFF)
    }

    @Test fun `one side missing`() {
        val off = food(FoodSource.OFF, *core)
        assertThat(BarcodeChoice.choose(null, off, "03017624010701")).isEqualTo(off)
        assertThat(BarcodeChoice.choose(null, null, "03017624010701")).isNull()
    }

    @Test fun `us market prefixes`() {
        assertThat(BarcodeChoice.isUsMarket("00016000275287")).isTrue()   // 001
        assertThat(BarcodeChoice.isUsMarket("00681131000005")).isTrue()   // 068 (Canada block 060-069 isn't separate; still US/CA)
        assertThat(BarcodeChoice.isUsMarket("03017624010701")).isFalse()  // 301 France
        assertThat(BarcodeChoice.isUsMarket("07891000100103")).isFalse()  // 789 Brazil
    }
}
