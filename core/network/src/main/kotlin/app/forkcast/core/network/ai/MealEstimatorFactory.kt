package app.forkcast.core.network.ai

import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.ProviderId
import io.ktor.client.HttpClient

/**
 * Builds the estimator for a profile at call time (AI_PROVIDERS.md §1), so switching provider
 * is only a settings change. [keyFor] reads the profile's encrypted key when a request is made.
 */
class MealEstimatorFactory(
    private val http: HttpClient,
    private val defaults: AiDefaults,
    private val keyFor: suspend (profileId: String) -> String?,
) {
    /** [keyOverride]: a key typed in the editor but not saved yet (model list, first test). */
    /**
     * [textOnly]: the estimator will answer a described meal (no photo), so its requests get the
     * shorter text timeout.
     */
    fun create(profile: AiProfile, model: String = profile.model, keyOverride: String? = null, textOnly: Boolean = false): MealEstimator {
        val timeoutMs = 1000L * if (textOnly) defaults.textTimeoutSec else defaults.photoTimeoutSec
        val key: suspend () -> String? = if (keyOverride != null) ({ keyOverride }) else ({ keyFor(profile.id) })
        val modelDefaults = defaults.models[model]
        return when (profile.provider) {
            ProviderId.CLAUDE -> ClaudeEstimator(
                http, key, model,
                effort = modelDefaults?.effort ?: "low",
                maxOutputTokens = defaults.maxOutputTokens,
                estimateTimeoutMs = timeoutMs,
            )
            ProviderId.GEMINI -> GeminiEstimator(
                http, key, model,
                thinkingLevel = modelDefaults?.thinkingLevel,
                maxOutputTokens = defaults.maxOutputTokens,
                estimateTimeoutMs = timeoutMs,
            )
            ProviderId.OPENAI_COMPAT -> OpenAiCompatEstimator(
                http, key, model,
                baseUrl = profile.baseUrl ?: defaults.provider(ProviderId.OPENAI_COMPAT).defaultBaseUrl ?: "https://api.openai.com",
                maxOutputTokens = defaults.maxOutputTokens,
                estimateTimeoutMs = timeoutMs,
            )
        }
    }
}
