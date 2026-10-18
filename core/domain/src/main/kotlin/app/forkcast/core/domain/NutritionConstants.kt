package app.forkcast.core.domain

/** Every tunable number behind the budget math, in one place (SPEC §5.3–5.7). */
object NutritionConstants {
    /** ≈ 3,500 kcal/lb. A documented approximation, shown in "How we calculated this". */
    const val KCAL_PER_KG = 7700.0

    /** Budgets are rounded to the nearest this many kcal. */
    const val BUDGET_ROUNDING_KCAL = 10

    // Guardrails (warn, never block)
    const val MAX_WEEKLY_LOSS_FRACTION = 0.01
    const val MIN_BUDGET_MALE_KCAL = 1500
    const val MIN_BUDGET_FEMALE_KCAL = 1200

    // Default targets
    const val PROTEIN_G_PER_KG_CURRENT = 1.6
    const val PROTEIN_MAX_G_PER_KG_GOAL = 2.2
    const val FAT_FRACTION_OF_KCAL = 0.30
    const val FIBER_G_PER_1000_KCAL = 14.0
    const val SODIUM_MAX_MG = 2300.0
    const val SAT_FAT_MAX_FRACTION_OF_KCAL = 0.10
    const val ADDED_SUGAR_MAX_FRACTION_OF_KCAL = 0.10

    const val KCAL_PER_G_PROTEIN = 4.0
    const val KCAL_PER_G_CARB = 4.0
    const val KCAL_PER_G_FAT = 9.0

    // Water default target (SPEC §4.9)
    const val WATER_TARGET_MALE_ML = 2500.0
    const val WATER_TARGET_FEMALE_ML = 2000.0

    // Weight trend smoothing (SPEC §5.5)
    const val TREND_SMOOTHING = 0.1
}
