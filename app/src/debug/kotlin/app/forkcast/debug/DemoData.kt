package app.forkcast.debug

import app.forkcast.core.data.backup.ForkcastExport
import app.forkcast.core.data.db.FoodEntity
import app.forkcast.core.data.db.LogEntryEntity
import app.forkcast.core.data.db.WaterEntryEntity
import app.forkcast.core.data.db.WeightEntryEntity
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.ServingSize
import app.forkcast.core.model.WeightSource
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * About two months of realistic meals, weigh-ins and water for the emulator (roadmap 2.0 task
 * 3.7, debug builds only), the shape tools/seed/make_seed_backup.py writes. It goes in through
 * the backup import's Merge, into this phone's own meals; ids are fixed per day, so loading it
 * twice changes nothing. Deterministic: the same day gives the same data.
 */
object DemoData {

    const val DAYS = 60

    /** A food per 100 g and the serving it's usually logged in. */
    private data class DemoFood(
        val name: String,
        val brand: String?,
        val kcal: Double,
        val protein: Double,
        val carbs: Double,
        val fat: Double,
        val grams: Double,
        val serving: String,
    )

    private val FOODS = listOf(
        DemoFood("Rolled oats", null, 379.0, 13.2, 67.7, 6.5, 40.0, "½ cup"),
        DemoFood("Banana", null, 89.0, 1.1, 22.8, 0.3, 118.0, "1 medium"),
        DemoFood("Greek yogurt, plain", "Fage", 73.0, 10.0, 3.9, 1.9, 170.0, "1 container"),
        DemoFood("Egg, whole", null, 143.0, 12.6, 0.7, 9.5, 50.0, "1 large"),
        DemoFood("Whole wheat bread", null, 247.0, 13.0, 41.0, 3.4, 45.0, "1 slice"),
        DemoFood("Chicken breast, grilled", null, 165.0, 31.0, 0.0, 3.6, 120.0, "1 piece"),
        DemoFood("Brown rice, cooked", null, 123.0, 2.7, 25.6, 1.0, 195.0, "1 cup"),
        DemoFood("Broccoli, steamed", null, 35.0, 2.4, 7.2, 0.4, 90.0, "1 cup"),
        DemoFood("Salmon, baked", null, 206.0, 22.1, 0.0, 12.4, 140.0, "1 fillet"),
        DemoFood("Pasta, cooked", null, 158.0, 5.8, 30.9, 0.9, 140.0, "1 cup"),
        DemoFood("Marinara sauce", null, 70.0, 1.5, 6.0, 4.5, 125.0, "½ cup"),
        DemoFood("Black beans, canned", null, 91.0, 6.0, 16.6, 0.3, 130.0, "½ cup"),
        DemoFood("Avocado", null, 160.0, 2.0, 8.5, 14.7, 68.0, "½ avocado"),
        DemoFood("Apple", null, 52.0, 0.3, 13.8, 0.2, 182.0, "1 medium"),
        DemoFood("Almonds", null, 579.0, 21.2, 21.6, 49.9, 28.0, "1 oz"),
        DemoFood("Coffee with milk", null, 20.0, 1.0, 1.6, 0.8, 240.0, "1 cup"),
        DemoFood("Pizza, pepperoni", null, 270.0, 11.5, 30.0, 11.5, 110.0, "1 slice"),
        DemoFood("Dark chocolate 70%", null, 598.0, 7.8, 45.9, 42.6, 20.0, "2 squares"),
    )

    /** Food indexes per meal: each day picks one of these plates, with a varied amount. */
    private val BREAKFASTS = listOf(listOf(0, 1, 15), listOf(2, 1, 15), listOf(3, 3, 4, 15))
    private val LUNCHES = listOf(listOf(5, 6, 7), listOf(11, 6, 12), listOf(4, 4, 3, 13))
    private val DINNERS = listOf(listOf(8, 6, 7), listOf(9, 10, 5), listOf(16, 16), listOf(5, 11, 12, 6))
    private val SNACKS = listOf(listOf(13), listOf(14), listOf(17), listOf(13, 14))

