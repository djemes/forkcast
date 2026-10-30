package app.forkcast.core.network.ai

import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.EstimatedItem
import app.forkcast.core.model.MealEstimateJson
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.time.Instant

class AiChainTest {

    private val now = Instant.parse("2026-10-18T19:30:00Z")

    private fun profile(id: String, order: Int, provider: ProviderId = ProviderId.GEMINI, cooldown: Long? = null, enabled: Boolean = true) =
        AiProfile(id, id, provider, "model-$id", null, TierLabel.FREE, order, enabled, needsAttention = false, cooldownUntil = cooldown)

    private val meal = MealEstimateJson("Toast", listOf(EstimatedItem(name = "Toast", grams = 30.0, gramsLow = 25.0, gramsHigh = 35.0)))
    private fun ok(model: String) = MealEstimateResult(meal, emptyList(), "{}", 10, 5, 100, model)

    private class Scripted(private val answers: ArrayDeque<Result<MealEstimateResult>>) : MealEstimator {
        var calls = 0
        override val providerId = ProviderId.GEMINI
        override suspend fun listModels() = Result.success(emptyList<ModelInfo>())
        override suspend fun testConnection() = Result.success(ConnectionReport("m", 1))
        override suspend fun estimate(request: MealEstimateRequest): Result<MealEstimateResult> {
            calls++
            return answers.removeFirst()
        }
    }

    private fun req(p: AiProfile) = MealEstimateRequest(emptyList(), "toast", "Breakfast", "08:00", "metric", null, null, null, p.model)

    @Test fun firstProfileRateLimitedFallsThroughToNext() = runTest {
        val a = profile("a", 0)
        val b = profile("b", 1)
        val scripts = mapOf(
            "a" to Scripted(ArrayDeque(listOf(Result.failure(AiError.RateLimited(null))))),
            "b" to Scripted(ArrayDeque(listOf(Result.success(ok("model-b"))))),
        )
        val out = AiChain.run(listOf(b, a), now, { scripts.getValue(it.id) }, ::req)
        assertThat(out).isInstanceOf(AiChain.Outcome.Success::class.java)
        out as AiChain.Outcome.Success
        // The review screen names the profile used.
        assertThat(out.profile.id).isEqualTo("b")
        assertThat(out.attempts.first().cooldownUntil).isEqualTo(now.toEpochMilli() + QuotaWindows.SHORT_COOLDOWN_MS)
    }

    @Test fun profilesInCooldownOrDisabledAreSkipped() = runTest {
        val a = profile("a", 0, cooldown = now.toEpochMilli() + 1_000)
        val b = profile("b", 1, enabled = false)
        val c = profile("c", 2)
        val scripts = mapOf("c" to Scripted(ArrayDeque(listOf(Result.success(ok("model-c"))))))
        val out = AiChain.run(listOf(a, b, c), now, { scripts.getValue(it.id) }, ::req) as AiChain.Outcome.Success
        assertThat(out.profile.id).isEqualTo("c")
        assertThat(out.attempts).hasSize(1)
    }

    @Test fun dailyQuotaOnGeminiWaitsUntilMidnightPacific() = runTest {
        val a = profile("a", 0)
        val scripts = mapOf("a" to Scripted(ArrayDeque(listOf(Result.failure(AiError.QuotaExhausted(null))))))
        val out = AiChain.run(listOf(a), now, { scripts.getValue(it.id) }, ::req) as AiChain.Outcome.Failure
        assertThat(out.retryable).isTrue()
        assertThat(out.retryAtMs).isEqualTo(Instant.parse("2026-10-19T07:00:00Z").toEpochMilli())
    }

    @Test fun everyProfileInCooldownReportsEarliestReset() = runTest {
        val a = profile("a", 0, cooldown = now.toEpochMilli() + 5_000)
        val b = profile("b", 1, cooldown = now.toEpochMilli() + 2_000)
        val out = AiChain.run(listOf(a, b), now, { error("not called") }, ::req) as AiChain.Outcome.Failure
        assertThat(out.retryAtMs).isEqualTo(now.toEpochMilli() + 2_000)
    }

