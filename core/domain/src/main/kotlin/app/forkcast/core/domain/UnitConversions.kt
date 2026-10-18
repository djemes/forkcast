package app.forkcast.core.domain

import kotlin.math.floor
import kotlin.math.roundToInt

/** Exact conversion factors. Storage is always kg, cm, kcal, ml, g, mg. */
object UnitConversions {
    const val KG_PER_LB = 0.45359237
    const val CM_PER_INCH = 2.54
    const val KJ_PER_KCAL = 4.184
    const val ML_PER_FL_OZ = 29.5735295625
    const val G_PER_OZ = 28.349523125

    fun lbToKg(lb: Double) = lb * KG_PER_LB
    fun kgToLb(kg: Double) = kg / KG_PER_LB
    fun inchesToCm(inches: Double) = inches * CM_PER_INCH
    fun cmToInches(cm: Double) = cm / CM_PER_INCH
    fun kcalToKj(kcal: Double) = kcal * KJ_PER_KCAL
    fun kjToKcal(kj: Double) = kj / KJ_PER_KCAL
    fun flOzToMl(oz: Double) = oz * ML_PER_FL_OZ
    fun mlToFlOz(ml: Double) = ml / ML_PER_FL_OZ
    fun ozToG(oz: Double) = oz * G_PER_OZ
    fun gToOz(g: Double) = g / G_PER_OZ

    fun feetInchesToCm(feet: Int, inches: Double) = inchesToCm(feet * 12 + inches)

    /** Splits a height into whole feet and inches rounded to the nearest inch (5 ft 11.6 in → 6 ft 0 in). */
    fun cmToFeetInches(cm: Double): Pair<Int, Int> {
        val totalInches = cmToInches(cm).roundToInt()
        return totalInches / 12 to totalInches % 12
    }

    /** Whole-number feet part without rounding, for editors that keep decimal inches. */
    fun cmToFeetAndDecimalInches(cm: Double): Pair<Int, Double> {
        val total = cmToInches(cm)
        val feet = floor(total / 12).toInt()
        return feet to total - feet * 12
    }
}
