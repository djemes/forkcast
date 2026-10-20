package app.forkcast.core.domain

import app.forkcast.core.model.Sex

/**
 * The day's water (SPEC §4.9), one rule for Today, the widget and Settings › Water: glasses plus
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
}