    @Test fun invalidKeyMarksProfileAndMovesOn() = runTest {
        val a = profile("a", 0)
        val b = profile("b", 1)
        val scripts = mapOf(
            "a" to Scripted(ArrayDeque(listOf(Result.failure(AiError.InvalidKey())))),
            "b" to Scripted(ArrayDeque(listOf(Result.success(ok("model-b"))))),
        )
        val out = AiChain.run(listOf(a, b), now, { scripts.getValue(it.id) }, ::req) as AiChain.Outcome.Success
        assertThat(out.attempts.first().needsAttention).isTrue()
    }

    @Test fun refusalStopsTheChain() = runTest {
        val a = profile("a", 0)
        val b = Scripted(ArrayDeque(listOf(Result.success(ok("b")))))
        val scripts = mapOf("a" to Scripted(ArrayDeque(listOf(Result.failure(AiError.ContentRefused())))), "b" to b)
        val out = AiChain.run(listOf(a, profile("b", 1)), now, { scripts.getValue(it.id) }, ::req) as AiChain.Outcome.Failure
        assertThat(out.finalError).isInstanceOf(AiError.ContentRefused::class.java)
        assertThat(out.retryable).isFalse()
        assertThat(b.calls).isEqualTo(0)
    }

    @Test fun badResponseRetriesOnceOnSameProfile() = runTest {
        val a = Scripted(ArrayDeque(listOf(Result.failure(AiError.BadResponse("x")), Result.success(ok("model-a")))))
        val out = AiChain.run(listOf(profile("a", 0)), now, { a }, ::req)
        assertThat(out).isInstanceOf(AiChain.Outcome.Success::class.java)
        assertThat(a.calls).isEqualTo(2)
    }

    @Test fun offlineStopsAndStaysRetryable() = runTest {
        val b = Scripted(ArrayDeque(listOf(Result.success(ok("b")))))
        val scripts = mapOf("a" to Scripted(ArrayDeque(listOf(Result.failure(AiError.Network())))), "b" to b)
        val out = AiChain.run(listOf(profile("a", 0), profile("b", 1)), now, { scripts.getValue(it.id) }, ::req) as AiChain.Outcome.Failure
        assertThat(out.retryable).isTrue()
        assertThat(b.calls).isEqualTo(0)
    }

    // ---- Privacy (SPEC §4.6): no body data can reach a provider.

    @Test fun requestTypeCarriesNoBodyData() {
        val fields = MealEstimateRequest::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertThat(fields).containsExactly(
            "images", "description", "mealSlot", "localTime", "unitsHint", "personalContext", "previous", "correction", "model",
            // A progress callback (1.2), never serialized: serializedRequestsHaveOnlyExpectedFields checks the bodies.
            "onSent",
            // The app's language for names (2.0 task 1.6): not personal data.
            "language",
        )
    }

    // ---- Gemini 429s (owner, 2026-10-22): a per-minute limit must never wait until midnight.

    @Test fun onlyPerMinuteProfileGetsShortCooldown() = runTest {
        val a = Scripted(ArrayDeque(listOf(Result.failure(AiError.RateLimited(20_000, quota = "GenerateRequestsPerMinutePerProjectPerModel-FreeTier")))))
        val out = AiChain.run(listOf(profile("a", 0)), now, { a }, ::req) as AiChain.Outcome.Failure
        assertThat(out.retryAtMs).isEqualTo(now.toEpochMilli() + 20_000)
    }

    @Test fun retryNowIgnoresPauseAndOnlyAsksChosenProfile() = runTest {
        val a = Scripted(ArrayDeque(listOf(Result.success(ok("a")))))
        val b = Scripted(ArrayDeque(listOf(Result.success(ok("b")))))
        val paused = profile("a", 0).copy(cooldownUntil = now.toEpochMilli() + 60_000)
        val scripts = mapOf("a" to a, "b" to b)
        val retried = AiChain.run(listOf(paused, profile("b", 1)), now, { scripts.getValue(it.id) }, ::req, ignoreCooldown = true)
        assertThat((retried as AiChain.Outcome.Success).profile.id).isEqualTo("a")
        val only = AiChain.run(listOf(paused, profile("b", 1)), now, { scripts.getValue(it.id) }, ::req, only = "b")
        assertThat((only as AiChain.Outcome.Success).profile.id).isEqualTo("b")
        assertThat(only.attempts.single().durationMs).isAtLeast(0)
    }

