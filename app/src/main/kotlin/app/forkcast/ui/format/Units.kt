package app.forkcast.ui.format

import app.forkcast.core.domain.UnitConversions
import app.forkcast.core.model.EnergyUnit
import app.forkcast.core.model.HeightUnit
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.WaterUnit
import app.forkcast.core.model.WeightUnit
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Converts stored values (kg, cm, kcal, ml) to display units and back. The only place
 * in the app that knows about lb, ft-in, kJ and fl oz (CLAUDE.md: convert at the UI edge).
 */
class UnitFormatter(val prefs: UnitPrefs, private val locale: Locale = Locale.getDefault()) {

    private fun format(value: Double, decimals: Int): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = decimals
            isGroupingUsed = true
        }.format(value)

    // ---- weight
    val weightUnitLabel: String get() = if (prefs.weight == WeightUnit.LB) "lb" else "kg"
    fun weightValue(kg: Double): Double = if (prefs.weight == WeightUnit.LB) UnitConversions.kgToLb(kg) else kg
    fun weightToKg(value: Double): Double = if (prefs.weight == WeightUnit.LB) UnitConversions.lbToKg(value) else value
    fun weightNumber(kg: Double): String = format(weightValue(kg), 1)
    fun weight(kg: Double): String = "${weightNumber(kg)} $weightUnitLabel"
    /** For text fields: no grouping, one decimal. */
    fun weightInput(kg: Double): String = trimNumber(weightValue(kg), 1)

    // ---- height
    fun height(cm: Double): String = when (prefs.height) {
        HeightUnit.CM -> "${format(cm, 0)} cm"
        HeightUnit.FT_IN -> UnitConversions.cmToFeetInches(cm).let { (ft, inch) -> "$ft′ $inch″" }
    }

    // ---- energy
    val energyUnitLabel: String get() = if (prefs.energy == EnergyUnit.KJ) "kJ" else "kcal"
    fun energyValue(kcal: Double): Double = if (prefs.energy == EnergyUnit.KJ) UnitConversions.kcalToKj(kcal) else kcal
    fun energyToKcal(value: Double): Double = if (prefs.energy == EnergyUnit.KJ) UnitConversions.kjToKcal(value) else value
    fun energyNumber(kcal: Double): String = format(energyValue(kcal).roundToInt().toDouble(), 0)
    fun energy(kcal: Double): String = "${energyNumber(kcal)} $energyUnitLabel"

    // ---- water
    val waterUnitLabel: String get() = if (prefs.water == WaterUnit.FL_OZ) "fl oz" else "ml"
    fun waterValue(ml: Double): Double = if (prefs.water == WaterUnit.FL_OZ) UnitConversions.mlToFlOz(ml) else ml
    fun waterToMl(value: Double): Double = if (prefs.water == WaterUnit.FL_OZ) UnitConversions.flOzToMl(value) else value
    fun waterLiters(ml: Double): String = when (prefs.water) {
        WaterUnit.ML -> "${format(ml / 1000.0, 1)} L"
        WaterUnit.FL_OZ -> "${format(UnitConversions.mlToFlOz(ml), 0)} fl oz"
    }

    // ---- grams
    fun grams(g: Double): String = "${format(g, if (g < 10) 1 else 0)} g"

    fun number(value: Double, decimals: Int = 0): String = format(value, decimals)

    /** Parses user input using either "." or the locale's decimal separator. Null if not a number. */
    fun parse(text: String): Double? = parseNumber(text, locale)

    companion object {
        /** US customary for the US, Liberia and Myanmar; metric elsewhere. */
        fun defaultsFor(locale: Locale): UnitPrefs = when (locale.country.uppercase(Locale.ROOT)) {
            "US", "LR", "MM" -> UnitPrefs.US
            else -> UnitPrefs.METRIC
        }

        fun trimNumber(value: Double, decimals: Int): String {
            val factor = Math.pow(10.0, decimals.toDouble())
            val rounded = (value * factor).roundToInt() / factor
            return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
        }

        fun parseNumber(text: String, locale: Locale = Locale.getDefault()): Double? {
            val t = text.trim()
            if (t.isEmpty()) return null
            t.replace(',', '.').toDoubleOrNull()?.let { return it }
            val pos = ParsePosition(0)
            val n = NumberFormat.getNumberInstance(locale).parse(t, pos)
            return if (n != null && pos.index == t.length) n.toDouble() else null
        }
    }
}
