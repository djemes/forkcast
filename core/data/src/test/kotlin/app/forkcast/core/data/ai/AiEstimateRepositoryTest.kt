package app.forkcast.core.data.ai

import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.ComponentSource
import app.forkcast.core.data.db.EstimateStatus
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.MealSlotEntity
import app.forkcast.core.data.repository.AiProfileDraft
import app.forkcast.core.data.repository.AiProfileStatus
import app.forkcast.core.data.repository.DeletedAiProfile
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.DataStoreSettingsRepository
import app.forkcast.core.data.repository.DefaultFoodRepository
import app.forkcast.core.data.repository.MonthUsage
import app.forkcast.core.data.repository.RoomLogRepository
import app.forkcast.core.data.repository.RoomMealSlotRepository
import app.forkcast.core.data.settings.AppSettingsSerializer
import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.EstimatedItem
import app.forkcast.core.model.EstimatedNutrients
import app.forkcast.core.model.MealEstimateJson
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.Food
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.model.kcal
import app.forkcast.core.network.HttpClientFactory
import app.forkcast.core.network.OffClient
import app.forkcast.core.network.UsdaClient
import app.forkcast.core.network.ai.AiChain
import app.forkcast.core.network.ai.AiDefaults
import app.forkcast.core.network.ai.AiError
import app.forkcast.core.network.ai.ConnectionReport
import app.forkcast.core.network.ai.MealEstimateRequest
import app.forkcast.core.network.ai.MealEstimateResult
import app.forkcast.core.network.ai.MealEstimator
import app.forkcast.core.network.ai.ModelInfo
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class AiEstimateRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java).allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-18T16:40:00Z"), ZoneId.of("America/New_York"))
    private val day = LocalDate.of(2026, 10, 18)
    private val settingsFile = File(context.filesDir, "settings-test-${System.nanoTime()}.json")
    private val settings = DataStoreSettingsRepository(DataStoreFactory.create(AppSettingsSerializer) { settingsFile })
    private val log = RoomLogRepository(db, db.logEntryDao(), db.foodDao(), clock)

    /** USDA answers every search with the SR "rice" fixture, or fails when [usdaDown]. */
    private var usdaDown = true
    private val usda = UsdaClient(
        HttpClientFactory.create("t", MockEngine { _ ->
            if (usdaDown) respond("{}", HttpStatusCode.ServiceUnavailable)
            else respond(RICE_SEARCH, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }),
    ) { "k" }
    private val foods = DefaultFoodRepository(db.foodDao(), usda, OffClient(HttpClientFactory.create("t", MockEngine { respond("{}") })), clock)
    private val ai = ScriptedAi()
    private val scheduled = mutableListOf<Pair<String, Long>>()
    /** Estimates WorkManager would report as queued or running. */
    private val live = mutableSetOf<String>()
    private val scheduler = object : EstimateScheduler {
        override fun enqueue(estimateId: String, delayMs: Long) { scheduled += estimateId to delayMs; live += estimateId }
        override fun cancel(estimateId: String) { live -= estimateId }
        override suspend fun isScheduled(estimateId: String) = estimateId in live
    }
    private val photos = PhotoStore(context)
    private val repo = DefaultAiEstimateRepository(
        context, db.aiDao(), ai, photos, ComponentMatcher(usda, foods, db.aiDao(), clock), log,
        RoomMealSlotRepository(db, db.mealSlotDao(), clock, context), settings, scheduler, clock, foods,
    )

    @After fun tearDown() {
        db.close()
        settingsFile.delete()
    }

    private suspend fun lunch(): String {
        db.mealSlotDao().upsert(listOf(MealSlotEntity("lunch", "Lunch", 1, 660, 900, 1, 1, null)))
        return "lunch"
    }

    private fun jpeg(): Uri {
        val f = File(context.cacheDir, "meal-${System.nanoTime()}.jpg")
        f.outputStream().use { Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Uri.fromFile(f)
    }

    private val meal = MealEstimateJson(
        mealName = "Chicken and rice",
        items = listOf(
            EstimatedItem("Chicken thigh", "Chicken, thigh, roasted, meat only", "roasted", 120.0, 95.0, 150.0, 0.7, false, EstimatedNutrients(250.0, 30.0, 0.0, 14.0)),
            EstimatedItem("White rice", "Rice, white, long-grain, cooked", "boiled", 200.0, 160.0, 250.0, 0.8, false, EstimatedNutrients(260.0, 5.4, 56.0, 0.6)),
            EstimatedItem("Olive oil", "Oil, olive, salad or cooking", "unknown", 7.0, 3.0, 14.0, 0.4, true, EstimatedNutrients(62.0, 0.0, 0.0, 7.0)),
        ),
        overallConfidence = 0.6,
    )

    private fun result(json: MealEstimateJson = meal) = Result.success(MealEstimateResult(json, emptyList(), "{}", 3000, 600, 900, "gemini-3.8-flash"))

    @Test fun pendingEstimateRunsAndAutoAcceptsIntoTheMeal() = runTest {
        val slot = lunch()
        ai.add("g", ProviderId.GEMINI, result())
        val id = repo.create(day, slot, listOf(jpeg()), "chicken thigh and rice")
        val pending = db.aiDao().estimate(id)!!
        assertThat(pending.status).isEqualTo(EstimateStatus.PENDING)
        assertThat(pending.photoPaths).hasSize(1)
        assertThat(pending.localTime).isEqualTo("12:40")
        assertThat(scheduled.single().first).isEqualTo(id)
        // Stored photo is a fresh JPEG (no EXIF), already in app storage.
        assertThat(photos.file(pending.photoPaths.single()).exists()).isTrue()

        val outcome = repo.run(id)
        assertThat(outcome).isEqualTo(RunOutcome.Finished(id, notify = true))
        val done = db.aiDao().estimate(id)!!
        assertThat(done.status).isEqualTo(EstimateStatus.DONE)
        assertThat(done.profileName).isEqualTo("g")
        assertThat(done.kcalExpected).isWithin(0.5).of(572.0)
        assertThat(done.kcalLow!!).isLessThan(done.kcalExpected!!)
        assertThat(done.kcalHigh!!).isGreaterThan(done.kcalExpected!!)

        val entry = log.observeDay(day).first().single()
        assertThat(entry.aiEstimateId).isEqualTo(id)
        assertThat(entry.displayName).isEqualTo("Chicken and rice")
        assertThat(entry.mealSlotId).isEqualTo(slot)
        assertThat(entry.nutrients.kcal).isWithin(0.5).of(572.0)
        // Usage is recorded through the AI repository.
        assertThat(ai.recorded.single().result).isNotNull()
        // The request carried only meal info.
        assertThat(ai.lastRequest!!.mealSlot).isEqualTo("Lunch")
        assertThat(ai.lastRequest!!.description).isEqualTo("chicken thigh and rice")
    }

    @Test fun firstProfileRateLimitedSecondProfileProducesEstimate() = runTest {
        val slot = lunch()
        ai.add("free", ProviderId.GEMINI, Result.failure<MealEstimateResult>(AiError.QuotaExhausted(null)))
        ai.add("paid", ProviderId.CLAUDE, result())
        val id = repo.create(day, slot, listOf(jpeg()), null)
        repo.run(id)
        assertThat(db.aiDao().estimate(id)!!.profileName).isEqualTo("paid")
        assertThat(ai.recorded.first().cooldownUntil).isNotNull()
    }

    @Test fun offlineStaysPendingAndRetries() = runTest {
        val slot = lunch()
        ai.add("g", ProviderId.GEMINI, Result.failure<MealEstimateResult>(AiError.Network()))
        val id = repo.create(day, slot, listOf(jpeg()), null)
        assertThat(repo.run(id)).isEqualTo(RunOutcome.RetryWhenOnline)
        val e = db.aiDao().estimate(id)!!
        assertThat(e.status).isEqualTo(EstimateStatus.PENDING)
        assertThat(e.errorCode).isEqualTo("Network")
        assertThat(log.observeDay(day).first()).isEmpty()
    }

    @Test fun everyProfileOutOfQuotaWaitsForReset() = runTest {
        val slot = lunch()
        ai.add("g", ProviderId.GEMINI, Result.failure<MealEstimateResult>(AiError.QuotaExhausted(Instant.parse("2026-10-19T07:00:00Z").toEpochMilli())))
        val id = repo.create(day, slot, listOf(jpeg()), null)
        val outcome = repo.run(id) as RunOutcome.RetryAt
        assertThat(outcome.atMs).isEqualTo(Instant.parse("2026-10-19T07:00:00Z").toEpochMilli())
        assertThat(db.aiDao().estimate(id)!!.status).isEqualTo(EstimateStatus.PENDING)
    }

    @Test fun refusalFailsWithSpecificCode() = runTest {
        val slot = lunch()
        ai.add("g", ProviderId.GEMINI, Result.failure<MealEstimateResult>(AiError.ContentRefused()))
        val id = repo.create(day, slot, listOf(jpeg()), null)
        assertThat(repo.run(id)).isInstanceOf(RunOutcome.Failed::class.java)
        assertThat(db.aiDao().estimate(id)!!.errorCode).isEqualTo("ContentRefused")
        // The failure names the profile that gave it ("Gemini declined…").
        assertThat(db.aiDao().estimate(id)!!.provider).isEqualTo(ProviderId.GEMINI)
    }

    @Test fun noProfileFails() = runTest {
        val slot = lunch()
        val id = repo.create(day, slot, listOf(jpeg()), null)
        repo.run(id)
        assertThat(db.aiDao().estimate(id)!!.errorCode).isEqualTo(AiErrorCodes.NO_PROFILE)
    }

    @Test fun clarifyingQuestionWaitsThenAcceptAsIsLogs() = runTest {
        val slot = lunch()
        ai.add("g", ProviderId.GEMINI, result(meal.copy(clarifyingQuestion = "Skin on?")))
        val id = repo.create(day, slot, listOf(jpeg()), null)
        repo.run(id)
        assertThat(db.aiDao().estimate(id)!!.status).isEqualTo(EstimateStatus.NEEDS_INPUT)
        assertThat(log.observeDay(day).first()).isEmpty()
        repo.acceptAsIs(id)
        assertThat(log.observeDay(day).first()).hasSize(1)
    }

    @Test fun correctionRerunsAndUpdatesTheSameEntry() = runTest {
        val slot = lunch()
        ai.add("g", ProviderId.GEMINI, result(), result(meal.copy(items = meal.items.take(2))))
        val id = repo.create(day, slot, listOf(jpeg()), null)
        repo.run(id)
        repo.correct(id, "no oil")
        assertThat(db.aiDao().estimate(id)!!.status).isEqualTo(EstimateStatus.PENDING)
        repo.run(id)
        assertThat(ai.lastRequest!!.correction).isEqualTo("no oil")
        assertThat(ai.lastRequest!!.previous!!.items).hasSize(3)
        val entries = log.observeDay(day).first()
        assertThat(entries).hasSize(1)
        assertThat(entries.single().nutrients.kcal).isWithin(0.5).of(510.0)
    }

    @Test fun reviewEditsGramsAndSourceThenSaveClearsBadge() = runTest {
        val slot = lunch()
        usdaDown = false
        ai.add("g", ProviderId.GEMINI, result())
        val id = repo.create(day, slot, listOf(jpeg()), null)
        repo.run(id)
        val rice = db.aiDao().components(id).first { it.name == "White rice" }
        assertThat(rice.useSource).isEqualTo(ComponentSource.USDA)
        assertThat(rice.matchedName).startsWith("Rice, white")
        // USDA rice is 130 kcal/100 g.
        assertThat(rice.nutrientsUsed.kcal).isWithin(1.0).of(260.0)

        // Edits are a draft until Save: nothing stored changes on the way.
        var draft = db.aiDao().components(id).map { if (it.id == rice.id) ComponentEdits.withGrams(it, 100.0) else it }
        assertThat(draft.first { it.id == rice.id }.nutrientsUsed.kcal).isWithin(1.0).of(130.0)
        draft = draft.map { if (it.id == rice.id) ComponentEdits.withSource(it, ComponentSource.AI) else it }
        assertThat(draft.first { it.id == rice.id }.nutrientsUsed.kcal).isWithin(1.0).of(130.0)
        assertThat(draft.first { it.id == rice.id }.nutrientsUsed[NutrientKey.CARBS_G]).isWithin(0.5).of(28.0)
        assertThat(db.aiDao().components(id).first { it.id == rice.id }.grams).isEqualTo(200.0)

        repo.saveReview(id, draft, 1.0)
        assertThat(db.aiDao().components(id).first { it.id == rice.id }.grams).isEqualTo(100.0)
        val e = db.aiDao().estimate(id)!!
        assertThat(e.reviewedAt).isNotNull()
        assertThat(log.observeDay(day).first().single().nutrients.kcal).isWithin(1.0).of(e.kcalExpected!!)
        // Match cached for next time.
        assertThat(db.aiDao().match("rice white long grain cooked | boiled")).isNotNull()
    }

    /** "Change match": a picked food replaces the numbers now and is used for the same food next time. */
    @Test fun changeMatchUsesThePickedFoodAndRemembersIt() = runTest {
        val slot = lunch()
        ai.add("g", ProviderId.GEMINI, result())
        val id = repo.create(day, slot, listOf(jpeg()), null)
        repo.run(id)
        val chicken = db.aiDao().components(id).first { it.name == "Chicken thigh" }
        assertThat(chicken.useSource).isEqualTo(ComponentSource.AI)

        val picked = Food("", "Chicken, thigh, skinless, grilled", null, FoodSource.USDA, "999", null, emptyList(),
            mapOf(NutrientKey.ENERGY_KCAL to 180.0, NutrientKey.PROTEIN_G to 26.0), false, null, null, null)
        val local = repo.localFood(picked)
        val draft = db.aiDao().components(id).map { if (it.id == chicken.id) ComponentEdits.withMatch(it, local) else it }
        repo.saveReview(id, draft, 1.0)

        val after = db.aiDao().components(id).first { it.id == chicken.id }
        assertThat(after.useSource).isEqualTo(ComponentSource.USDA)
        assertThat(after.matchedName).isEqualTo("Chicken, thigh, skinless, grilled")
        assertThat(after.nutrientsUsed.kcal).isWithin(0.1).of(216.0) // 120 g × 180/100
        assertThat(db.aiDao().estimate(id)!!.kcalExpected!!).isWithin(1.0).of(216.0 + 260.0 + 62.0)
        val cached = db.aiDao().match("chicken thigh roasted meat only | roasted")!!
        assertThat(cached.score).isEqualTo(ComponentMatcher.USER_CHOICE_SCORE)
        assertThat(foods.get(cached.foodId!!)!!.name).isEqualTo("Chicken, thigh, skinless, grilled")
    }

    @Test fun autoAcceptOffWaitsForSave() = runTest {
        settings.updateAi { it.copy(autoAccept = false) }
        val slot = lunch()
        ai.add("g", ProviderId.GEMINI, result())
        val id = repo.create(day, slot, listOf(jpeg()), null)
        repo.run(id)
        assertThat(log.observeDay(day).first()).isEmpty()
        repo.saveReview(id, db.aiDao().components(id), 1.0)
        assertThat(log.observeDay(day).first()).hasSize(1)
    }

    @Test fun deleteRemovesEntryAndPhotos() = runTest {
        val slot = lunch()
        ai.add("g", ProviderId.GEMINI, result())
        val id = repo.create(day, slot, listOf(jpeg()), null)
        val path = db.aiDao().estimate(id)!!.photoPaths.single()
        repo.run(id)
        repo.delete(id)
        assertThat(log.observeDay(day).first()).isEmpty()
        assertThat(photos.file(path).exists()).isFalse()
        assertThat(repo.observeDetail(id).first()).isNull()
    }


    /** Bug (owner, 2026-10-23): sparkling water from a photo meal didn't count toward the day's water. */
    @Test fun drinksInAnEstimateCountTowardWater() = runTest {
        val slot = lunch()
        val withWater = meal.copy(items = meal.items + EstimatedItem("Sparkling water", "Water, carbonated", "unknown", 355.0, 330.0, 500.0, 0.9, false, EstimatedNutrients(0.0, 0.0, 0.0, 0.0)))
        ai.add("g", ProviderId.GEMINI, result(withWater))
        val id = repo.create(day, slot, listOf(jpeg()), "chicken, rice and a sparkling water")
        repo.run(id)
        assertThat(log.observeDay(day).first().single().waterMl).isWithin(0.1).of(355.0)
    }

    /** "How much did you eat?": the entry is the plate × the portion, without asking the AI. */
    @Test fun portionScalesTheEntryLocally() = runTest {
        val slot = lunch()
        ai.add("g", ProviderId.GEMINI, result())
        val id = repo.create(day, slot, listOf(jpeg()), null)
        repo.run(id)
        repo.saveReview(id, db.aiDao().components(id), 0.5)
        val entry = log.observeDay(day).first().single()
        assertThat(entry.nutrients.kcal).isWithin(0.5).of(286.0)
        assertThat(entry.quantity).isEqualTo(0.5)
        assertThat(db.aiDao().estimate(id)!!.kcalExpected!!).isWithin(0.5).of(286.0)
        // The plate itself is unchanged, so "All" brings it back.
        assertThat(db.aiDao().components(id).sumOf { it.grams }).isEqualTo(327.0)
        assertThat(ai.requests).hasSize(1)
    }

    /** One profile that times out: tried again with backoff, then Failed with every try in Details. */
    @Test fun timeoutsRetryWithBackoffThenFail() = runTest {
        val slot = lunch()
        val timeout = Result.failure<MealEstimateResult>(AiError.Timeout())
        ai.add("g", ProviderId.GEMINI, timeout, timeout, timeout)
        val id = repo.create(day, slot, listOf(jpeg()), null)
        val first = repo.run(id) as RunOutcome.RetryAt
        assertThat(first.atMs - clock.millis()).isEqualTo(30_000L)
        var e = db.aiDao().estimate(id)!!
        assertThat(e.status).isEqualTo(EstimateStatus.PENDING)
        assertThat(e.errorCode).isEqualTo("Timeout")
        assertThat(e.retryAfter).isEqualTo(first.atMs)
        assertThat((repo.run(id) as RunOutcome.RetryAt).atMs - clock.millis()).isEqualTo(120_000L)
        assertThat(repo.run(id)).isInstanceOf(RunOutcome.Failed::class.java)
        e = db.aiDao().estimate(id)!!
        assertThat(e.status).isEqualTo(EstimateStatus.FAILED)
        assertThat(e.attemptLog.map { it.result }).containsExactly("Timeout", "Timeout", "Timeout")
        assertThat(e.attemptLog.first().profileName).isEqualTo("g")
    }

    /** A per-minute 429 waits Google's delay, never until midnight. */
    @Test fun perMinuteLimitWaitsShort() = runTest {
        val slot = lunch()
        ai.add("g", ProviderId.GEMINI, Result.failure<MealEstimateResult>(AiError.RateLimited(37_000, quota = "GenerateRequestsPerMinutePerProjectPerModel-FreeTier")))
        val id = repo.create(day, slot, listOf(jpeg()), null)
        val out = repo.run(id) as RunOutcome.RetryAt
        assertThat(out.atMs - clock.millis()).isEqualTo(37_000L)
        val e = db.aiDao().estimate(id)!!
        assertThat(e.attemptLog.single().quota).isEqualTo("GenerateRequestsPerMinutePerProjectPerModel-FreeTier")
        assertThat(ai.recorded.single().cooldownUntil!! - clock.millis()).isEqualTo(37_000L)
    }

    @Test fun retryWithAnotherProfileAsksOnlyThatOne() = runTest {
        val slot = lunch()
        ai.add("a", ProviderId.GEMINI, Result.failure<MealEstimateResult>(AiError.ContentRefused()))
        ai.add("b", ProviderId.GEMINI, result())
        val id = repo.create(day, slot, listOf(jpeg()), null)
        assertThat(repo.run(id)).isInstanceOf(RunOutcome.Failed::class.java)
        repo.retryWith(id, "b")
        assertThat(db.aiDao().estimate(id)!!.status).isEqualTo(EstimateStatus.PENDING)
        repo.run(id)
        assertThat(ai.requests).containsExactly("a", "b").inOrder()
        assertThat(db.aiDao().estimate(id)!!.profileName).isEqualTo("b")
    }

    /** An estimate stuck by an older version (no run start, retry hours away) becomes Failed with Retry. */
    @Test fun recoverStopsEstimatesStuckInOldVersions() = runTest {
        val slot = lunch()
        val id = repo.create(day, slot, listOf(jpeg()), null)
        val stuck = db.aiDao().estimate(id)!!.copy(runStartedAt = null, createdAt = clock.millis() - 3 * 60 * 60_000L, errorCode = "Timeout")
        db.aiDao().upsertEstimate(stuck)
        repo.recover()
        val e = db.aiDao().estimate(id)!!
        assertThat(e.status).isEqualTo(EstimateStatus.FAILED)
        assertThat(e.errorCode).isEqualTo("Timeout")
        assertThat(id in live).isFalse()
        // Retry works on it like on any other.
        ai.add("g", ProviderId.GEMINI, result())
        repo.retry(id)
        repo.run(id)
        assertThat(db.aiDao().estimate(id)!!.status).isEqualTo(EstimateStatus.DONE)
    }

    /** Killed mid-run: RUNNING with no job → queued again. */
    @Test fun recoverRequeuesKilledRuns() = runTest {
        val slot = lunch()
        val id = repo.create(day, slot, listOf(jpeg()), null)
        db.aiDao().upsertEstimate(db.aiDao().estimate(id)!!.copy(status = EstimateStatus.RUNNING))
        live.clear()
        scheduled.clear()
        repo.recover()
        assertThat(db.aiDao().estimate(id)!!.status).isEqualTo(EstimateStatus.PENDING)
        assertThat(scheduled.single()).isEqualTo(id to 0L)
    }

    @Test fun describedMealIsTextOnlyAndSavesAsFood() = runTest {
        val slot = lunch()
        val pizza = MealEstimateJson("Costco cheese pizza", listOf(
            EstimatedItem("Cheese pizza slice", "Pizza, cheese, regular crust", "baked", 400.0, 360.0, 440.0, 0.8, false, EstimatedNutrients(1140.0, 56.0, 120.0, 46.0)),
        ), 0.8, listOf("Costco's published nutrition: 570 kcal per slice"))
        ai.add("g", ProviderId.GEMINI, result(pizza))
        val id = repo.createDescribed(day, slot, "2 slices of Costco cheese pizza")
        repo.run(id)
        assertThat(ai.lastRequest!!.images).isEmpty()
        assertThat(ai.lastRequest!!.isTextOnly).isTrue()
        val entry = log.observeDay(day).first().single()
        assertThat(entry.source).isEqualTo(FoodSource.AI_TEXT)
        val foodId = repo.saveAsFood(id, "Costco cheese pizza")!!
        val food = foods.get(foodId)!!
        assertThat(food.source).isEqualTo(FoodSource.AI_TEXT)
        assertThat(food.nutrientsPer100g.kcal).isWithin(0.1).of(285.0)
        assertThat(food.describedAs).isEqualTo("2 slices of Costco cheese pizza")
        // Found again offline by something close to what was described.
        assertThat(foods.searchLocal("costco pizza slices").first().id).isEqualTo(foodId)
        assertThat(log.observeDay(day).first().single().foodId).isEqualTo(foodId)
        // Saving the review again updates the same food.
        assertThat(repo.saveAsFood(id, "Costco pizza, 2 slices")).isEqualTo(foodId)
        assertThat(foods.get(foodId)!!.name).isEqualTo("Costco pizza, 2 slices")
    }

    /** Profiles with scripted answers, in order. */
    private class ScriptedAi : AiRepository {
        private val list = mutableListOf<AiProfile>()
        private val answers = mutableMapOf<String, ArrayDeque<Result<MealEstimateResult>>>()
        val recorded = mutableListOf<AiChain.Attempt>()
        var lastRequest: MealEstimateRequest? = null
        val requests = mutableListOf<String>()

        fun add(name: String, provider: ProviderId, vararg results: Any) {
            list += AiProfile(name, name, provider, "m-$name", null, TierLabel.FREE, list.size, true, false, null)
            @Suppress("UNCHECKED_CAST")
            answers[name] = ArrayDeque(results.map { it as Result<MealEstimateResult> })
        }

        override val profiles: Flow<List<AiProfileStatus>> = MutableStateFlow(emptyList())
        override val defaults: AiDefaults = AiDefaults.load()
        override suspend fun profilesNow() = list.toList()
        override suspend fun profile(id: String) = list.firstOrNull { it.id == id }
        override suspend fun saveProfile(draft: AiProfileDraft, key: String?) = error("unused")
        override suspend fun deleteProfile(id: String) = null
        override suspend fun restoreProfile(deleted: DeletedAiProfile) = Unit
        override suspend fun savedKey(id: String): String? = null
        override suspend fun reorder(idsInOrder: List<String>) = Unit
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun copyKey(fromId: String, toId: String) = Unit
        override suspend fun test(id: String) = Result.success(ConnectionReport("m", 1))
        override suspend fun listModels(provider: ProviderId, baseUrl: String?, profileId: String?, draftKey: String?) = Result.success(emptyList<ModelInfo>())
        override suspend fun backupOffer() = null
        override suspend fun addBackupModel(): String? = null
        override fun estimatorFor(profile: AiProfile, textOnly: Boolean): MealEstimator = object : MealEstimator {
            override val providerId = profile.provider
            override suspend fun listModels() = Result.success(emptyList<ModelInfo>())
            override suspend fun testConnection() = Result.success(ConnectionReport("m", 1))
            override suspend fun estimate(request: MealEstimateRequest): Result<MealEstimateResult> {
                lastRequest = request
                requests += profile.id
                return answers.getValue(profile.id).removeFirst()
            }
        }
        override suspend fun recordAttempts(attempts: List<AiChain.Attempt>) { recorded += attempts }
        override fun monthUsage(month: YearMonth): Flow<MonthUsage> = flowOf(MonthUsage(month, emptyList()))
    }

    private companion object {
        /** One SR Legacy hit, shaped like FDC's /foods/search response. */
        val RICE_SEARCH = """
            {"foods":[{"fdcId":169756,"description":"Rice, white, long-grain, regular, enriched, cooked","dataType":"SR Legacy",
            "foodNutrients":[{"nutrientId":1008,"unitName":"KCAL","value":130.0},{"nutrientId":1003,"unitName":"G","value":2.69},
            {"nutrientId":1005,"unitName":"G","value":28.2},{"nutrientId":1004,"unitName":"G","value":0.28}],"foodMeasures":[]}]}
        """.trimIndent()
    }
}