    @Test fun serializedRequestsHaveOnlyExpectedFields() {
        val r = MealEstimateRequest(listOf(EncodedImage(byteArrayOf(9))), "toast", "Breakfast", "08:00", "metric", null, null, null, "m")
        val bodies = listOf(
            ClaudeEstimator(AiHttpClientFactory.create("t"), { "k" }, "m").requestBody(r),
            GeminiEstimator(AiHttpClientFactory.create("t"), { "k" }, "m", "low").requestBody(r),
            OpenAiCompatEstimator(AiHttpClientFactory.create("t"), { "k" }, "m", "https://x.test").requestBody(r),
        )
        val forbidden = setOf("weight", "weight_kg", "goal", "age", "sex", "gender", "height", "birth_date", "bmi", "body_fat")
        for (body in bodies) {
            val keys = allKeys(body)
            // Schema property names are fine; no body-data key may appear anywhere.
            assertThat(keys.intersect(forbidden)).isEmpty()
        }
        // The only free text besides the prompt is what the user typed for this meal.
        val text = MealPrompt.userText(r)
        assertThat(text).isEqualTo(
            "Logged as Breakfast at 08:00 (context only; never use it in meal_name). Units the user thinks in: metric.\n" +
                "Write every food and meal name in English.\nDescription: toast\nPersonal context: none",
        )
    }

    private fun allKeys(e: JsonElement): Set<String> = when (e) {
        is JsonObject -> e.keys + e.values.flatMap { allKeys(it) }
        is JsonArray -> e.flatMap { allKeys(it) }.toSet()
        is JsonPrimitive -> emptySet()
    }

    // ---- Schema variants.

    @Test fun claudeSchemaHasNoBoundsAndClosedObjects() {
        val s = MealSchema.claude.toString()
        assertThat(s).doesNotContain("minimum")
        assertThat(s).doesNotContain("maximum")
        assertThat(countObjects(MealSchema.claude)).isEqualTo(Regex("\"additionalProperties\":false").findAll(s).count())
        assertThat(MealSchema.canonical.toString()).contains("\"minimum\":0")
    }

    @Test fun openAiStrictSchemaRequiresEveryProperty() {
        val s = MealSchema.openAiStrict.toString()
        assertThat(s).contains("\"required\":[\"kcal\",\"protein_g\",\"carbs_g\",\"fat_g\",\"fiber_g\",\"sugar_g\",\"sat_fat_g\",\"sodium_mg\"]")
        assertThat(s).contains("\"fiber_g\":{\"type\":[\"number\",\"null\"]}")
    }

    private fun countObjects(e: JsonElement): Int = when (e) {
        is JsonObject -> (if (e["type"] == JsonPrimitive("object")) 1 else 0) + e.values.sumOf { countObjects(it) }
        is JsonArray -> e.sumOf { countObjects(it) }
        is JsonPrimitive -> 0
    }

    @Test fun defaultsLoadWithPricesAndChain() {
        val d = AiDefaults.load()
        assertThat(d.provider(ProviderId.GEMINI).recommendedModel).isEqualTo("gemini-3.8-flash")
        assertThat(d.price("gemini-3.8-flash", java.time.LocalDate.of(2026, 12, 31))).isEqualTo(Price(0.75, 3.75))
        assertThat(d.price("gemini-3.8-flash", java.time.LocalDate.of(2027, 1, 1))).isEqualTo(Price(1.50, 7.50))
        assertThat(d.defaultChain.map { it.model }).containsExactly("gemini-3.8-flash", "gemini-3.5-flash-lite", "claude-haiku-5-5").inOrder()
        assertThat(d.models.getValue("gemini-3.5-flash-lite").thinkingLevel).isEqualTo("minimal")
    }

    @Test fun validatorFixesRangesAndCapsImpossibleCalories() {
        val parsed = MealEstimateParser.validate(
            MealEstimateJson(
                "x",
                listOf(
                    EstimatedItem(name = "Oil", grams = 10.0, gramsLow = 20.0, gramsHigh = 5.0,
                        nutrients = app.forkcast.core.model.EstimatedNutrients(kcal = 500.0)),
                    EstimatedItem(name = "Ghost", grams = 0.0),
                ),
            ),
        )
        val oil = parsed.json.items.single()
        assertThat(oil.gramsLow).isAtMost(oil.grams)
        assertThat(oil.gramsHigh).isAtLeast(oil.grams)
        assertThat(oil.nutrients.kcal).isEqualTo(95.0)
        assertThat(parsed.warnings).hasSize(2)
    }
}
