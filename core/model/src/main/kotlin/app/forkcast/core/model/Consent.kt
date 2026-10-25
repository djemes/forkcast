package app.forkcast.core.model

/** The Terms of Use and Disclaimer as accepted on this phone (roadmap 2.0, task 4.1). Never sent anywhere. */
data class AgreementAcceptance(val version: Int, val acceptedAt: Long, val appVersion: String)

/**
 * Who receives what an AI service is sent. Permission is asked once per recipient (task 4.2):
 * Gemini and Claude by provider, OpenAI-compatible servers by host, since OpenRouter and an
 * Ollama at home receive meals in very different places.
 */
object AiRecipient {
    fun of(provider: ProviderId, baseUrl: String?): String = when (provider) {
        ProviderId.GEMINI -> "gemini"
        ProviderId.CLAUDE -> "claude"
        ProviderId.OPENAI_COMPAT -> "openai:" + host(baseUrl)
    }

    fun of(profile: AiProfile): String = of(profile.provider, profile.baseUrl)

    /** "https://OpenRouter.ai/api" → "openrouter.ai"; a URL without a scheme still works. */
    fun host(baseUrl: String?): String {
        val url = baseUrl?.trim().orEmpty()
        if (url.isEmpty()) return ""
        val withScheme = if ("://" in url) url else "https://$url"
        val host = runCatching { java.net.URI(withScheme).host }.getOrNull()
        return (host ?: url).lowercase()
    }
}
