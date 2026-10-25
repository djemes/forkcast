package app.forkcast.core.network.ai

import app.forkcast.core.model.MealEstimateJson
import app.forkcast.core.model.ProviderId

/**
 * One implementation per provider (AI_PROVIDERS.md §1). Adding a provider never touches UI or
 * domain code: it implements this, and [MealEstimatorFactory] learns to build it.
 * Every call returns a [Result] whose failure is always an [AiError].
 */
interface MealEstimator {
    val providerId: ProviderId
    suspend fun listModels(): Result<List<ModelInfo>>
    /** Tiny text-only call. */
    suspend fun testConnection(): Result<ConnectionReport>
    suspend fun estimate(request: MealEstimateRequest): Result<MealEstimateResult>
}

/** JPEG bytes, already downscaled with EXIF stripped (AI_PROVIDERS.md §5). */
class EncodedImage(val jpeg: ByteArray) {
    val mimeType: String get() = "image/jpeg"
}

/**
 * Everything a provider may see. Never add weight, goals, age, sex or other body/health data
 * here (AI_PROVIDERS.md §2.5; a unit test inspects the serialized requests).
 */
data class MealEstimateRequest(
    val images: List<EncodedImage>,
    val description: String?,
    /** "Lunch" */
    val mealSlot: String,
    /** "12:40" */
    val localTime: String,
    /** "US customary" | "metric" */
    val unitsHint: String,
    val personalContext: String?,
    /** For "Correct it" follow-ups: the previous result, resent as context. */
    val previous: MealEstimateJson?,
    val correction: String?,
    val model: String,
    /**
     * Called once the request body has been sent, so the screen can move from "Sending" to
     * "Waiting for Gemini" (owner, 2026-10-23). Never part of what is sent.
     */
    val onSent: (() -> Unit)? = null,
    /** The app's language in English ("English", "Portuguese (Brazil)"): food and meal names come back in it. */
    val language: String = "English",
) {
    /** A described meal (SPEC §4.6.2): no photo, the description is all the model has. */
    val isTextOnly: Boolean get() = images.isEmpty()
}

data class MealEstimateResult(
    val parsed: MealEstimateJson,
    /** Items the validator clamped or dropped, in plain words, for the review screen. */
    val warnings: List<String>,
    val rawJson: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long,
    val model: String,
)

data class ModelInfo(
    val id: String,
    val displayName: String,
    /** False only when the provider says so; null = the API doesn't report it. */
    val supportsImages: Boolean?,
)

data class ConnectionReport(val model: String, val latencyMs: Long)

/**
 * What the provider actually answered when it refused a request: the HTTP status, its own error
 * code (Google's `ErrorInfo.reason` such as `ACCESS_TOKEN_TYPE_UNSUPPORTED`, else its status or
 * error type) and its message. Shown under the friendly error so the real cause is visible.
 * The estimator removes the API key from [message] before building this.
 */
data class ProviderReply(val httpStatus: Int, val code: String?, val message: String?) {
    /** "HTTP 401 · ACCESS_TOKEN_TYPE_UNSUPPORTED" */
    val summary: String get() = listOfNotNull("HTTP $httpStatus", code?.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/**
 * The one error type estimators report (AI_PROVIDERS.md §1). The UI shows a specific message for
 * each, plus [reply] when the provider sent one.
 */
sealed class AiError(message: String, val reply: ProviderReply? = null) : Exception(message) {
    class InvalidKey(detail: String? = null, reply: ProviderReply? = null) : AiError(text("API key rejected", detail, reply), reply) {
        /** No key is saved for the profile: nothing was sent. */
        val missing: Boolean = detail == NO_KEY
    }
    /**
     * Short window (per-minute limit, overload). [retryAfterMs] when the provider says; [quota] is
     * which limit, when it says (Gemini's quota id, e.g. `GenerateRequestsPerMinutePerProjectPerModel-FreeTier`).
     */
    class RateLimited(val retryAfterMs: Long?, reply: ProviderReply? = null, val quota: String? = null) :
        AiError(text("Rate limited", quota, reply), reply)
    /** Daily quota or billing credit used up; [resetsAtMs] when known (Gemini: next midnight Pacific). */
    class QuotaExhausted(val resetsAtMs: Long?, detail: String? = null, reply: ProviderReply? = null, val quota: String? = null) :
        AiError(text("Quota used up", detail ?: quota, reply), reply)
    class ModelNotFound(val model: String, reply: ProviderReply? = null) : AiError(text("Model not found: $model", null, reply), reply)
    class ContentRefused(detail: String? = null) : AiError(text("The provider declined this request", detail, null))
    /** The provider accepted the request but the output wasn't a usable estimate. */
    class BadResponse(val raw: String?, detail: String? = null) : AiError(text("Unexpected response", detail, null))
    /** 4xx we can't classify further (e.g. the model doesn't accept images). */
    class InvalidRequest(detail: String?, reply: ProviderReply? = null) : AiError(text("Request rejected", detail, reply), reply)
    class Network : AiError("No connection")
    class Timeout : AiError("The provider took too long")
    class Server(val code: Int, reply: ProviderReply? = null) : AiError(text("Provider error $code", null, reply), reply)

    companion object {
        /** Detail of [InvalidKey] when the profile has no key at all. */
        const val NO_KEY = "no key saved"

        /** "API key rejected (HTTP 401 · ACCESS_TOKEN_TYPE_UNSUPPORTED): Request had invalid…" */
        private fun text(base: String, detail: String?, reply: ProviderReply?): String {
            val head = reply?.let { "$base (${it.summary})" } ?: base
            val tail = (detail ?: reply?.message)?.takeIf { it.isNotBlank() }?.take(200)
            return if (tail == null) head else "$head: $tail"
        }
    }
}

/**
 * Removes [key] from text a provider sent back (some error messages quote the request), so a key
 * can never reach the screen, the database or an export through an error message.
 */
internal fun String.withoutKey(key: String?): String =
    if (key.isNullOrBlank() || key.length < 8) this else replace(key, "•••")
