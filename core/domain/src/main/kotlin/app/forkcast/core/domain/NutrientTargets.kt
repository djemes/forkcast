package app.forkcast.core.domain

import app.forkcast.core.domain.NutritionConstants.ADDED_SUGAR_MAX_FRACTION_OF_KCAL
import app.forkcast.core.domain.NutritionConstants.FAT_FRACTION_OF_KCAL
import app.forkcast.core.domain.NutritionConstants.FIBER_G_PER_1000_KCAL
import app.forkcast.core.domain.NutritionConstants.KCAL_PER_G_CARB
import app.forkcast.core.domain.NutritionConstants.KCAL_PER_G_FAT
import app.forkcast.core.domain.NutritionConstants.KCAL_PER_G_PROTEIN
import app.forkcast.core.domain.NutritionConstants.PROTEIN_G_PER_KG_CURRENT
import app.forkcast.core.domain.NutritionConstants.PROTEIN_MAX_G_PER_KG_GOAL
import app.forkcast.core.domain.NutritionConstants.SAT_FAT_MAX_FRACTION_OF_KCAL
import app.forkcast.core.domain.NutritionConstants.SODIUM_MAX_MG
import app.forkcast.core.model.NutrientKey

/** Whether a target is something to reach or a ceiling to stay under. */
enum class TargetKind { GOAL, LIMIT }

data class NutrientTarget(val key: NutrientKey, val amount: Double, val kind: TargetKind)

/** Default daily targets from the budget (SPEC §5.7). Overrides are applied on top by the caller. */
object NutrientTargets {

    fun defaults(budgetKcal: Int, currentKg: Double, goalKg: Double): Map<NutrientKey, NutrientTarget> {
        val kcal = budgetKcal.toDouble()
        val protein = minOf(PROTEIN_G_PER_KG_CURRENT * currentKg, PROTEIN_MAX_G_PER_KG_GOAL * goalKg)
        val fat = kcal * FAT_FRACTION_OF_KCAL / KCAL_PER_G_FAT
        val carbs = ((kcal - protein * KCAL_PER_G_PROTEIN - fat * KCAL_PER_G_FAT) / KCAL_PER_G_CARB).coerceAtLeast(0.0)
        return listOf(
            NutrientTarget(NutrientKey.ENERGY_KCAL, kcal, TargetKind.LIMIT),
            NutrientTarget(NutrientKey.PROTEIN_G, protein, TargetKind.GOAL),
            NutrientTarget(NutrientKey.FAT_G, fat, TargetKind.GOAL),
            NutrientTarget(NutrientKey.CARBS_G, carbs, TargetKind.GOAL),
            NutrientTarget(NutrientKey.FIBER_G, kcal / 1000.0 * FIBER_G_PER_1000_KCAL, TargetKind.GOAL),
            NutrientTarget(NutrientKey.SODIUM_MG, SODIUM_MAX_MG, TargetKind.LIMIT),
            NutrientTarget(NutrientKey.SAT_FAT_G, kcal * SAT_FAT_MAX_FRACTION_OF_KCAL / KCAL_PER_G_FAT, TargetKind.LIMIT),
            NutrientTarget(NutrientKey.ADDED_SUGARS_G, kcal * ADDED_SUGAR_MAX_FRACTION_OF_KCAL / KCAL_PER_G_CARB, TargetKind.LIMIT),
        ).plus(DailyValues.micronutrients)
            .associateBy { it.key }
    }

    /** Applies user overrides (nutrient → amount) on top of [defaults]; the kind stays the default's. */
    fun withOverrides(
        defaults: Map<NutrientKey, NutrientTarget>,
        overrides: Map<NutrientKey, Double>,
    ): Map<NutrientKey, NutrientTarget> = defaults + overrides.map { (key, amount) ->
        key to NutrientTarget(key, amount, defaults[key]?.kind ?: TargetKind.GOAL)
    }
}

/** US Daily Values for adults (FDA 2016 labeling rule), used as micronutrient targets. */
object DailyValues {
    val micronutrients = listOf(
        NutrientTarget(NutrientKey.CHOLESTEROL_MG, 300.0, TargetKind.LIMIT),
        NutrientTarget(NutrientKey.POTASSIUM_MG, 4700.0, TargetKind.GOAL),
        NutrientTarget(NutrientKey.CALCIUM_MG, 1300.0, TargetKind.GOAL),
        NutrientTarget(NutrientKey.IRON_MG, 18.0, TargetKind.GOAL),
        NutrientTarget(NutrientKey.MAGNESIUM_MG, 420.0, TargetKind.GOAL),
        NutrientTarget(NutrientKey.VITAMIN_A_UG, 900.0, TargetKind.GOAL),
        NutrientTarget(NutrientKey.VITAMIN_C_MG, 90.0, TargetKind.GOAL),
        NutrientTarget(NutrientKey.VITAMIN_D_UG, 20.0, TargetKind.GOAL),
        NutrientTarget(NutrientKey.VITAMIN_B12_UG, 2.4, TargetKind.GOAL),
    )
}
