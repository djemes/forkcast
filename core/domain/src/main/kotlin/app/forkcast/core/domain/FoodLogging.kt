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
    const val ML_LABEL = "ml"
    const val FL_OZ_LABEL = "fl oz"

    /**
     * The food's own servings, then grams and ounces (SPEC §4.3). Drinks that count toward water
     * also get ml and fl oz (1 ml ≈ 1 g), so a tea logged "per bag" can be logged by the cup.
     */
    fun servingOptions(food: Food): List<ServingSize> {
        val drink = if (DrinkWater.countsAsWater(food)) {
            listOf(ServingSize(ML_LABEL, 1.0), ServingSize(FL_OZ_LABEL, UnitConversions.flOzToMl(1.0)))
        } else {
            emptyList()
        }
        return (food.servingSizes + drink + ServingSize(GRAM_LABEL, 1.0) + ServingSize(OUNCE_LABEL, UnitConversions.G_PER_OZ))
            .distinctBy { it.label.lowercase() }
    }

    /**
     * Servings an entry can be changed to in the edit sheet. Needs the entry's grams (quick adds
     * have none: only the quantity changes). The food's own servings when it's known, else the
     * entry's serving, g and oz (and ml/fl oz for counted drinks). The current serving is always
     * in the list, at the grams the entry was logged with.
     */
    fun editServings(entry: LogEntry, food: Food?): List<ServingSize> {
        val grams = entry.grams ?: return emptyList()
        if (entry.quantity <= 0) return emptyList()
        val current = ServingSize(entry.servingLabel, grams / entry.quantity)
        val options = food?.let { servingOptions(it) } ?: buildList {
            add(current)
            if (entry.waterMl != null) {
                add(ServingSize(ML_LABEL, 1.0))
                add(ServingSize(FL_OZ_LABEL, UnitConversions.flOzToMl(1.0)))
            }
            add(ServingSize(GRAM_LABEL, 1.0))
            add(ServingSize(OUNCE_LABEL, UnitConversions.G_PER_OZ))
        }
        return (listOf(current) + options).distinctBy { it.label.lowercase() }
    }

    /** The quantity that keeps the same amount when switching from one serving to another. */
    fun convertQuantity(quantity: Double, from: ServingSize, to: ServingSize): Double =
        if (to.grams <= 0) quantity else quantity * from.grams / to.grams

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
            waterMl = DrinkWater.waterMl(DrinkWater.countsAsWater(food), grams),
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
