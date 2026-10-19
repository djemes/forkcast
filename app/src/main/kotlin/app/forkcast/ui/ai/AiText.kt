package app.forkcast.ui.ai

import android.content.res.Resources
import androidx.annotation.StringRes
import app.forkcast.R
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.network.ai.AiError

@StringRes
fun ProviderId.label(): Int = when (this) {
    ProviderId.CLAUDE -> R.string.ai_provider_claude
    ProviderId.GEMINI -> R.string.ai_provider_gemini
    ProviderId.OPENAI_COMPAT -> R.string.ai_provider_openai
}

/** Short company name for messages ("Claude rejected the API key…"). */
fun ProviderId.shortName(): String = when (this) {
    ProviderId.CLAUDE -> "Claude"
    ProviderId.GEMINI -> "Gemini"
    ProviderId.OPENAI_COMPAT -> "The AI server"
}

@StringRes
fun TierLabel.label(): Int = when (this) {
    TierLabel.FREE -> R.string.ai_tier_free
    TierLabel.PAID -> R.string.ai_tier_paid
    TierLabel.UNKNOWN -> R.string.ai_tier_unknown
}

/** One specific, actionable message per error (SPEC §4.6 acceptance; DESIGN.md §6 tone). */
fun Resources.aiErrorMessage(error: Throwable?, provider: ProviderId?): String {
    val who = provider?.shortName() ?: "The AI provider"
    return when (error) {
        is AiError.InvalidKey ->
            if (error.message?.contains("no key saved") == true) getString(R.string.ai_err_no_key) else getString(R.string.ai_err_invalid_key, who)
        is AiError.RateLimited -> getString(R.string.ai_err_rate_limited, who)
        is AiError.QuotaExhausted -> getString(R.string.ai_err_quota, who)
        is AiError.ModelNotFound -> getString(R.string.ai_err_model, who, error.model)
        is AiError.ContentRefused -> getString(R.string.ai_err_refused, who)
        is AiError.BadResponse -> getString(R.string.ai_err_bad_response, who)
        is AiError.InvalidRequest -> getString(R.string.ai_err_request, who, error.message.orEmpty().substringAfter(": ", ""))
        is AiError.Network -> getString(R.string.ai_err_network)
        is AiError.Timeout -> getString(R.string.ai_err_timeout, who)
        is AiError.Server -> getString(R.string.ai_err_server, who, error.code)
        else -> getString(R.string.ai_err_bad_response, who)
    }
}
