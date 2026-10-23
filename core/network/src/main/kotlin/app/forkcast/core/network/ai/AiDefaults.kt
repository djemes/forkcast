package app.forkcast.core.network.ai

import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.network.NetworkJson
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Recommended models, prices and quota rules from `ai/ai_defaults.json` (AI_PROVIDERS.md §2.4).
 * Model IDs are never hardcoded in logic; they come from here or the user's settings.
 */
@Serializable
data class AiDefaults(
    val maxImageEdgePx: Int = 1568,
    val jpegQuality: Int = 85,
    val maxImages: Int = 3,
    val maxOutputTokens: Int = 4096,
    /** Timeout of one photo-estimate request (the client's 60 s stays for lists and Test). */
    val photoTimeoutSec: Int = 120,
    /** Timeout of one described-meal request (no images to upload or look at). */
    val textTimeoutSec: Int = 75,
    /** "Correct it" resends only the first photo, at this long edge (the previous result carries the rest). */
    val correctionImageEdgePx: Int = 1024,
    val providers: Map<ProviderId, ProviderDefaults> = emptyMap(),
    val models: Map<String, ModelDefaults> = emptyMap(),
    val defaultChain: List<ChainEntry> = emptyList(),
) {
    fun provider(id: ProviderId): ProviderDefaults = providers[id] ?: ProviderDefaults()

    /** Price per 1M tokens for [model] on [day], or null when unknown (user can enter one). */
    fun price(model: String, day: LocalDate): Price? {
        val m = models[model] ?: return null
        val change = m.priceChanges.filter { !LocalDate.parse(it.from).isAfter(day) }.maxByOrNull { it.from }
        val input = change?.inputPerM ?: m.inputPerM ?: return null
        val output = change?.outputPerM ?: m.outputPerM ?: return null
        return Price(input, output)
    }

    fun displayName(model: String): String = models[model]?.displayName ?: model

    companion object {
        fun load(): AiDefaults {
            val text = requireNotNull(AiDefaults::class.java.getResourceAsStream("/ai/ai_defaults.json")) { "ai_defaults.json missing" }
                .bufferedReader().use { it.readText() }
            return NetworkJson.decodeFromString(serializer(), text)
        }
    }
}

@Serializable
data class ProviderDefaults(
    val recommendedModel: String? = null,
    val budgetModel: String? = null,
    val defaultBaseUrl: String? = null,
    /** "rolling" or "midnight:<zone id>". */
    val quotaReset: String = "rolling",
)

@Serializable
data class ModelDefaults(
    val displayName: String? = null,
    val inputPerM: Double? = null,
    val outputPerM: Double? = null,
    /** Claude `output_config.effort`. */
    val effort: String? = null,
    /** Gemini `generation_config.thinking_level`. */
    val thinkingLevel: String? = null,
    val priceChanges: List<PriceChange> = emptyList(),
)

@Serializable
data class PriceChange(val from: String, val inputPerM: Double? = null, val outputPerM: Double? = null)

@Serializable
data class ChainEntry(
    val provider: ProviderId,
    val model: String,
    val tier: TierLabel = TierLabel.UNKNOWN,
    val name: String,
    val sameKeyAsPrevious: Boolean = false,
    val optional: Boolean = false,
)

data class Price(val inputPerM: Double, val outputPerM: Double) {
    fun cost(inputTokens: Long, outputTokens: Long): Double = (inputTokens * inputPerM + outputTokens * outputPerM) / 1_000_000.0
}
