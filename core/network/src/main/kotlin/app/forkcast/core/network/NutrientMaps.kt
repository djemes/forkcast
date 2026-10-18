package app.forkcast.core.network

import app.forkcast.core.model.NutrientKey

/**
 * USDA FoodData Central nutrient ids → Forkcast keys, with the factor to convert FDC's unit
 * to ours (SPEC §6.2). FDC values are per 100 g for every data type we use.
 */
object UsdaNutrientMap {
    private data class M(val key: NutrientKey, val factor: Double = 1.0)

    private val byId: Map<Int, M> = mapOf(
        1003 to M(NutrientKey.PROTEIN_G),
        1004 to M(NutrientKey.FAT_G),
        1005 to M(NutrientKey.CARBS_G),
        1258 to M(NutrientKey.SAT_FAT_G),
        1257 to M(NutrientKey.TRANS_FAT_G),
        1292 to M(NutrientKey.MONO_FAT_G),
        1293 to M(NutrientKey.POLY_FAT_G),
        1253 to M(NutrientKey.CHOLESTEROL_MG),
        1079 to M(NutrientKey.FIBER_G),
        2000 to M(NutrientKey.SUGARS_G),
        1063 to M(NutrientKey.SUGARS_G), // "Sugars, Total" in Foundation foods
        1235 to M(NutrientKey.ADDED_SUGARS_G),
        1093 to M(NutrientKey.SODIUM_MG),
        1092 to M(NutrientKey.POTASSIUM_MG),
        1087 to M(NutrientKey.CALCIUM_MG),
        1089 to M(NutrientKey.IRON_MG),
        1090 to M(NutrientKey.MAGNESIUM_MG),
        1106 to M(NutrientKey.VITAMIN_A_UG), // RAE, µg
        1162 to M(NutrientKey.VITAMIN_C_MG),
        1114 to M(NutrientKey.VITAMIN_D_UG), // D2 + D3, µg
        1178 to M(NutrientKey.VITAMIN_B12_UG),
        1057 to M(NutrientKey.CAFFEINE_MG),
        1018 to M(NutrientKey.ALCOHOL_G),
        1051 to M(NutrientKey.WATER_G),
    )

    /** Energy candidates in preference order: label energy, then Atwater specific, then general. */
    private val energyIds = listOf(1008, 2048, 2047)
    private const val KJ_ID = 1062

    /**
     * Maps (nutrientId, unitName, value) triples. The first occurrence of an id wins
     * (FDC search results occasionally repeat an id with a different derivation).
     */
    fun map(rows: List<Triple<Int, String?, Double?>>): Map<NutrientKey, Double> {
        val first = LinkedHashMap<Int, Pair<String?, Double>>()
        rows.forEach { (id, unit, value) -> if (value != null && id !in first) first[id] = unit to value }
        val out = LinkedHashMap<NutrientKey, Double>()
        energyIds.firstNotNullOfOrNull { id -> first[id]?.takeIf { it.first.equals("kcal", true) }?.second }
            ?.let { out[NutrientKey.ENERGY_KCAL] = it }
            ?: first[KJ_ID]?.second?.let { out[NutrientKey.ENERGY_KCAL] = it / 4.184 }
        first.forEach { (id, uv) ->
            val m = byId[id] ?: return@forEach
            if (m.key in out) return@forEach
            out[m.key] = convert(uv.second, uv.first, m.key) * m.factor
        }
        return out
    }

    /** FDC is consistent with our units for these ids, but guard against a stray "UG"/"MG" mismatch. */
    private fun convert(value: Double, unit: String?, key: NutrientKey): Double {
        val u = unit?.lowercase() ?: return value
        return when (key.unit) {
            app.forkcast.core.model.NutrientUnit.MG -> when (u) { "g" -> value * 1000; "ug", "µg" -> value / 1000; else -> value }
            app.forkcast.core.model.NutrientUnit.UG -> when (u) { "mg" -> value * 1000; "g" -> value * 1_000_000; "iu" -> value; else -> value }
            app.forkcast.core.model.NutrientUnit.G -> when (u) { "mg" -> value / 1000; else -> value }
            app.forkcast.core.model.NutrientUnit.KCAL -> value
        }
    }
}

/**
 * Open Food Facts `nutriments` keys (per 100 g) → Forkcast keys. OFF stores masses in grams
 * (`sodium_100g` = 0.4 means 400 mg), so mg/µg nutrients are scaled.
 */
object OffNutrientMap {
    private val map: Map<String, Pair<NutrientKey, Double>> = mapOf(
        "energy-kcal_100g" to (NutrientKey.ENERGY_KCAL to 1.0),
        "proteins_100g" to (NutrientKey.PROTEIN_G to 1.0),
        "carbohydrates_100g" to (NutrientKey.CARBS_G to 1.0),
        "fat_100g" to (NutrientKey.FAT_G to 1.0),
        "saturated-fat_100g" to (NutrientKey.SAT_FAT_G to 1.0),
        "trans-fat_100g" to (NutrientKey.TRANS_FAT_G to 1.0),
        "monounsaturated-fat_100g" to (NutrientKey.MONO_FAT_G to 1.0),
        "polyunsaturated-fat_100g" to (NutrientKey.POLY_FAT_G to 1.0),
        "cholesterol_100g" to (NutrientKey.CHOLESTEROL_MG to 1000.0),
        "fiber_100g" to (NutrientKey.FIBER_G to 1.0),
        "sugars_100g" to (NutrientKey.SUGARS_G to 1.0),
        "added-sugars_100g" to (NutrientKey.ADDED_SUGARS_G to 1.0),
        "sodium_100g" to (NutrientKey.SODIUM_MG to 1000.0),
        "potassium_100g" to (NutrientKey.POTASSIUM_MG to 1000.0),
        "calcium_100g" to (NutrientKey.CALCIUM_MG to 1000.0),
        "iron_100g" to (NutrientKey.IRON_MG to 1000.0),
        "magnesium_100g" to (NutrientKey.MAGNESIUM_MG to 1000.0),
        "vitamin-a_100g" to (NutrientKey.VITAMIN_A_UG to 1_000_000.0),
        "vitamin-c_100g" to (NutrientKey.VITAMIN_C_MG to 1000.0),
        "vitamin-d_100g" to (NutrientKey.VITAMIN_D_UG to 1_000_000.0),
        "vitamin-b12_100g" to (NutrientKey.VITAMIN_B12_UG to 1_000_000.0),
        "caffeine_100g" to (NutrientKey.CAFFEINE_MG to 1000.0),
        "alcohol_100g" to (NutrientKey.ALCOHOL_G to 1.0),
    )

    fun map(nutriments: Map<String, Double>): Map<NutrientKey, Double> {
        val out = LinkedHashMap<NutrientKey, Double>()
        map.forEach { (offKey, target) -> nutriments[offKey]?.let { out[target.first] = it * target.second } }
        // Some products only have kJ (or "energy_100g", which OFF stores in kJ).
        if (NutrientKey.ENERGY_KCAL !in out) {
            (nutriments["energy-kj_100g"] ?: nutriments["energy_100g"])?.let { out[NutrientKey.ENERGY_KCAL] = it / 4.184 }
        }
        // Salt only: sodium = salt / 2.5
        if (NutrientKey.SODIUM_MG !in out) nutriments["salt_100g"]?.let { out[NutrientKey.SODIUM_MG] = it / 2.5 * 1000 }
        return out
    }
}
