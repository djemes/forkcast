package app.forkcast.ui.ai

import android.content.res.Resources
import androidx.annotation.StringRes
import app.forkcast.R
import app.forkcast.core.model.ProviderId
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


/**
 * One specific, actionable message per error (SPEC §4.6 acceptance; DESIGN.md §6 tone). When the
 * provider answered, the message names its HTTP status and error code ("HTTP 401 ·
 * ACCESS_TOKEN_TYPE_UNSUPPORTED") instead of a generic "rejected"; [aiErrorDetail] adds its words.
 */
fun Resources.aiErrorMessage(error: Throwable?, provider: ProviderId?): String {
    val who = provider?.shortName() ?: "The AI provider"
    val reply = (error as? AiError)?.reply
    return when (error) {
        is AiError.InvalidKey -> when {
            // Saved estimate errors come back from their message text (see errorFromCode).
            error.missing || error.message?.contains(AiError.NO_KEY) == true -> getString(R.string.ai_err_no_key)
            reply == null -> getString(R.string.ai_err_invalid_key, who)
            else -> getString(R.string.ai_err_key_status, who, reply.summary) + " " + keyHint(reply.code)
        }
        is AiError.RateLimited -> getString(R.string.ai_err_rate_limited, who)
        is AiError.QuotaExhausted -> getString(R.string.ai_err_quota, who)
        is AiError.ModelNotFound -> getString(R.string.ai_err_model, who, error.model)
        is AiError.ContentRefused -> getString(R.string.ai_err_refused, who)
        is AiError.BadResponse -> getString(R.string.ai_err_bad_response, who)
        is AiError.InvalidRequest ->
            if (reply != null) getString(R.string.ai_err_request_status, who, reply.summary)
            else getString(R.string.ai_err_request, who, error.message.orEmpty().substringAfter(": ", ""))
        is AiError.Network -> getString(R.string.ai_err_network)
        is AiError.Timeout -> getString(R.string.ai_err_timeout, who)
        is AiError.Server -> getString(R.string.ai_err_server, who, error.code)
        else -> getString(R.string.ai_err_bad_response, who)
    }
}

/** The provider's own words (Google's error message), shown small under [aiErrorMessage]. */
fun aiErrorDetail(error: Throwable?): String? = (error as? AiError)?.reply?.message?.takeIf { it.isNotBlank() }?.take(300)

/** What to do about a rejected key, by the provider's error code. */
private fun Resources.keyHint(code: String?): String = getString(
    when {
        code == "ACCESS_TOKEN_TYPE_UNSUPPORTED" -> R.string.ai_err_hint_token_type
        code == "SERVICE_DISABLED" -> R.string.ai_err_hint_service_disabled
        code == "API_KEY_INVALID" || code == "authentication_error" || code == "invalid_api_key" -> R.string.ai_err_hint_key_invalid
        code != null && code.startsWith("API_KEY_") && code.endsWith("_BLOCKED") -> R.string.ai_err_hint_key_restricted
        code == "permission_error" || code == "PERMISSION_DENIED" -> R.string.ai_err_hint_permission
        else -> R.string.ai_err_hint_check_key
    },
)
