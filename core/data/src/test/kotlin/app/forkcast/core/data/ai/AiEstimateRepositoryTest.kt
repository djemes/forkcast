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
    private val scheduler = object : EstimateScheduler {
        override fun enqueue(estimateId: String, delayMs: Long) { scheduled += estimateId to delayMs }
        override fun cancel(estimateId: String) {}
    }
    private val photos = PhotoStore(context)
    private val repo = DefaultAiEstimateRepository(
        db.aiDao(), ai, photos, ComponentMatcher(usda, foods, db.aiDao(), clock), log,
        RoomMealSlotRepository(db, db.mealSlotDao(), clock), settings, scheduler, clock,
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
        assertThat(repo.run(id)).isEqualTo(RunOutcome.RetryWithBackoff)
        val e = db.aiDao().estimate(id)!!
        assertThat(e.status).isEqualTo(EstimateStatus.PENDING)
        assertThat(e.errorCode).isEqualTo("Network")
        assertThat(log.observeDay(day).first()).isEmpty()
        // The worker ran out of retries: the row shows the network message with Try again.
        repo.stopRetrying(id)
        assertThat(db.aiDao().estimate(id)!!.status).isEqualTo(EstimateStatus.FAILED)
        assertThat(db.aiDao().estimate(id)!!.errorCode).isEqualTo("Network")
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

        repo.setGrams(rice.id, 100.0)
        assertThat(db.aiDao().components(id).first { it.id == rice.id }.nutrientsUsed.kcal).isWithin(1.0).of(130.0)
        repo.setSource(rice.id, ComponentSource.AI)
        assertThat(db.aiDao().components(id).first { it.id == rice.id }.nutrientsUsed.kcal).isWithin(1.0).of(130.0)
        assertThat(db.aiDao().components(id).first { it.id == rice.id }.nutrientsUsed[NutrientKey.CARBS_G]).isWithin(0.5).of(28.0)

        repo.save(id)
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
        repo.setMatch(chicken.id, picked)

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
        repo.save(id)
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

    /** Profiles with scripted answers, in order. */
    private class ScriptedAi : AiRepository {
        private val list = mutableListOf<AiProfile>()
        private val answers = mutableMapOf<String, ArrayDeque<Result<MealEstimateResult>>>()
        val recorded = mutableListOf<AiChain.Attempt>()
        var lastRequest: MealEstimateRequest? = null

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
        override fun estimatorFor(profile: AiProfile): MealEstimator = object : MealEstimator {
            override val providerId = profile.provider
            override suspend fun listModels() = Result.success(emptyList<ModelInfo>())
            override suspend fun testConnection() = Result.success(ConnectionReport("m", 1))
            override suspend fun estimate(request: MealEstimateRequest): Result<MealEstimateResult> {
                lastRequest = request
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