    /**
     * [slots] are this phone's meals in order (the first three as breakfast, lunch and dinner, the
     * last as snacks); [endKg] is where the weight trend ends today, about 2 kg below where it starts.
     */
    fun build(today: LocalDate, zone: ZoneId, slots: List<MealSlot>, endKg: Double, nowMs: Long): ForkcastExport {
        val days = Days(zone, Random(today.toEpochDay()), slots.sortedBy { it.sortOrder })
        val dates = (DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
        return ForkcastExport(
            appVersion = "demo",
            exportedAt = java.time.Instant.ofEpochMilli(nowMs).toString(),
            timeZone = zone.id,
            foods = FOODS.mapIndexed { i, f -> food(i, f, nowMs) },
            // Today stays half done, like a real morning.
            logEntries = dates.flatMap { days.entries(it, partial = it == today) },
            weights = dates.mapIndexedNotNull { i, d -> days.weighIn(d, endKg + 2.0 * (DAYS - 1 - i) / (DAYS - 1)) },
            water = dates.flatMap { days.water(it, partial = it == today) },
        )
    }

    /** Builds each day from one seeded [rnd], in date order, so the same day always gives the same data. */
    private class Days(private val zone: ZoneId, private val rnd: Random, private val meals: List<MealSlot>) {

        fun entries(day: LocalDate, partial: Boolean): List<LogEntryEntity> {
            val plates = if (partial) listOf(BREAKFASTS) else listOf(BREAKFASTS, LUNCHES, DINNERS, SNACKS)
            return plates.flatMapIndexed { m, choices ->
                val slot = meals.getOrNull(if (m == 3) meals.lastIndex else m) ?: return@flatMapIndexed emptyList()
                // Some evenings have no snack, and about one day in twelve is only partly logged.
                val skip = (m == 3 && rnd.nextInt(3) == 0) || (m == 2 && rnd.nextInt(12) == 0)
                if (skip) emptyList() else choices[rnd.nextInt(choices.size)].mapIndexed { k, f -> entry(day, slot.id, f, m, k) }
            }
        }

        /** Four days in five, at 7:00, around the trend at [kg]. */
        fun weighIn(day: LocalDate, kg: Double): WeightEntryEntity? {
            if (rnd.nextInt(5) == 0) return null
            val at = day.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
            val reading = Math.round((kg + rnd.nextDouble(-0.4, 0.4)) * 10) / 10.0
            return WeightEntryEntity("demo-weight-$day", at, reading, null, WeightSource.MANUAL, null, at, at)
        }

        fun water(day: LocalDate, partial: Boolean): List<WaterEntryEntity> = List(if (partial) 2 else 4 + rnd.nextInt(4)) { n ->
            val at = day.atTime(8 + n * 2, rnd.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
            WaterEntryEntity("demo-water-$day-$n", at, day, zone.id, listOf(250.0, 330.0, 500.0)[rnd.nextInt(3)], at, at)
        }

        /** Food [f] as item [k] of meal [m], eaten around the meal's usual time. */
        private fun entry(day: LocalDate, slotId: String, f: Int, m: Int, k: Int): LogEntryEntity {
            val food = FOODS[f]
            // Bigger than one serving most of the time, so the days add up to an adult's intake.
            val quantity = listOf(1.0, 1.25, 1.5, 1.5, 2.0)[rnd.nextInt(5)]
            val grams = food.grams * quantity
            val minute = MEAL_MINUTES[m] + rnd.nextInt(-30, 31)
            val at = day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
            return LogEntryEntity(
                id = "demo-$day-$m-$k",
                date = day,
                zoneId = zone.id,
                mealSlotId = slotId,
                foodId = "demo-food-$f",
                displayName = food.name,
                brand = food.brand,
                quantity = quantity,
                servingLabel = food.serving,
                grams = grams,
                nutrients = per100g(food).mapValues { (_, v) -> v * grams / 100.0 },
                source = FoodSource.CUSTOM,
                aiEstimateId = null,
                healthConnectRecordId = null,
                note = null,
                loggedAt = at,
                createdAt = at,
                updatedAt = at,
                eatenMinute = minute,
            )
        }
    }

    private fun food(i: Int, f: DemoFood, nowMs: Long) = FoodEntity(
        id = "demo-food-$i",
        name = f.name,
        brand = f.brand,
        source = FoodSource.CUSTOM,
        sourceId = null,
        barcode = null,
        servingSizes = listOf(ServingSize(f.serving, f.grams)),
        nutrientsPer100g = per100g(f),
        isFavorite = i % 6 == 0,
        lastUsedAt = null,
        lastQuantity = null,
        lastServingLabel = null,
        createdAt = nowMs,
        updatedAt = nowMs,
    )

    private fun per100g(f: DemoFood) = mapOf(
        NutrientKey.ENERGY_KCAL to f.kcal,
        NutrientKey.PROTEIN_G to f.protein,
        NutrientKey.CARBS_G to f.carbs,
        NutrientKey.FAT_G to f.fat,
    )

    /** Usual minute of breakfast, lunch, dinner and the snack. */
    private val MEAL_MINUTES = listOf(7 * 60 + 30, 12 * 60 + 30, 19 * 60, 16 * 60)
}
