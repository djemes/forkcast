package app.forkcast.core.network.ai

import app.forkcast.core.model.MealEstimateJson
import app.forkcast.core.model.ProviderId
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Per-provider fixtures (AI_PROVIDERS.md §7): valid, clarifying question, malformed JSON,
 * missing fields, refusal, 401, 429, 500. Fixtures follow the documented response shapes
 * (verified 2026-10-18); swap in live captures once real keys are configured.
 */
class EstimatorsTest {

    private fun fixture(name: String) = javaClass.getResource("/fixtures/ai/$name")!!.readText()

    private val requests = mutableListOf<HttpRequestData>()
    private val now = Instant.parse("2026-10-18T19:30:00Z")

    private fun http(status: HttpStatusCode, body: String, headers: Map<String, String> = emptyMap()) = AiHttpClientFactory.create(
        "Forkcast/test",
        MockEngine { req ->
            requests += req
            respond(body, status, headersOf(*(headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))
        },
    )

    private fun request(model: String) = MealEstimateRequest(
        images = listOf(EncodedImage(byteArrayOf(1, 2, 3))),
        description = "chicken thigh, ~1.5 cups white rice",
        mealSlot = "Lunch",
        localTime = "12:40",
        unitsHint = "US customary",
        personalContext = "I cook with olive oil",
        previous = null,
        correction = null,
        model = model,
    )

    private fun claude(status: HttpStatusCode, file: String, headers: Map<String, String> = emptyMap()) =
        ClaudeEstimator(http(status, fixture(file), headers), { "sk-test" }, "claude-haiku-5-5")

    private fun gemini(status: HttpStatusCode, file: String) =
        GeminiEstimator(http(status, fixture(file)), { "g-test" }, "gemini-3.8-flash", "low", clock = Clock.fixed(now, ZoneOffset.UTC))

    private fun openAi(status: HttpStatusCode, file: String) =
        OpenAiCompatEstimator(http(status, fixture(file)), { "o-test" }, "gpt-test", "https://example.test/v1/")

    private fun estimatorFor(provider: ProviderId, status: HttpStatusCode, case: String): MealEstimator = when (provider) {
        ProviderId.CLAUDE -> claude(status, "claude_$case.json")
        ProviderId.GEMINI -> gemini(status, "gemini_$case.json")
        ProviderId.OPENAI_COMPAT -> openAi(status, "openai_$case.json")
    }

    private fun modelFor(p: ProviderId) = when (p) {
        ProviderId.CLAUDE -> "claude-haiku-5-5"
        ProviderId.GEMINI -> "gemini-3.8-flash"
        ProviderId.OPENAI_COMPAT -> "gpt-test"
    }

    // ---- Contract: the same logical answer parses identically from every provider.

    @Test fun allProvidersParseTheSameAnswer() = runTest {
        val results = ProviderId.entries.map { p ->
            estimatorFor(p, HttpStatusCode.OK, "valid").estimate(request(modelFor(p))).getOrThrow()
        }
        val parsed = results.map { it.parsed }
        assertThat(parsed.distinct()).hasSize(1)
        val meal: MealEstimateJson = parsed.first()
        assertThat(meal.items.map { it.name }).containsExactly("Chicken thigh", "White rice", "Olive oil").inOrder()
        assertThat(meal.items.last().isHiddenIngredient).isTrue()
        assertThat(results.all { it.inputTokens != null && it.outputTokens != null }).isTrue()
    }

    @Test fun clarifyingQuestionIsKept() = runTest {
        for (p in ProviderId.entries) {
            val r = estimatorFor(p, HttpStatusCode.OK, "question").estimate(request(modelFor(p))).getOrThrow()
            assertThat(r.parsed.clarifyingQuestion).isEqualTo("Was the chicken skin-on?")
        }
    }

    @Test fun malformedJsonIsBadResponse() = runTest {
        for (p in ProviderId.entries) {
            val e = estimatorFor(p, HttpStatusCode.OK, "malformed").estimate(request(modelFor(p))).exceptionOrNull()
            assertThat(e).isInstanceOf(AiError.BadResponse::class.java)
        }
    }

    @Test fun missingFieldsNeverCrash() = runTest {
        for (p in ProviderId.entries) {
            val e = estimatorFor(p, HttpStatusCode.OK, "missing_fields").estimate(request(modelFor(p))).exceptionOrNull()
            // Only item has no usable weight → nothing to log → BadResponse, not a crash.
            assertThat(e).isInstanceOf(AiError.BadResponse::class.java)
        }
    }

    @Test fun refusalsAreContentRefused() = runTest {
        for (p in ProviderId.entries) {
            val e = estimatorFor(p, HttpStatusCode.OK, "refusal").estimate(request(modelFor(p))).exceptionOrNull()
            assertThat(e).isInstanceOf(AiError.ContentRefused::class.java)
        }
    }

    @Test fun badKeysAreInvalidKey() = runTest {
        assertThat(claude(HttpStatusCode.Unauthorized, "claude_401.json").estimate(request("m")).exceptionOrNull())
            .isInstanceOf(AiError.InvalidKey::class.java)
        assertThat(gemini(HttpStatusCode.BadRequest, "gemini_401.json").estimate(request("m")).exceptionOrNull())
            .isInstanceOf(AiError.InvalidKey::class.java)
        assertThat(openAi(HttpStatusCode.Unauthorized, "openai_401.json").estimate(request("m")).exceptionOrNull())
            .isInstanceOf(AiError.InvalidKey::class.java)
    }

    @Test fun missingKeyFailsWithoutARequest() = runTest {
        val e = ClaudeEstimator(http(HttpStatusCode.OK, "{}"), { null }, "m").estimate(request("m")).exceptionOrNull()
        assertThat(e).isInstanceOf(AiError.InvalidKey::class.java)
        assertThat(requests).isEmpty()
    }

    @Test fun rateLimitsCarryRetryHints() = runTest {
        val c = claude(HttpStatusCode.TooManyRequests, "claude_429.json", mapOf("retry-after" to "20")).estimate(request("m")).exceptionOrNull()
        assertThat((c as AiError.RateLimited).retryAfterMs).isEqualTo(20_000)
        // 429 is not retried by the client; the chain moves on instead.
        assertThat(requests).hasSize(1)

        val minute = gemini(HttpStatusCode.TooManyRequests, "gemini_429_minute.json").estimate(request("m")).exceptionOrNull()
        assertThat((minute as AiError.RateLimited).retryAfterMs).isEqualTo(12_000)

        val daily = gemini(HttpStatusCode.TooManyRequests, "gemini_429_daily.json").estimate(request("m")).exceptionOrNull()
        // 2026-10-18 12:30 Pacific → resets 2026-10-19 00:00 Pacific (07:00Z, PDT).
        assertThat((daily as AiError.QuotaExhausted).resetsAtMs).isEqualTo(Instant.parse("2026-10-19T07:00:00Z").toEpochMilli())

        assertThat(openAi(HttpStatusCode.TooManyRequests, "openai_429.json").estimate(request("m")).exceptionOrNull())
            .isInstanceOf(AiError.RateLimited::class.java)
    }

    @Test fun serverErrorsAreServer() = runTest {
        assertThat(claude(HttpStatusCode.InternalServerError, "claude_500.json").estimate(request("m")).exceptionOrNull())
            .isInstanceOf(AiError.Server::class.java)
        assertThat(gemini(HttpStatusCode.ServiceUnavailable, "gemini_500.json").estimate(request("m")).exceptionOrNull())
            .isInstanceOf(AiError.Server::class.java)
        assertThat(openAi(HttpStatusCode.InternalServerError, "openai_500.json").estimate(request("m")).exceptionOrNull())
            .isInstanceOf(AiError.Server::class.java)
        // One quick retry on 5xx.
        assertThat(requests.size).isEqualTo(6)
    }

    @Test fun fencedJsonFromLocalServersParses() = runTest {
        val r = openAi(HttpStatusCode.OK, "openai_fenced_valid.json").estimate(request("gpt-test")).getOrThrow()
        assertThat(r.parsed.items).hasSize(3)
    }

    // ---- Request shapes (AI_PROVIDERS.md §2, verified 2026-10-18).

    @Test fun claudeRequestShape() = runTest {
        claude(HttpStatusCode.OK, "claude_valid.json").estimate(request("claude-haiku-5-5"))
        val req = requests.single()
        assertThat(req.url.toString()).isEqualTo("https://api.anthropic.com/v1/messages")
        assertThat(req.headers["x-api-key"]).isEqualTo("sk-test")
        assertThat(req.headers["anthropic-version"]).isEqualTo("2023-06-01")
        val body = (req.body as TextContent).text
        assertThat(body).contains("\"output_config\":{\"effort\":\"low\",\"format\":{\"type\":\"json_schema\"")
        assertThat(body).doesNotContain("tool_choice")
        assertThat(body).doesNotContain("budget_tokens")
        assertThat(body).doesNotContain("temperature")
        // Image block comes before the text block.
        assertThat(body.indexOf("\"type\":\"image\"")).isLessThan(body.indexOf("\"type\":\"text\""))
    }

    @Test fun geminiRequestShape() = runTest {
        gemini(HttpStatusCode.OK, "gemini_valid.json").estimate(request("gemini-3.8-flash"))
        val req = requests.single()
        assertThat(req.url.toString()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/interactions")
        assertThat(req.headers["x-goog-api-key"]).isEqualTo("g-test")
        assertThat(req.url.toString()).doesNotContain("g-test")
        val body = (req.body as TextContent).text
        assertThat(body).contains("\"store\":false")
        assertThat(body).contains("\"thinking_level\":\"low\"")
        assertThat(body).contains("\"mime_type\":\"application/json\"")
    }

    @Test fun geminiCountsThinkingAsOutput() = runTest {
        val r = gemini(HttpStatusCode.OK, "gemini_valid.json").estimate(request("gemini-3.8-flash")).getOrThrow()
        assertThat(r.outputTokens).isEqualTo(610 + 420)
    }

    @Test fun openAiBaseUrlVariantsAndOptionalKey() = runTest {
        val noKey = OpenAiCompatEstimator(http(HttpStatusCode.OK, fixture("openai_valid.json")), { null }, "llava", "http://ollama.lan:11434")
        noKey.estimate(request("llava")).getOrThrow()
        assertThat(requests.single().url.toString()).isEqualTo("http://ollama.lan:11434/v1/chat/completions")
        assertThat(requests.single().headers["Authorization"]).isNull()
    }

    @Test fun modelLists() = runTest {
        val c = claude(HttpStatusCode.OK, "claude_models.json").listModels().getOrThrow()
        assertThat(c.map { it.id }).containsExactly("claude-sonnet-5-5", "claude-haiku-5-5")
        assertThat(c.all { it.supportsImages == true }).isTrue()
        val g = gemini(HttpStatusCode.OK, "gemini_models.json").listModels().getOrThrow()
        assertThat(g.map { it.id }).containsExactly("gemini-3.8-flash", "gemini-3.5-flash-lite")
    }

    @Test fun correctionResendsPreviousResult() {
        val previous = MealEstimateJson(mealName = "Chicken and rice")
        val text = MealPrompt.userText(request("m").copy(previous = previous, correction = "only ate half"))
        assertThat(text).contains("Previous estimate:")
        assertThat(text).contains("\"meal_name\":\"Chicken and rice\"")
        assertThat(text).endsWith("User correction: only ate half. Return the full updated result.")
    }
}
