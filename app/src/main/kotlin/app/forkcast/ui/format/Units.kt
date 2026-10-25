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
class UnitFormatter(
    val prefs: UnitPrefs,
    private val locale: Locale = Locale.getDefault(),
    private val labels: UnitLabels = UnitLabels.ENGLISH,
) {

    /** "72.5 kg": the number and the unit as the language writes them. */
    private fun withUnit(number: String, unit: String): String = labels.quantity.format(locale, number, unit)


    private fun format(value: Double, decimals: Int): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = decimals
            isGroupingUsed = true
        }.format(value)

    // ---- weight
    val weightUnitLabel: String get() = if (prefs.weight == WeightUnit.LB) labels.lb else labels.kg
    fun weightValue(kg: Double): Double = if (prefs.weight == WeightUnit.LB) UnitConversions.kgToLb(kg) else kg
    fun weightToKg(value: Double): Double = if (prefs.weight == WeightUnit.LB) UnitConversions.lbToKg(value) else value
    fun weightNumber(kg: Double): String = format(weightValue(kg), 1)
    fun weight(kg: Double): String = withUnit(weightNumber(kg), weightUnitLabel)
    /** For text fields: no grouping, one decimal. */
    fun weightInput(kg: Double): String = trimNumber(weightValue(kg), 1)

    // ---- height
    fun height(cm: Double): String = when (prefs.height) {
        HeightUnit.CM -> withUnit(format(cm, 0), labels.cm)
        HeightUnit.FT_IN -> UnitConversions.cmToFeetInches(cm).let { (ft, inch) -> "$ft′ $inch″" }
    }

    // ---- energy
    val energyUnitLabel: String get() = if (prefs.energy == EnergyUnit.KJ) labels.kj else labels.kcal
    fun energyValue(kcal: Double): Double = if (prefs.energy == EnergyUnit.KJ) UnitConversions.kcalToKj(kcal) else kcal
    fun energyToKcal(value: Double): Double = if (prefs.energy == EnergyUnit.KJ) UnitConversions.kjToKcal(value) else value
    fun energyNumber(kcal: Double): String = format(energyValue(kcal).roundToInt().toDouble(), 0)
    fun energy(kcal: Double): String = withUnit(energyNumber(kcal), energyUnitLabel)

    // ---- water
    val waterUnitLabel: String get() = if (prefs.water == WaterUnit.FL_OZ) labels.flOz else labels.ml
    fun waterValue(ml: Double): Double = if (prefs.water == WaterUnit.FL_OZ) UnitConversions.mlToFlOz(ml) else ml
    fun waterToMl(value: Double): Double = if (prefs.water == WaterUnit.FL_OZ) UnitConversions.flOzToMl(value) else value
    fun waterLiters(ml: Double): String = when (prefs.water) {
        WaterUnit.ML -> withUnit(format(ml / 1000.0, 1), labels.liter)
        WaterUnit.FL_OZ -> withUnit(format(UnitConversions.mlToFlOz(ml), 0), labels.flOz)
    }
    /** One glass or drink: whole ml (250 ml, not "0.3 L"), or fl oz. */
    fun waterServing(ml: Double): String = when (prefs.water) {
        WaterUnit.ML -> withUnit(format(ml, 0), labels.ml)
        WaterUnit.FL_OZ -> withUnit(format(UnitConversions.mlToFlOz(ml), 0), labels.flOz)
    }

    // ---- grams
    fun grams(g: Double): String = withUnit(format(g, if (g < 10) 1 else 0), labels.g)

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

/** Unit symbols and how a quantity is written, from string resources (`unit_*`); English for tests and previews. */
data class UnitLabels(
    val kg: String,
    val lb: String,
    val cm: String,
    val kcal: String,
    val kj: String,
    val ml: String,
    val liter: String,
    val flOz: String,
    val g: String,
    /** "%1$s\u2009%2$s": number, then unit, with a thin space. */
    val quantity: String,
) {
    companion object {
        val ENGLISH = UnitLabels("kg", "lb", "cm", "kcal", "kJ", "ml", "L", "fl oz", "g", "%1\$s\u2009%2\$s")

        fun from(res: android.content.res.Resources) = UnitLabels(
            kg = res.getString(app.forkcast.R.string.unit_kg),
            lb = res.getString(app.forkcast.R.string.unit_lb),
            cm = res.getString(app.forkcast.R.string.unit_cm),
            kcal = res.getString(app.forkcast.R.string.unit_kcal),
            kj = res.getString(app.forkcast.R.string.unit_kj),
            ml = res.getString(app.forkcast.R.string.unit_ml),
            liter = res.getString(app.forkcast.R.string.unit_l),
            flOz = res.getString(app.forkcast.R.string.unit_fl_oz),
            g = res.getString(app.forkcast.R.string.unit_g),
            quantity = res.getString(app.forkcast.R.string.unit_quantity),
        )
    }
}

/** The unit labels of the current language, for a [UnitFormatter] in a composable. */
@androidx.compose.runtime.Composable
fun unitLabels(): UnitLabels {
    val res = androidx.compose.ui.platform.LocalResources.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    return androidx.compose.runtime.remember(res, config) { UnitLabels.from(res) }
}
