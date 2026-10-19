package app.forkcast.core.domain

import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.RecipeIngredient
import app.forkcast.core.model.ServingSize
import app.forkcast.core.model.sumNutrients

/**
 * Recipe nutrition (SPEC §4.5): the ingredients' nutrients added up, spread over the servings
 * and over the dish's weight. With a cooked weight, water lost or gained in cooking changes the
 * per-gram values but not the per-serving ones.
 */
object RecipeMath {

    /** Label of the recipe food's serving ("1 serving" in the food detail's chips). */
    const val SERVING_LABEL = "serving"

    data class Result(
        val total: Nutrients,
        /** Sum of the ingredients' weights. */
        val rawGrams: Double,
        /** The dish's weight: the cooked weight when given, else [rawGrams]. */
        val finalGrams: Double,
        val per100g: Nutrients,
        val servingGrams: Double,
        val perServing: Nutrients,
    )

    /** Null when nothing can be computed (no ingredients, no weight, or no servings). */
    fun compute(ingredients: List<RecipeIngredient>, servings: Double, cookedWeightG: Double?): Result? {
        if (ingredients.isEmpty() || servings <= 0) return null
        val total = ingredients.map { it.nutrients }.sumNutrients()
        val raw = ingredients.sumOf { it.grams }
        val final = cookedWeightG?.takeIf { it > 0 } ?: raw
        if (final <= 0) return null
        return Result(
            total = total,
            rawGrams = raw,
            finalGrams = final,
            per100g = total.mapValues { (_, v) -> v / final * 100.0 },
            servingGrams = final / servings,
            perServing = total.mapValues { (_, v) -> v / servings },
        )
    }

    /** The recipe food's servings: one serving by weight (g and oz are added by [FoodLogging.servingOptions]). */
    fun servingSizes(result: Result): List<ServingSize> = listOf(ServingSize(SERVING_LABEL, result.servingGrams))
}
