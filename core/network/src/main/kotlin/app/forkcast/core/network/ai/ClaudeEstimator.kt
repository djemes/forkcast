package app.forkcast.core.network.ai

import app.forkcast.core.model.ProviderId
import app.forkcast.core.network.NetworkJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Anthropic Messages API (AI_PROVIDERS.md §2.1, verified 2026-10-18): JSON via
 * `output_config.format`, adaptive thinking with explicit `effort`, images before text.
 */
class ClaudeEstimator(
    private val http: HttpClient,
    private val apiKey: suspend () -> String?,
    private val model: String,
    private val effort: String = "low",
    private val maxOutputTokens: Int = 4096,
    private val baseUrl: String = BASE,
    private val estimateTimeoutMs: Long? = null,
) : MealEstimator {
    override val providerId = ProviderId.CLAUDE

    override suspend fun listModels(): Result<List<ModelInfo>> = aiResult {
        val key = key()
        val t = timedCall { http.get("$baseUrl/v1/models") { auth(key); parameter("limit", 1000) } }
        checkStatus(t, key)
        NetworkJson.decodeFromString(ModelsPage.serializer(), t.body).data.map { m ->
            val vision = m.capabilities?.get("image_input")?.jsonObject?.get("supported")?.jsonPrimitive?.boolean
            ModelInfo(m.id, m.displayName ?: m.id, vision)
        }
    }

    override suspend fun testConnection(): Result<ConnectionReport> = aiResult {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 256)
            putJsonObject("output_config") { put("effort", "low") }
            putJsonArray("messages") {
                addJsonObject { put("role", "user"); put("content", MealPrompt.TEST_TEXT) }
            }
        }
        val t = post(body)
        val r = NetworkJson.decodeFromString(MessageResponse.serializer(), t.body)
        ConnectionReport(r.model ?: model, t.latencyMs)
    }

    override suspend fun estimate(request: MealEstimateRequest): Result<MealEstimateResult> = aiResult {
        val t = post(requestBody(request), estimateTimeoutMs, request.onSent)
        val r = NetworkJson.decodeFromString(MessageResponse.serializer(), t.body)
        when (r.stopReason) {
            "refusal" -> throw AiError.ContentRefused(r.stopDetails?.get("explanation")?.jsonPrimitive?.content)
            "max_tokens" -> throw AiError.BadResponse(t.body, "the answer was cut off")
        }
        val text = r.content.firstOrNull { it.type == "text" }?.text ?: throw AiError.BadResponse(t.body, "no text in reply")
        val parsed = MealEstimateParser.parse(text)
        MealEstimateResult(parsed.json, parsed.warnings, text, r.usage?.inputTokens, r.usage?.outputTokens, t.latencyMs, r.model ?: model)
    }

    /** Visible for the privacy test (no body data in requests). */
    fun requestBody(request: MealEstimateRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", maxOutputTokens)
        put("system", MealPrompt.system)
        putJsonObject("output_config") {
            put("effort", effort)
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", MealSchema.claude)
            }
        }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    for (img in request.images) addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", img.mimeType)
                            put("data", img.jpeg.base64())
                        }
                    }
                    addJsonObject { put("type", "text"); put("text", MealPrompt.userText(request)) }
                }
            }
        }
    }

    private suspend fun post(body: JsonObject, timeoutMs: Long? = null, onSent: (() -> Unit)? = null): Timed {
        val key = key()
        val t = timedCall {
            http.post("$baseUrl/v1/messages") {
                auth(key)
                estimateOptions(timeoutMs, onSent)
                contentType(ContentType.Application.Json)
                setBody(NetworkJson.encodeToString(JsonObject.serializer(), body))
            }
        }
        checkStatus(t, key)
        return t
    }

    private suspend fun key(): String = apiKey()?.takeIf { it.isNotBlank() } ?: throw AiError.InvalidKey(AiError.NO_KEY)

    private fun io.ktor.client.request.HttpRequestBuilder.auth(key: String) {
        header("x-api-key", key)
        header("anthropic-version", API_VERSION)
    }

    private fun checkStatus(t: Timed, key: String) {
        val code = t.response.status.value
        if (code in 200..299) return
        val err = runCatching { NetworkJson.decodeFromString(ErrorEnvelope.serializer(), t.body).error }.getOrNull()
        val type = err?.type
        val message = err?.message?.withoutKey(key)
        val reply = ProviderReply(code, type, message)
        throw when {
            code == 401 || type == "authentication_error" -> AiError.InvalidKey(reply = reply)
            code == 403 || type == "permission_error" -> AiError.InvalidKey(reply = reply)
            code == 402 || type == "billing_error" -> AiError.QuotaExhausted(null, reply = reply)
            code == 404 || type == "not_found_error" -> AiError.ModelNotFound(model, reply)
            code == 429 || type == "rate_limit_error" -> AiError.RateLimited(t.response.retryAfterMs(), reply)
            code == 529 || type == "overloaded_error" -> AiError.Server(code, reply)
            code >= 500 -> AiError.Server(code, reply)
            message?.contains("credit balance", ignoreCase = true) == true -> AiError.QuotaExhausted(null, reply = reply)
            else -> AiError.InvalidRequest(null, reply)
        }
    }

    @Serializable
    private data class ModelsPage(val data: List<ModelItem> = emptyList())

    @Serializable
    private data class ModelItem(
        val id: String,
        @kotlinx.serialization.SerialName("display_name") val displayName: String? = null,
        val capabilities: JsonObject? = null,
    )

    @Serializable
    private data class MessageResponse(
        val model: String? = null,
        val content: List<ContentBlock> = emptyList(),
        @kotlinx.serialization.SerialName("stop_reason") val stopReason: String? = null,
        @kotlinx.serialization.SerialName("stop_details") val stopDetails: JsonObject? = null,
        val usage: Usage? = null,
    )

    @Serializable
    private data class ContentBlock(val type: String, val text: String? = null)

    @Serializable
    private data class Usage(
        @kotlinx.serialization.SerialName("input_tokens") val inputTokens: Int? = null,
        @kotlinx.serialization.SerialName("output_tokens") val outputTokens: Int? = null,
    )

    @Serializable
    private data class ErrorEnvelope(val error: ErrorBody? = null)

    @Serializable
    private data class ErrorBody(val type: String? = null, val message: String? = null)

    companion object {
        const val BASE = "https://api.anthropic.com"
        const val API_VERSION = "2023-06-01"
    }
}
