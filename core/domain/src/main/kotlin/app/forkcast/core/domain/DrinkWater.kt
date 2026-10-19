package app.forkcast.core.domain

import app.forkcast.core.model.Food
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Nutrients

/**
 * Drinks that count toward the day's water (owner, 2026-10-19): water, sparkling water and
 * unsweetened tea, hot or iced. Sugary drinks, alcohol, milk-based drinks and shakes don't.
 * The user can override it per food ([Food.countsAsWater]).
 */
object DrinkWater {

    /** Words that make a food a water-like drink. */
    private val drinks = listOf(
        "water", "sparkling", "seltzer", "club soda", "soda water", "tonic water", "mineral",
        "tea", "teas", "chá", "infusion", "tisane", "chamomile", "rooibos", "mate", "água", "agua",
    ).map { Regex("""(?<![\p{L}])${Regex.escape(it)}(?![\p{L}])""") }

    /** Words that rule it out: food, sugar, alcohol, milk, shakes. */
    private val excluded = listOf(
        "watermelon", "watercress", "water chestnut", "coconut water", "tonic",
        "milk", "latte", "cream", "shake", "smoothie", "protein", "juice", "nectar", "cola", "lemonade",
        "sweet", "sweetened", "sugar", "sugary", "syrup", "honey", "bubble", "boba", "chai", "kombucha", "beer", "wine",
        "vodka", "whiskey", "whisky", "rum", "gin", "liquor", "cocktail", "cider", "energy", "biscuit", "cake", "cookie",
        "leite", "suco", "açúcar", "doce", "adoçado",
    ).map { Regex("""(?<![\p{L}])${Regex.escape(it)}s?(?![\p{L}])""") }

    /** Most a counted drink may carry per 100 g (an unsweetened tea is ~1 kcal, 0 g sugar). */
    private const val MAX_KCAL_PER_100G = 5.0
    private const val MAX_SUGAR_PER_100G = 1.0
    private const val MAX_PROTEIN_PER_100G = 0.5

    fun countsAsWater(food: Food): Boolean = food.countsAsWater ?: looksLikeWater(listOfNotNull(food.name, food.brand).joinToString(" "), food.nutrientsPer100g)

    /** The automatic rule, from the name and nutrients per 100 g. */
    fun looksLikeWater(name: String, per100g: Nutrients): Boolean {
        // Whole words ("tea", not "steak"); "unsweetened" mustn't trip the "sweet" exclusion.
        val n = name.lowercase().replace("unsweetened", "plain").replace("no sugar", "plain").replace("sugar-free", "plain")
        if (drinks.none { it.containsMatchIn(n) }) return false
        if (excluded.any { it.containsMatchIn(n) }) return false
        if ((per100g[NutrientKey.ALCOHOL_G] ?: 0.0) > 0.0) return false
        if ((per100g[NutrientKey.ENERGY_KCAL] ?: 0.0) > MAX_KCAL_PER_100G) return false
        if ((per100g[NutrientKey.SUGARS_G] ?: 0.0) > MAX_SUGAR_PER_100G) return false
        if ((per100g[NutrientKey.PROTEIN_G] ?: 0.0) > MAX_PROTEIN_PER_100G) return false
        return true
    }

    /** Water a logged amount adds: 1 g of drink ≈ 1 ml. Null when the drink doesn't count or grams are unknown. */
    fun waterMl(counts: Boolean, grams: Double?): Double? = if (counts) grams?.takeIf { it > 0 } else null
}
