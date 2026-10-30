package app.forkcast.core.domain

import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.kcal
import app.forkcast.core.model.sumNutrients
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

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
     * A week's or a month's averages: intake over the days something was logged (an empty day
     * isn't a day of eating nothing), water over the days with water, weight over the weigh-ins.
     */
    data class Period(
        val start: LocalDate,
        val end: LocalDate,
        val loggedDays: Int,
        val kcal: Double?,
        val targetKcal: Double?,
        val nutrients: Nutrients,
        val waterMl: Double?,
        val weightKg: Double?,
    )

    /** Ranges longer than this get the summary PDF unless every meal is asked for (owner, 2026-10-29). */
    const val SUMMARY_AFTER_DAYS = 60

    fun summaryByDefault(from: LocalDate, to: LocalDate): Boolean = ChronoUnit.DAYS.between(from, to) + 1 > SUMMARY_AFTER_DAYS

    /** Weeks starting on [firstDay] (the locale's), the first and last cut to the report's range. */
    fun weeks(report: Report, firstDay: DayOfWeek): List<Period> =
        periods(report) { it.with(TemporalAdjusters.previousOrSame(firstDay)) }

    fun months(report: Report): List<Period> = periods(report) { it.withDayOfMonth(1) }

    private fun periods(report: Report, startOf: (LocalDate) -> LocalDate): List<Period> =
        report.days.groupBy { startOf(it.date) }.toSortedMap().map { (_, days) -> period(days) }

    private fun period(days: List<Day>): Period {
        val logged = days.filter { it.meals.isNotEmpty() }
        val keys = KEY_NUTRIENTS.filter { k -> logged.any { k in it.nutrients } }
        return Period(
            start = days.first().date,
            end = days.last().date,
            loggedDays = logged.size,
            kcal = logged.takeIf { it.isNotEmpty() }?.map { it.kcal }?.average(),
            targetKcal = days.mapNotNull { it.targetKcal }.takeIf { it.isNotEmpty() }?.average(),
            nutrients = keys.associateWith { k -> logged.sumOf { it.nutrients[k] ?: 0.0 } / logged.size },
            waterMl = days.mapNotNull { it.waterMl }.takeIf { it.isNotEmpty() }?.average(),
            weightKg = days.mapNotNull { it.weightKg }.takeIf { it.isNotEmpty() }?.average(),
        )
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
