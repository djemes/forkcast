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

    /** Any drink, counted or not: where the "counts toward water" switch is worth showing. */
    private val beverages = listOf(
        "water", "tea", "coffee", "espresso", "juice", "milk", "soda", "cola", "drink", "beverage", "latte", "shake", "smoothie",
        "lemonade", "kombucha", "beer", "wine", "seltzer", "sparkling", "cappuccino", "mocha", "café", "suco", "leite", "água", "chá",
    ).map { Regex("""(?<![\p{L}])${Regex.escape(it)}s?(?![\p{L}])""") }

    fun isDrink(food: Food): Boolean = food.countsAsWater != null || countsAsWater(food) || isDrinkName(food.name)

    fun isDrinkName(name: String): Boolean = beverages.any { it.containsMatchIn(name.lowercase()) }

    fun countsAsWater(food: Food): Boolean = food.countsAsWater ?: looksLikeWater(listOfNotNull(food.name, food.brand).joinToString(" "), food.nutrientsPer100g)

    /**
     * An item with no food behind it, only a name and a nutrient snapshot: a photo or text
     * estimate's component, or a saved-meal item from before drinks counted (bug, owner
     * 2026-10-23: sparkling water from a photo estimate didn't add to the day's water).
     * [altName] is a second name to try (the model's USDA-style query, e.g. "Water, carbonated"
     * for "Perrier"); it can't override an exclusion in [name].
     */
    fun snapshotCountsAsWater(name: String, altName: String?, nutrients: Nutrients, grams: Double?): Boolean {
        if (grams == null || grams <= 0) return false
        val per100g = nutrients.mapValues { (_, v) -> v * 100.0 / grams }
        if (looksLikeWater(name, per100g)) return true
        if (altName.isNullOrBlank() || excluded.any { it.containsMatchIn(normalize(name)) }) return false
        return looksLikeWater(altName, per100g)
    }

    /** The automatic rule, from the name and nutrients per 100 g. */
    fun looksLikeWater(name: String, per100g: Nutrients): Boolean {
        val n = normalize(name)
        if (drinks.none { it.containsMatchIn(n) }) return false
        if (excluded.any { it.containsMatchIn(n) }) return false
        if ((per100g[NutrientKey.ALCOHOL_G] ?: 0.0) > 0.0) return false
        if ((per100g[NutrientKey.ENERGY_KCAL] ?: 0.0) > MAX_KCAL_PER_100G) return false
        if ((per100g[NutrientKey.SUGARS_G] ?: 0.0) > MAX_SUGAR_PER_100G) return false
        if ((per100g[NutrientKey.PROTEIN_G] ?: 0.0) > MAX_PROTEIN_PER_100G) return false
        return true
    }

    /**
     * Whole words ("tea", not "steak"); "unsweetened" mustn't trip the "sweet" exclusion; "brewed
     * with water" (USDA's coffee and cocoa names) says how it was made, not that it's water.
     */
    private fun normalize(name: String): String = name.lowercase()
        .replace("unsweetened", "plain").replace("no sugar", "plain").replace("sugar-free", "plain")
        .replace(Regex("""(prepared|made|brewed|reconstituted)\s+with\s+(tap\s+)?water"""), "")

    /** Water a logged amount adds: 1 g of drink ≈ 1 ml. Null when the drink doesn't count or grams are unknown. */
    fun waterMl(counts: Boolean, grams: Double?): Double? = if (counts) grams?.takeIf { it > 0 } else null
}
