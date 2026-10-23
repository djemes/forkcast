package app.forkcast.core.model

import kotlinx.serialization.Serializable

/** Nutrient amounts keyed by [NutrientKey] in that key's unit (g, mg, µg, kcal). */
typealias Nutrients = Map<NutrientKey, Double>

/** Where a food or log entry's numbers came from (shown as a source badge). */
@Serializable
enum class FoodSource {
    USDA, OFF, LABEL, CUSTOM, QUICK_ADD, RECIPE, AI,
    /** A described meal ("Describe", no photo) and the foods saved from one; badge "AI · text" (v7). */
    AI_TEXT,
    ;

    /** Estimated by an AI model, from a photo or a description. */
    val isAi: Boolean get() = this == AI || this == AI_TEXT
}

/** One way to measure a food, e.g. "1 cup" = 158 g. */
@Serializable
data class ServingSize(val label: String, val grams: Double)

data class Food(
    val id: String,
    val name: String,
    val brand: String?,
    val source: FoodSource,
    /** fdcId for USDA, product code for OFF. */
    val sourceId: String?,
    /** Normalized GTIN-14, if known. */
    val barcode: String?,
    val servingSizes: List<ServingSize>,
    val nutrientsPer100g: Nutrients,
    val isFavorite: Boolean,
    val lastUsedAt: Long?,
    val lastQuantity: Double?,
    val lastServingLabel: String?,
    /** Counts toward the day's water when logged: true/false set by the user, null = decided by the drink rule. */
    val countsAsWater: Boolean? = null,
    /** What the user typed when this food was saved from "Describe" (search matches it too). */
    val describedAs: String? = null,
)

/** Scales per-100 g nutrients to an amount in grams. */
fun Nutrients.forGrams(grams: Double): Nutrients = mapValues { (_, per100) -> per100 * grams / 100.0 }

operator fun Nutrients.plus(other: Nutrients): Nutrients =
    (keys + other.keys).associateWith { (this[it] ?: 0.0) + (other[it] ?: 0.0) }

fun Iterable<Nutrients>.sumNutrients(): Nutrients = fold(emptyMap()) { acc, n -> acc + n }

val Nutrients.kcal: Double get() = this[NutrientKey.ENERGY_KCAL] ?: 0.0
