package app.forkcast

/**
 * Every outside page a guide or setting opens, in one place (CLAUDE.md: guide URLs live in one
 * config file), so a provider moving a page is a one-line fix. Checked against each provider's
 * docs on 2026-10-25 (DECISIONS.md); the guides' wording stays generic so small console changes
 * don't break them.
 */
object GuideLinks {
    // Gemini (Google)
    const val AI_STUDIO_KEYS = "https://aistudio.google.com/apikey"
    const val GEMINI_API_LIBRARY = "https://console.cloud.google.com/apis/library/generativelanguage.googleapis.com"
    const val GOOGLE_CLOUD_CREDENTIALS = "https://console.cloud.google.com/apis/credentials"
    const val GOOGLE_CLOUD_BILLING = "https://console.cloud.google.com/billing"
    const val GEMINI_TERMS = "https://ai.google.dev/gemini-api/terms"

    // Claude (Anthropic)
    const val CLAUDE_CONSOLE = "https://platform.claude.com/"
    const val CLAUDE_BILLING = "https://platform.claude.com/settings/billing"
    const val CLAUDE_LIMITS = "https://platform.claude.com/settings/limits"
    const val CLAUDE_KEYS = "https://platform.claude.com/settings/keys"
    const val CLAUDE_TERMS = "https://www.anthropic.com/legal/commercial-terms"

    // OpenAI-compatible servers
    const val OPENAI_PLATFORM = "https://platform.openai.com/"
    const val OPENAI_BILLING = "https://platform.openai.com/settings/organization/billing/overview"
    const val OPENAI_KEYS = "https://platform.openai.com/api-keys"
    const val OPENAI_SERVER = "https://api.openai.com"
    const val OPENROUTER_KEYS = "https://openrouter.ai/keys"
    const val OPENROUTER_CREDITS = "https://openrouter.ai/settings/credits"
    const val OPENROUTER_SERVER = "https://openrouter.ai/api"
    const val OLLAMA_DOWNLOAD = "https://ollama.com/download"
    const val OLLAMA_FAQ = "https://docs.ollama.com/faq"

    // Food databases: FoodData Central's "Get an API Key" page (the api.data.gov form loads on it).
    const val USDA_SIGNUP = "https://fdc.nal.usda.gov/api-key-signup"

    // Android and GitHub
    const val HEALTH_CONNECT_STORE = "https://play.google.com/store/apps/details?id="
    const val GITHUB_TOKEN_NEW = "https://github.com/settings/personal-access-tokens/new"
}
