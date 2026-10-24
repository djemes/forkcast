package app.forkcast.core.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Meal timing in Trends (owner, 2026-10-23: "you decide, from the perspective of a calorie
 * tracker for losing weight; simple and useful"). Three things, from complete past days whose
 * items all have a time:
 *
 * - when each meal usually happens, and how much that varies (median and the median distance from it);
 * - the usual eating window (first and last bite);
 * - how much is eaten late in the evening, and whether that differs on days over the budget,
 *   since late eating is where many over-budget days come from.
 */
object MealTiming {
    /** "Late": from 8 pm. */
    const val LATE_MINUTE = 20 * 60
    /** Fewer days than this say too little. */
    const val MIN_DAYS = 3
    /** Each side of the over-budget comparison needs this many days. */
    const val MIN_GROUP_DAYS = 2

    /** One logged item with its time. */
    data class Item(val date: LocalDate, val mealSlotId: String, val minute: Int?, val kcal: Double)

    data class MealTime(val mealSlotId: String, val medianMinute: Int, val spreadMinutes: Int, val days: Int)

    data class LateComparison(val overDays: Int, val lateKcalOver: Double, val otherDays: Int, val lateKcalOther: Double)

    data class Insights(
        /** Days the numbers rest on. */
        val days: Int,
        /** Breakfast, Lunch, Dinner (meals with a window), in the order given. */
        val meals: List<MealTime>,
        val windowStartMinute: Int,
        val windowEndMinute: Int,
        val lateKcalPerDay: Double,
        /** Late kcal over all kcal, 0..1. */
        val lateShare: Double,
        val comparison: LateComparison?,
    )

    /**
     * [days]: the days to use (complete past days) with their allowance; [mealSlotIds]: meals that
     * show one time (Breakfast, Lunch, Dinner), in display order. Null under [MIN_DAYS] usable days.
     */
    fun insights(items: List<Item>, days: Map<LocalDate, Double>, mealSlotIds: List<String>): Insights? {
        val byDay = items.filter { it.date in days }.groupBy { it.date }
            // A day counts only if every item on it has a time.
            .filterValues { list -> list.isNotEmpty() && list.all { it.minute != null } }
        if (byDay.size < MIN_DAYS) return null

        val meals = mealSlotIds.mapNotNull { slot ->
            val perDay = byDay.values.mapNotNull { list -> list.filter { it.mealSlotId == slot }.minOfOrNull { it.minute!! } }
            if (perDay.size < MIN_DAYS) return@mapNotNull null
            val median = median(perDay)
            MealTime(slot, median, median(perDay.map { abs(it - median) }), perDay.size)
        }

        val late = byDay.mapValues { (_, list) -> list.filter { it.minute!! >= LATE_MINUTE }.sumOf { it.kcal } }
        val total = byDay.values.sumOf { list -> list.sumOf { it.kcal } }
        val over = byDay.keys.filter { d -> byDay.getValue(d).sumOf { it.kcal } > days.getValue(d) }
        val other = byDay.keys - over.toSet()
        val comparison = if (over.size >= MIN_GROUP_DAYS && other.size >= MIN_GROUP_DAYS) {
            LateComparison(over.size, over.map { late.getValue(it) }.average(), other.size, other.map { late.getValue(it) }.average())
        } else {
            null
        }
        return Insights(
            days = byDay.size,
            meals = meals,
            windowStartMinute = median(byDay.values.map { list -> list.minOf { it.minute!! } }),
            windowEndMinute = median(byDay.values.map { list -> list.maxOf { it.minute!! } }),
            lateKcalPerDay = late.values.average(),
            lateShare = if (total > 0) late.values.sum() / total else 0.0,
            comparison = comparison,
        )
    }

    private fun median(values: List<Int>): Int {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else ((s[mid - 1] + s[mid]) / 2.0).roundToInt()
    }
}
