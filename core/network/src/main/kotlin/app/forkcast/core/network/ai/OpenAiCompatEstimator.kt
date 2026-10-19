package app.forkcast.core.network.ai

import app.forkcast.core.model.ProviderId
import app.forkcast.core.network.NetworkJson
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI-compatible chat completions (OpenAI, OpenRouter, Ollama, LM Studio, vLLM;
 * AI_PROVIDERS.md §2.3). Tries strict `json_schema`; if the server rejects it, retries once
 * with `json_object` and the schema pasted into the prompt.
 */
class OpenAiCompatEstimator(
    private val http: HttpClient,
    private val apiKey: suspend () -> String?,
    private val model: String,
    baseUrl: String,
    private val maxOutputTokens: Int = 4096,
) : MealEstimator {
    override val providerId = ProviderId.OPENAI_COMPAT

    /** Accepts "https://host", "https://host/v1" or "https://host/v1/". */
    private val root = baseUrl.trim().trimEnd('/').removeSuffix("/v1")

    override suspend fun listModels(): Result<List<ModelInfo>> = aiResult {
        val key = apiKey()
        val t = timedCall { http.get("$root/v1/models") { auth(key) } }
        checkStatus(t, key)
        NetworkJson.decodeFromString(ModelsPage.serializer(), t.body).data.map { ModelInfo(it.id, it.id, supportsImages = null) }
    }

    override suspend fun testConnection(): Result<ConnectionReport> = aiResult {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16)
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", MealPrompt.TEST_TEXT) } }
        }
        val t = post(body)
        val r = NetworkJson.decodeFromString(ChatResponse.serializer(), t.body)
        ConnectionReport(r.model ?: model, t.latencyMs)
    }

    override suspend fun estimate(request: MealEstimateRequest): Result<MealEstimateResult> = aiResult {
        val t = try {
            post(requestBody(request, strict = true))
        } catch (e: AiError.InvalidRequest) {
            post(requestBody(request, strict = false))
        }
        val r = NetworkJson.decodeFromString(ChatResponse.serializer(), t.body)
        val choice = r.choices.firstOrNull() ?: throw AiError.BadResponse(t.body, "no choices")
        if (choice.finishReason == "content_filter" || !choice.message?.refusal.isNullOrBlank()) {
            throw AiError.ContentRefused(choice.message?.refusal)
        }
        if (choice.finishReason == "length") throw AiError.BadResponse(t.body, "the answer was cut off")
        val text = choice.message?.content ?: throw AiError.BadResponse(t.body, "no text in reply")
        val parsed = MealEstimateParser.parse(text)
        MealEstimateResult(parsed.json, parsed.warnings, text, r.usage?.promptTokens, r.usage?.completionTokens, t.latencyMs, r.model ?: model)
    }

    /** Visible for the privacy test (no body data in requests). */
    fun requestBody(request: MealEstimateRequest, strict: Boolean = true): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", maxOutputTokens)
        val system = if (strict) MealPrompt.system else MealPrompt.system + "\n\nReturn JSON matching this JSON Schema:\n" + MealSchema.canonical
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", system) }
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    for (img in request.images) addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", "data:${img.mimeType};base64,${img.jpeg.base64()}") }
                    }
                    addJsonObject { put("type", "text"); put("text", MealPrompt.userText(request)) }
                }
            }
        }
        putJsonObject("response_format") {
            if (strict) {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "meal_estimate")
                    put("strict", true)
                    put("schema", MealSchema.openAiStrict)
                }
            } else {
                put("type", "json_object")
            }
        }
    }

    private suspend fun post(body: JsonObject): Timed {
        val key = apiKey()
        val t = timedCall {
            http.post("$root/v1/chat/completions") {
                auth(key)
                contentType(ContentType.Application.Json)
                setBody(NetworkJson.encodeToString(JsonObject.serializer(), body))
            }
        }
        checkStatus(t, key)
        return t
    }

    /** Local servers (Ollama, LM Studio) often need no key. */
    private fun HttpRequestBuilder.auth(key: String?) {
        if (!key.isNullOrBlank()) header("Authorization", "Bearer $key")
    }

    private fun checkStatus(t: Timed, key: String?) {
        val code = t.response.status.value
        if (code in 200..299) return
        val err = runCatching { NetworkJson.decodeFromString(ErrorEnvelope.serializer(), t.body).error }.getOrNull()
        val message = err?.message?.withoutKey(key)
        val reply = ProviderReply(code, err?.code ?: err?.type, message)
        throw when {
            code == 401 || code == 403 -> AiError.InvalidKey(reply = reply)
            code == 404 -> AiError.ModelNotFound(model, reply)
            code == 429 && (err?.code == "insufficient_quota" || err?.type == "insufficient_quota") -> AiError.QuotaExhausted(null, reply = reply)
            code == 429 -> AiError.RateLimited(t.response.retryAfterMs(), reply)
            code >= 500 -> AiError.Server(code, reply)
            else -> AiError.InvalidRequest(null, reply)
        }
    }

    @Serializable
    private data class ModelsPage(val data: List<ModelItem> = emptyList())

    @Serializable
    private data class ModelItem(val id: String)

    @Serializable
    private data class ChatResponse(val model: String? = null, val choices: List<Choice> = emptyList(), val usage: Usage? = null)

    @Serializable
    private data class Choice(val message: Message? = null, @SerialName("finish_reason") val finishReason: String? = null)

    @Serializable
    private data class Message(val content: String? = null, val refusal: String? = null)

    @Serializable
    private data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int? = null,
        @SerialName("completion_tokens") val completionTokens: Int? = null,
    )

    @Serializable
    private data class ErrorEnvelope(val error: ErrorBody? = null)

    @Serializable
    private data class ErrorBody(val message: String? = null, val type: String? = null, val code: String? = null)
}
