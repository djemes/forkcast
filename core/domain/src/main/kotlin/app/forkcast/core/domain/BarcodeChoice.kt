package app.forkcast.core.domain

import app.forkcast.core.model.Food
import app.forkcast.core.model.NutrientKey

/** Picks between USDA Branded and Open Food Facts results for the same barcode (SPEC §4.4). */
object BarcodeChoice {

    /** Core nutrients count double: a record with kcal + macros beats one with many micros only. */
    private val core = setOf(NutrientKey.ENERGY_KCAL, NutrientKey.PROTEIN_G, NutrientKey.CARBS_G, NutrientKey.FAT_G)

    fun completeness(food: Food): Int =
        food.nutrientsPer100g.keys.sumOf { if (it in core) 2 else 1 } + if (food.servingSizes.isNotEmpty()) 1 else 0

    /** GS1 prefixes 000–139 are assigned to the US and Canada. */
    fun isUsMarket(gtin14: String): Boolean {
        val gs1 = gtin14.drop(1).take(3).toIntOrNull() ?: return false
        return gs1 in 0..139
    }

    /** More complete record wins; on a tie, USDA for US/Canada products, otherwise Open Food Facts. */
    fun choose(usda: Food?, off: Food?, gtin14: String): Food? {
        if (usda == null) return off
        if (off == null) return usda
        val u = completeness(usda)
        val o = completeness(off)
        return when {
            u > o -> usda
            o > u -> off
            isUsMarket(gtin14) -> usda
            else -> off
        }
    }
}
