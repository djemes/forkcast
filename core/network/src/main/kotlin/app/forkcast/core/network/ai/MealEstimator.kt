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
)

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

/** The one error type estimators report (AI_PROVIDERS.md §1). The UI shows a specific message for each. */
sealed class AiError(message: String) : Exception(message) {
    class InvalidKey(detail: String? = null) : AiError("API key rejected${detail.suffix()}")
    /** Short window (per-minute limit, overload). [retryAfterMs] when the provider says. */
    class RateLimited(val retryAfterMs: Long?) : AiError("Rate limited")
    /** Daily quota or billing credit used up; [resetsAtMs] when known (Gemini: next midnight Pacific). */
    class QuotaExhausted(val resetsAtMs: Long?, detail: String? = null) : AiError("Quota used up${detail.suffix()}")
    class ModelNotFound(val model: String) : AiError("Model not found: $model")
    class ContentRefused(detail: String? = null) : AiError("The provider declined this request${detail.suffix()}")
    /** The provider accepted the request but the output wasn't a usable estimate. */
    class BadResponse(val raw: String?, detail: String? = null) : AiError("Unexpected response${detail.suffix()}")
    /** 4xx we can't classify further (e.g. the model doesn't accept images). */
    class InvalidRequest(detail: String?) : AiError("Request rejected${detail.suffix()}")
    class Network : AiError("No connection")
    class Timeout : AiError("The provider took too long")
    class Server(val code: Int) : AiError("Provider error $code")
}

private fun String?.suffix() = if (isNullOrBlank()) "" else ": ${take(200)}"
