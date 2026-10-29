package app.forkcast.core.domain

import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.kcal
import app.forkcast.core.model.sumNutrients
import java.time.LocalDate

/**
 * The meal report for a health professional (owner, 2026-10-29): a date range of meals with each
 * item's amount, energy, macros and key nutrients, daily totals against the targets, and
 * optionally weight and water. Only what was logged; no photos and nothing about how an AI
 * estimate was made (an AI item is just marked as an estimate). Pure, so the PDF and the
 * spreadsheet are drawn from the same numbers and it's unit-tested.
 */
object MealReport {

    /** The nutrients each item and day shows, in this order (grams, except sodium in mg). */
    val KEY_NUTRIENTS = listOf(
        NutrientKey.PROTEIN_G, NutrientKey.CARBS_G, NutrientKey.FAT_G,
        NutrientKey.FIBER_G, NutrientKey.SUGARS_G, NutrientKey.SAT_FAT_G, NutrientKey.SODIUM_MG,
    )

    data class Item(
        val date: LocalDate,
        /** Minutes after midnight, if the time is known. */
        val minute: Int?,
        val meal: String,
        val name: String,
        /** Amount as logged (quantity and serving label), for the UI edge to word. */
        val quantity: Double,
        val servingLabel: String,
        val grams: Double?,
        val kcal: Double,
        val nutrients: Nutrients,
        /** From a photo or a description: always shown as an estimate. */
        val estimate: Boolean,
    )

    data class Meal(val name: String, val items: List<Item>) {
        val kcal: Double get() = items.sumOf { it.kcal }
    }

    data class Day(
        val date: LocalDate,
        val meals: List<Meal>,
        val kcal: Double,
        val nutrients: Nutrients,
        val targetKcal: Double?,
        val targets: Nutrients,
        val waterMl: Double?,
        val weightKg: Double?,
    )

    data class Report(val from: LocalDate, val to: LocalDate, val days: List<Day>) {
        val items: List<Item> get() = days.flatMap { d -> d.meals.flatMap { it.items } }
        val loggedDays: Int get() = days.count { it.meals.isNotEmpty() }
    }

    /**
     * [targetsFor] gives the day's calorie target and nutrient targets (null when there's no
     * profile); [water] and [weight] are per day and left out when not asked for.
     */
    fun build(
        from: LocalDate,
        to: LocalDate,
        entries: List<LogEntry>,
        slots: List<MealSlot>,
        targetsFor: (LocalDate) -> Pair<Double?, Nutrients>,
        water: Map<LocalDate, Double> = emptyMap(),
        weight: Map<LocalDate, Double> = emptyMap(),
    ): Report {
        val slotOrder = slots.withIndex().associate { (i, s) -> s.id to i }
        val slotName = slots.associate { it.id to it.name }
        val byDay = entries.filter { it.date in from..to }.groupBy { it.date }
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { date ->
            val list = byDay[date].orEmpty()
            val meals = list.groupBy { it.mealSlotId }
                .toSortedMap(compareBy<String> { slotOrder[it] ?: Int.MAX_VALUE }.thenBy { it })
                .map { (slotId, es) ->
                    Meal(
                        slotName[slotId].orEmpty(),
                        es.sortedWith(compareBy<LogEntry> { it.eatenMinute ?: Int.MAX_VALUE }.thenBy { it.loggedAt }).map { e ->
                            Item(
                                date = date,
                                minute = e.eatenMinute,
                                meal = slotName[slotId].orEmpty(),
                                name = e.displayName,
                                quantity = e.quantity,
                                servingLabel = e.servingLabel,
                                grams = e.grams,
                                kcal = e.nutrients.kcal,
                                nutrients = e.nutrients.filterKeys { it in KEY_NUTRIENTS },
                                estimate = e.source == FoodSource.AI || e.source == FoodSource.AI_TEXT,
                            )
                        },
                    )
                }
            val (targetKcal, targets) = targetsFor(date)
            Day(
                date = date,
                meals = meals,
                kcal = list.sumOf { it.nutrients.kcal },
                nutrients = list.map { it.nutrients }.sumNutrients().filterKeys { it in KEY_NUTRIENTS },
                targetKcal = targetKcal,
                targets = targets.filterKeys { it in KEY_NUTRIENTS },
                waterMl = water[date],
                weightKg = weight[date],
            )
        }.toList()
        return Report(from, to, days)
    }
}
