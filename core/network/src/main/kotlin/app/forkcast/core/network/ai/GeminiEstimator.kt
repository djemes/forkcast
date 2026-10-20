package app.forkcast.core.network.ai

import app.forkcast.core.model.ProviderId
import app.forkcast.core.network.NetworkJson
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Clock

/**
 * Gemini Interactions API (AI_PROVIDERS.md §2.2, owner's choice 2026-10-18). `store: false`
 * on every call: Forkcast keeps no server-side conversation.
 */
class GeminiEstimator(
    private val http: HttpClient,
    private val apiKey: suspend () -> String?,
    private val model: String,
    /** Lowest level per model comes from the defaults file; null = provider default. */
    private val thinkingLevel: String?,
    private val maxOutputTokens: Int = 4096,
    private val clock: Clock = Clock.systemUTC(),
    private val baseUrl: String = BASE,
) : MealEstimator {
    override val providerId = ProviderId.GEMINI

    override suspend fun listModels(): Result<List<ModelInfo>> = aiResult {
        val key = key()
        val out = mutableListOf<ModelInfo>()
        var pageToken: String? = null
        do {
            val t = timedCall {
                http.get("$baseUrl/v1beta/models") {
                    auth(key)
                    parameter("pageSize", 1000)
                    pageToken?.let { parameter("pageToken", it) }
                }
            }
            checkStatus(t, key)
            val page = NetworkJson.decodeFromString(ModelsPage.serializer(), t.body)
            out += page.models
                .filter { m -> m.name.startsWith("models/gemini") && EXCLUDED.none { m.name.contains(it) } }
                .map { ModelInfo(it.name.removePrefix("models/"), it.displayName ?: it.name.removePrefix("models/"), supportsImages = null) }
            pageToken = page.nextPageToken?.takeIf { it.isNotBlank() }
        } while (pageToken != null)
        out
    }

    override suspend fun testConnection(): Result<ConnectionReport> = aiResult {
        val body = buildJsonObject {
            put("model", model)
            put("input", MealPrompt.TEST_TEXT)
            putJsonObject("generation_config") {
                thinkingLevel?.let { put("thinking_level", it) }
                put("max_output_tokens", 256)
            }
            put("store", false)
        }
        val t = post(body)
        val r = NetworkJson.decodeFromString(Interaction.serializer(), t.body)
        ConnectionReport(r.model ?: model, t.latencyMs)
    }

    override suspend fun estimate(request: MealEstimateRequest): Result<MealEstimateResult> = aiResult {
        val t = post(requestBody(request))
        val r = NetworkJson.decodeFromString(Interaction.serializer(), t.body)
        if (r.status == "failed" || r.status == "cancelled") throw blockedOrBad(t.body)
        // Before reading the text: an answer cut off before any text still says "cut off".
        if (r.status == "incomplete") throw AiError.BadResponse(t.body, "the answer was cut off")
        // steps[].content[].text on the last model_output step (confirmed by live estimates, 2026-10-19).
        val text = r.steps.lastOrNull { it.type == "model_output" }?.content?.firstOrNull { it.type == "text" }?.text
            ?: throw blockedOrBad(t.body)
        val parsed = MealEstimateParser.parse(text)
        val output = (r.usage?.totalOutputTokens ?: 0) + (r.usage?.totalThoughtTokens ?: 0)
        MealEstimateResult(parsed.json, parsed.warnings, text, r.usage?.totalInputTokens, output.takeIf { r.usage != null }, t.latencyMs, r.model ?: model)
    }

    /** Visible for the privacy test (no body data in requests). */
    fun requestBody(request: MealEstimateRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("system_instruction", MealPrompt.system)
        putJsonArray("input") {
            addJsonObject { put("type", "text"); put("text", MealPrompt.userText(request)) }
            for (img in request.images) addJsonObject {
                put("type", "image")
                put("data", img.jpeg.base64())
                put("mime_type", img.mimeType)
            }
        }
        putJsonObject("response_format") {
            put("type", "text")
            put("mime_type", "application/json")
            put("schema", MealSchema.canonical)
        }
        putJsonObject("generation_config") {
            thinkingLevel?.let { put("thinking_level", it) }
            put("max_output_tokens", maxOutputTokens)
        }
        put("store", false)
    }

    private suspend fun post(body: JsonObject): Timed {
        val key = key()
        val t = timedCall {
            http.post("$baseUrl/v1beta/interactions") {
                auth(key)
                contentType(ContentType.Application.Json)
                setBody(NetworkJson.encodeToString(JsonObject.serializer(), body))
            }
        }
        checkStatus(t, key)
        return t
    }

    private suspend fun key(): String = apiKey()?.takeIf { it.isNotBlank() } ?: throw AiError.InvalidKey(AiError.NO_KEY)

    /**
     * Every Gemini call sends the key in the `x-goog-api-key` header, never as `?key=` and never
     * as a Bearer token, so both key formats work: classic `AIza…` keys and the `AQ.…` keys AI
     * Studio issues since mid-2026. The key is sent exactly as saved; there is no format check.
     */
    private fun HttpRequestBuilder.auth(key: String) = header("x-goog-api-key", key)

    private fun blockedOrBad(body: String): AiError =
        if (body.contains("SAFETY") || body.contains("PROHIBITED_CONTENT") || body.contains("blocked", ignoreCase = true)) {
            AiError.ContentRefused()
        } else {
            AiError.BadResponse(body, "no text in reply")
        }

    private fun checkStatus(t: Timed, key: String) {
        val code = t.response.status.value
        if (code in 200..299) return
        val err = parseError(t.body)
        val status = err?.status
        val message = err?.message?.withoutKey(key)
        val reason = err?.reason()
        val reply = ProviderReply(code, reason ?: status, message)
        throw when {
            code == 429 || status == "RESOURCE_EXHAUSTED" -> rateLimit(err, reply)
            reason in KEY_REASONS || reason?.startsWith("API_KEY_") == true -> AiError.InvalidKey(reply = reply)
            code == 401 || code == 403 || status == "PERMISSION_DENIED" || status == "UNAUTHENTICATED" -> AiError.InvalidKey(reply = reply)
            message?.contains("API key", ignoreCase = true) == true -> AiError.InvalidKey(reply = reply)
            code == 404 || status == "NOT_FOUND" -> AiError.ModelNotFound(model, reply)
            code >= 500 -> AiError.Server(code, reply)
            else -> AiError.InvalidRequest(null, reply)
        }
    }

    /** Google's machine-readable cause: `reason` of the `google.rpc.ErrorInfo` detail. */
    private fun ErrorBody.reason(): String? = details.orEmpty()
        .firstOrNull { it["@type"]?.jsonPrimitive?.contentOrNull?.endsWith("google.rpc.ErrorInfo") == true }
        ?.get("reason")?.jsonPrimitive?.contentOrNull

    /**
     * Per-day quotas ("...PerDay..." quota ids) reset at midnight Pacific; anything else is a
     * short window with the server's RetryInfo delay (AI_PROVIDERS.md §2.5–2.6).
     */
    private fun rateLimit(err: ErrorBody?, reply: ProviderReply): AiError {
        val details = err?.details.orEmpty()
        val daily = details.any { d ->
            (d["violations"] as? JsonArray)?.any { v -> v.jsonObject["quotaId"]?.jsonPrimitive?.contentOrNull?.contains("PerDay") == true } == true
        }
        if (daily) return AiError.QuotaExhausted(QuotaWindows.nextMidnightPacific(clock.instant()).toEpochMilli(), reply = reply)
        val delay = details.firstNotNullOfOrNull { it["retryDelay"]?.jsonPrimitive?.contentOrNull }
        return AiError.RateLimited(delay?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong() }, reply)
    }

    @Serializable
    private data class ModelsPage(val models: List<ModelItem> = emptyList(), val nextPageToken: String? = null)

    @Serializable
    private data class ModelItem(val name: String, val displayName: String? = null)

    @Serializable
    private data class Interaction(
        val model: String? = null,
        val status: String? = null,
        val steps: List<Step> = emptyList(),
        val usage: Usage? = null,
    )

    @Serializable
    private data class Step(val type: String? = null, val content: List<Part>? = null)

    @Serializable
    private data class Part(val type: String? = null, val text: String? = null)

    @Serializable
    private data class Usage(
        @SerialName("total_input_tokens") val totalInputTokens: Int? = null,
        @SerialName("total_output_tokens") val totalOutputTokens: Int? = null,
        @SerialName("total_thought_tokens") val totalThoughtTokens: Int? = null,
    )

    @Serializable
    private data class ErrorEnvelope(val error: ErrorBody? = null)

    /**
     * Google sends the error as an object (models list) or, from the Interactions endpoint, as a
     * one-element array: `[{"error":{...}}]` (seen live 2026-10-19). Both must parse, or the
     * ErrorInfo reason is lost.
     */
    private fun parseError(body: String): ErrorBody? = runCatching {
        val root = NetworkJson.parseToJsonElement(body)
        val envelope = if (root is kotlinx.serialization.json.JsonArray) root.firstOrNull() ?: return null else root
        NetworkJson.decodeFromJsonElement(ErrorEnvelope.serializer(), envelope).error
    }.getOrNull()

    @Serializable
    private data class ErrorBody(val code: Int? = null, val message: String? = null, val status: String? = null, val details: List<JsonObject>? = null)

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com"
        /** Non-chat model families the picker hides. */
        private val EXCLUDED = listOf("embedding", "tts", "-image", "live", "audio", "aqa", "robotics", "computer-use")

        /**
         * `ErrorInfo.reason` values that mean "this key can't be used here", whatever the HTTP
         * status: an invalid key, a key type the endpoint refuses (`ACCESS_TOKEN_TYPE_UNSUPPORTED`,
         * reported for some `AQ.` keys), the API not enabled on the key's project, or API
         * restrictions on the key (`API_KEY_*_BLOCKED`).
         */
        private val KEY_REASONS = setOf("API_KEY_INVALID", "ACCESS_TOKEN_TYPE_UNSUPPORTED", "SERVICE_DISABLED", "CONSUMER_SUSPENDED")
    }
}
