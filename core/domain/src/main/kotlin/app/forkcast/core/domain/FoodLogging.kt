package app.forkcast.core.domain

import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.ServingSize
import app.forkcast.core.model.forGrams
import java.time.Instant
import java.time.LocalDate

/** Turning a food + amount into a log entry, and the serving choices a food offers. */
object FoodLogging {
    const val GRAM_LABEL = "g"
    const val OUNCE_LABEL = "oz"

    /** The food's own servings, then grams and ounces (SPEC §4.3). */
    fun servingOptions(food: Food): List<ServingSize> =
        (food.servingSizes + ServingSize(GRAM_LABEL, 1.0) + ServingSize(OUNCE_LABEL, UnitConversions.G_PER_OZ))
            .distinctBy { it.label.lowercase() }

    /** Last-used serving and quantity, else the food's first serving × 1, else 100 g. */
    fun defaultServing(food: Food): Pair<ServingSize, Double> {
        val options = servingOptions(food)
        food.lastServingLabel?.let { label ->
            options.firstOrNull { it.label == label }?.let { return it to (food.lastQuantity ?: 1.0) }
        }
        return food.servingSizes.firstOrNull()?.let { it to 1.0 } ?: (options.first { it.label == GRAM_LABEL } to 100.0)
    }

    /** Snapshot entry for [quantity] × [serving]. Nutrients are copied now and never re-read from the food. */
    fun entry(
        food: Food,
        serving: ServingSize,
        quantity: Double,
        date: LocalDate,
        mealSlotId: String,
        now: Instant,
    ): LogEntry {
        val grams = serving.grams * quantity
        return LogEntry(
            id = "",
            date = date,
            mealSlotId = mealSlotId,
            foodId = food.id.takeIf { it.isNotEmpty() },
            displayName = food.name,
            brand = food.brand,
            quantity = quantity,
            servingLabel = serving.label,
            grams = grams,
            nutrients = food.nutrientsPer100g.forGrams(grams),
            source = food.source,
            note = null,
            loggedAt = now,
        )
    }

    /** Quick add: calories (and optionally macros) with a label, no food behind it. */
    fun quickAdd(
        label: String,
        kcal: Double,
        proteinG: Double?,
        carbsG: Double?,
        fatG: Double?,
        date: LocalDate,
        mealSlotId: String,
        now: Instant,
    ): LogEntry = LogEntry(
        id = "",
        date = date,
        mealSlotId = mealSlotId,
        foodId = null,
        displayName = label,
        brand = null,
        quantity = 1.0,
        servingLabel = "serving",
        grams = null,
        nutrients = buildMap {
            put(NutrientKey.ENERGY_KCAL, kcal)
            proteinG?.let { put(NutrientKey.PROTEIN_G, it) }
            carbsG?.let { put(NutrientKey.CARBS_G, it) }
            fatG?.let { put(NutrientKey.FAT_G, it) }
        },
        source = FoodSource.QUICK_ADD,
        note = null,
        loggedAt = now,
    )

    /** Custom-food form values are per serving; storage is per 100 g. */
    fun perServingToPer100g(perServing: Map<NutrientKey, Double>, servingGrams: Double): Map<NutrientKey, Double> =
        perServing.mapValues { (_, v) -> v * 100.0 / servingGrams }
}
