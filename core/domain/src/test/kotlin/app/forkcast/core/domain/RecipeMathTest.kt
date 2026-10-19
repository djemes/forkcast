package app.forkcast.core.domain

import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.RecipeIngredient
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecipeMathTest {

    private fun ing(grams: Double, kcal: Double, protein: Double) =
        RecipeIngredient("", null, "x", null, 1.0, "g", grams, mapOf(NutrientKey.ENERGY_KCAL to kcal, NutrientKey.PROTEIN_G to protein))

    @Test fun spreadsTotalsOverServingsAndRawWeight() {
        // 500 g rice (650 kcal) + 400 g chicken (660 kcal, 124 g protein), 4 servings.
        val r = RecipeMath.compute(listOf(ing(500.0, 650.0, 13.0), ing(400.0, 660.0, 124.0)), 4.0, null)!!
        assertThat(r.total[NutrientKey.ENERGY_KCAL]).isEqualTo(1310.0)
        assertThat(r.rawGrams).isEqualTo(900.0)
        assertThat(r.finalGrams).isEqualTo(900.0)
        assertThat(r.servingGrams).isEqualTo(225.0)
        assertThat(r.perServing[NutrientKey.ENERGY_KCAL]).isEqualTo(327.5)
        assertThat(r.per100g[NutrientKey.PROTEIN_G]!!).isWithin(1e-9).of(137.0 / 9)
    }

    @Test fun cookedWeightChangesPerGramNotPerServing() {
        // Simmered down from 900 g to 750 g.
        val r = RecipeMath.compute(listOf(ing(500.0, 650.0, 13.0), ing(400.0, 660.0, 124.0)), 4.0, 750.0)!!
        assertThat(r.finalGrams).isEqualTo(750.0)
        assertThat(r.servingGrams).isEqualTo(187.5)
        assertThat(r.perServing[NutrientKey.ENERGY_KCAL]).isEqualTo(327.5)
        assertThat(r.per100g[NutrientKey.ENERGY_KCAL]!!).isWithin(1e-9).of(1310.0 / 7.5)
        // One serving by weight gives the same calories as one serving.
        assertThat(r.per100g[NutrientKey.ENERGY_KCAL]!! * r.servingGrams / 100).isWithin(1e-9).of(327.5)
        assertThat(RecipeMath.servingSizes(r).single().grams).isEqualTo(187.5)
    }

    @Test fun nothingToComputeIsNull() {
        assertThat(RecipeMath.compute(emptyList(), 4.0, null)).isNull()
        assertThat(RecipeMath.compute(listOf(ing(100.0, 100.0, 1.0)), 0.0, null)).isNull()
        assertThat(RecipeMath.compute(listOf(ing(0.0, 100.0, 1.0)), 2.0, null)).isNull()
    }
}
