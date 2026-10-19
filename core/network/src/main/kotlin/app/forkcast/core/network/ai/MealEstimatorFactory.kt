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
    fun create(profile: AiProfile, model: String = profile.model): MealEstimator {
        val key: suspend () -> String? = { keyFor(profile.id) }
        val modelDefaults = defaults.models[model]
        return when (profile.provider) {
            ProviderId.CLAUDE -> ClaudeEstimator(
                http, key, model,
                effort = modelDefaults?.effort ?: "low",
                maxOutputTokens = defaults.maxOutputTokens,
            )
            ProviderId.GEMINI -> GeminiEstimator(
                http, key, model,
                thinkingLevel = modelDefaults?.thinkingLevel,
                maxOutputTokens = defaults.maxOutputTokens,
            )
            ProviderId.OPENAI_COMPAT -> OpenAiCompatEstimator(
                http, key, model,
                baseUrl = profile.baseUrl ?: defaults.provider(ProviderId.OPENAI_COMPAT).defaultBaseUrl ?: "https://api.openai.com",
                maxOutputTokens = defaults.maxOutputTokens,
            )
        }
    }
}
