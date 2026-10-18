package app.forkcast.core.model

/**
 * Nutrients Forkcast tracks (SPEC §6.2). Stored by enum name in snapshots and exports,
 * so never rename an entry; add new ones at the end.
 */
enum class NutrientKey(val unit: NutrientUnit) {
    ENERGY_KCAL(NutrientUnit.KCAL),
    PROTEIN_G(NutrientUnit.G),
    CARBS_G(NutrientUnit.G),
    FAT_G(NutrientUnit.G),
    SAT_FAT_G(NutrientUnit.G),
    TRANS_FAT_G(NutrientUnit.G),
    MONO_FAT_G(NutrientUnit.G),
    POLY_FAT_G(NutrientUnit.G),
    CHOLESTEROL_MG(NutrientUnit.MG),
    FIBER_G(NutrientUnit.G),
    SUGARS_G(NutrientUnit.G),
    ADDED_SUGARS_G(NutrientUnit.G),
    SODIUM_MG(NutrientUnit.MG),
    POTASSIUM_MG(NutrientUnit.MG),
    CALCIUM_MG(NutrientUnit.MG),
    IRON_MG(NutrientUnit.MG),
    MAGNESIUM_MG(NutrientUnit.MG),
    VITAMIN_A_UG(NutrientUnit.UG),
    VITAMIN_C_MG(NutrientUnit.MG),
    VITAMIN_D_UG(NutrientUnit.UG),
    VITAMIN_B12_UG(NutrientUnit.UG),
    CAFFEINE_MG(NutrientUnit.MG),
    ALCOHOL_G(NutrientUnit.G),
    WATER_G(NutrientUnit.G),
}

enum class NutrientUnit { KCAL, G, MG, UG }
