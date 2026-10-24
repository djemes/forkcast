package app.forkcast.core.domain

import app.forkcast.core.model.Sex
import app.forkcast.core.model.WaterContainer
import app.forkcast.core.model.WaterContainers

/**
 * The day's water (SPEC §4.9), one rule for Today, the shortcut and Settings › Water: glasses plus
 * drinks that count toward water, a goal by sex unless the user set one, and a glass of 8 fl oz
 * (US units) or 250 ml unless the user set one.
 */
object WaterMath {
    const val GLASS_METRIC_ML = 250.0
    const val GLASS_US_FL_OZ = 8.0

    /** Water logged as water plus the water in drinks that count (each entry's snapshot). */
    fun dayTotalMl(waterMl: List<Double>, drinkMl: List<Double?>): Double = waterMl.sum() + drinkMl.sumOf { it ?: 0.0 }

    fun defaultTargetMl(sex: Sex?): Double =
        if (sex == Sex.FEMALE) NutritionConstants.WATER_TARGET_FEMALE_ML else NutritionConstants.WATER_TARGET_MALE_ML

    fun targetMl(userTargetMl: Double?, sex: Sex?): Double = userTargetMl ?: defaultTargetMl(sex)

    fun defaultGlassMl(flOz: Boolean): Double = if (flOz) UnitConversions.flOzToMl(GLASS_US_FL_OZ) else GLASS_METRIC_ML

    fun glassMl(userGlassMl: Double?, flOz: Boolean): Double = userGlassMl ?: defaultGlassMl(flOz)

    /**
     * Default container sizes (owner, 2026-10-23: a standard 12 fl oz / 355 ml can). Bottle: the
     * common 16.9 fl oz / 500 ml; tumbler: a 30 fl oz Yeti-style tumbler, 900 ml in metric.
     */
    fun defaultMl(container: WaterContainer, flOz: Boolean): Double = when (container) {
        WaterContainer.GLASS -> defaultGlassMl(flOz)
        WaterContainer.CAN -> 355.0
        WaterContainer.BOTTLE -> 500.0
        WaterContainer.TUMBLER -> if (flOz) UnitConversions.flOzToMl(30.0) else 900.0
    }

    /** The size of [container] for these settings. */
    fun containerMl(container: WaterContainer, glassMl: Double?, sizes: WaterContainers, flOz: Boolean): Double = when (container) {
        WaterContainer.GLASS -> glassMl
        WaterContainer.CAN -> sizes.canMl
        WaterContainer.BOTTLE -> sizes.bottleMl
        WaterContainer.TUMBLER -> sizes.tumblerMl
    } ?: defaultMl(container, flOz)

    /**
     * "Edit the day's total" (long-press on Today's water total): which water entries to remove,
     * newest first, and how much to add, so the day's total becomes [targetMl]. Drinks logged as
     * food can't be changed here, so the total can't go below them.
     */
    fun adjustTo(targetMl: Double, entries: List<Pair<String, Double>>, drinksMl: Double): Adjustment {
        val wantWater = (targetMl - drinksMl).coerceAtLeast(0.0)
        var water = entries.sumOf { it.second }
        val remove = mutableListOf<String>()
        for ((id, ml) in entries.asReversed()) {
            if (water <= wantWater) break
            remove += id
            water -= ml
        }
        return Adjustment(remove, (wantWater - water).coerceAtLeast(0.0))
    }

    /** [removeIds] newest first; then [addMl] more (0 = nothing). */
    data class Adjustment(val removeIds: List<String>, val addMl: Double)
}
