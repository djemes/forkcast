package app.forkcast.core.model

import kotlinx.serialization.Serializable

/** Sex used only for the BMR equation and guardrail thresholds (SPEC §5.1, §5.4). */
@Serializable
enum class Sex { MALE, FEMALE }

/** Activity levels and their TDEE multipliers (SPEC §5.2). */
@Serializable
enum class ActivityLevel(val factor: Double) {
    SEDENTARY(1.2),
    LIGHT(1.375),
    MODERATE(1.55),
    VERY_ACTIVE(1.725),
    EXTRA_ACTIVE(1.9),
}

@Serializable
enum class GoalType { LOSE, MAINTAIN, GAIN }

/**
 * Phase 3 exercise modes, kept only because profile rows and exports carry them. Unused since
 * 2026-10-19: AppSettings.activityBonus replaced them (SPEC §5.2).
 */
@Serializable
enum class ExerciseMode { INCLUDED, ADDED_BACK }

@Serializable
enum class WeightUnit { KG, LB }

@Serializable
enum class HeightUnit { CM, FT_IN }

@Serializable
enum class EnergyUnit { KCAL, KJ }

@Serializable
enum class WaterUnit { ML, FL_OZ }

/** Display units. Everything is stored in kg, cm, kcal, ml; conversion happens at the UI edge. */
@Serializable
data class UnitPrefs(
    val weight: WeightUnit = WeightUnit.LB,
    val height: HeightUnit = HeightUnit.FT_IN,
    val energy: EnergyUnit = EnergyUnit.KCAL,
    val water: WaterUnit = WaterUnit.FL_OZ,
) {
    companion object {
        val METRIC = UnitPrefs(WeightUnit.KG, HeightUnit.CM, EnergyUnit.KCAL, WaterUnit.ML)
        val US = UnitPrefs()
    }
}
