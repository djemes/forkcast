package app.forkcast.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** AI providers the app can talk to (AI_PROVIDERS.md §2). */
@Serializable
enum class ProviderId { CLAUDE, GEMINI, OPENAI_COMPAT }

/** What the user told us about a key's billing; the API can't report it (AI_PROVIDERS.md §2.5). */
@Serializable
enum class TierLabel { FREE, PAID, UNKNOWN }

/**
 * One entry in the "Use in this order" chain (SPEC §4.7). The API key is stored separately,
 * encrypted, under [id]; it is never part of this object.
 */
data class AiProfile(
    val id: String,
    val name: String,
    val provider: ProviderId,
    val model: String,
    /** OpenAI-compatible servers only, e.g. `https://openrouter.ai/api`. */
    val baseUrl: String?,
    val tierLabel: TierLabel,
    val order: Int,
    val enabled: Boolean,
    /** Set after the key was rejected; cleared when the key is changed or a test passes. */
    val needsAttention: Boolean,
    /** Epoch ms until which the chain skips this profile (quota window after a 429). */
    val cooldownUntil: Long?,
) {
}

/**
 * The structured result every provider returns (AI_PROVIDERS.md §3). Field names match the
 * JSON schema the models are given, so this class is also the parser.
 */
@Serializable
data class MealEstimateJson(
    @SerialName("meal_name") val mealName: String = "",
    val items: List<EstimatedItem> = emptyList(),
    @SerialName("overall_confidence") val overallConfidence: Double = 0.0,
    val assumptions: List<String> = emptyList(),
    @SerialName("clarifying_question") val clarifyingQuestion: String? = null,
)

@Serializable
data class EstimatedItem(
    val name: String = "",
    @SerialName("usda_query") val usdaQuery: String = "",
    val preparation: String = "unknown",
    val grams: Double = 0.0,
    @SerialName("grams_low") val gramsLow: Double = 0.0,
    @SerialName("grams_high") val gramsHigh: Double = 0.0,
    val confidence: Double = 0.0,
    @SerialName("is_hidden_ingredient") val isHiddenIngredient: Boolean = false,
    val nutrients: EstimatedNutrients = EstimatedNutrients(),
)

/** The model's own nutrient estimate for the item at its stated grams. */
@Serializable
data class EstimatedNutrients(
    val kcal: Double = 0.0,
    @SerialName("protein_g") val proteinG: Double = 0.0,
    @SerialName("carbs_g") val carbsG: Double = 0.0,
    @SerialName("fat_g") val fatG: Double = 0.0,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("sat_fat_g") val satFatG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
) {
    // `this@EstimatedNutrients`: inside buildMap the receiver is itself a Nutrients map, whose
    // `kcal` extension would otherwise shadow this class's property.
    fun toNutrients(): Nutrients = buildMap {
        put(NutrientKey.ENERGY_KCAL, this@EstimatedNutrients.kcal)
        put(NutrientKey.PROTEIN_G, proteinG)
        put(NutrientKey.CARBS_G, carbsG)
        put(NutrientKey.FAT_G, fatG)
        fiberG?.let { put(NutrientKey.FIBER_G, it) }
        sugarG?.let { put(NutrientKey.SUGARS_G, it) }
        satFatG?.let { put(NutrientKey.SAT_FAT_G, it) }
        sodiumMg?.let { put(NutrientKey.SODIUM_MG, it) }
    }
}
