package app.forkcast.fakes

import android.net.Uri
import app.forkcast.core.data.ai.AiEstimateRepository
import app.forkcast.core.data.ai.EstimateDetail
import app.forkcast.core.data.ai.RunOutcome
import app.forkcast.core.data.db.AiEstimateEntity
import app.forkcast.core.data.db.ComponentSource
import app.forkcast.core.data.db.EstimateStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/** Records what the UI asked for; estimates are plain rows the test can set. */
class FakeAiEstimateRepository : AiEstimateRepository {
    val rows = MutableStateFlow<List<AiEstimateEntity>>(emptyList())
    val details = MutableStateFlow<Map<String, EstimateDetail>>(emptyMap())
    val created = mutableListOf<Triple<String, List<Uri>, String?>>()
    val calls = mutableListOf<String>()

    override fun observeDay(date: LocalDate): Flow<List<AiEstimateEntity>> = rows.map { l -> l.filter { it.date == date && it.deletedAt == null } }
    override fun observeDetail(id: String): Flow<EstimateDetail?> = details.map { it[id] }
    /** Meal times the UI passed to create / createDescribed. */
    val minutes = mutableListOf<Int?>()
    override suspend fun create(date: LocalDate, mealSlotId: String, photos: List<Uri>, description: String?, eatenMinute: Int?): String {
        created += Triple(mealSlotId, photos, description)
        minutes += eatenMinute
        return "e${created.size}"
    }
    override suspend fun createDescribed(date: LocalDate, mealSlotId: String, description: String, eatenMinute: Int?): String {
        calls += "describe:$description"
        minutes += eatenMinute
        return "d${calls.size}"
    }
    override suspend fun run(id: String): RunOutcome = RunOutcome.Nothing
    override suspend fun correct(id: String, text: String) { calls += "correct:$text" }
    override suspend fun acceptAsIs(id: String) { calls += "accept" }
    override suspend fun retry(id: String) { calls += "retry" }
    override suspend fun retryWith(id: String, profileId: String) { calls += "retryWith:$profileId" }
    override suspend fun editDescriptionAndRetry(id: String, description: String) { calls += "edit:$description" }
    override suspend fun abandon(id: String) { calls += "abandon" }
    override suspend fun localFood(food: app.forkcast.core.model.Food) = food.copy(id = food.id.ifEmpty { "local-${food.name}" })
    override suspend fun saveReview(id: String, components: List<app.forkcast.core.data.db.AiComponentEntity>, portion: Double) {
        calls += "save:${components.size}:$portion"
    }
    override suspend fun saveAsFood(id: String, name: String): String? { calls += "food:$name"; return "f1" }
    override suspend fun delete(id: String) {
        calls += "delete"
        details.update { it - id }
    }
    override suspend fun cleanUpPhotos() = Unit
    override suspend fun markNotified(id: String) = Unit
    override suspend fun recover() { calls += "recover" }

    companion object {
        fun row(id: String, date: LocalDate, mealSlotId: String, status: EstimateStatus, logEntryId: String? = null, kcal: Double? = null) =
            AiEstimateEntity(
                id = id, date = date, zoneId = "UTC", mealSlotId = mealSlotId, logEntryId = logEntryId, status = status,
                profileId = "p", profileName = "Gemini 3.8 Flash", provider = app.forkcast.core.model.ProviderId.GEMINI,
                model = "gemini-3.8-flash", tierLabel = app.forkcast.core.model.TierLabel.FREE, description = null,
                personalContext = null, photoPaths = emptyList(), localTime = "12:40", unitsHint = "metric", correction = null,
                promptVersion = "meal-v1", rawJson = null, result = null, warnings = emptyList(), mealName = "Chicken and rice",
                kcalLow = kcal?.times(0.8), kcalExpected = kcal, kcalHigh = kcal?.times(1.2), inputTokens = null, outputTokens = null,
                latencyMs = null, errorCode = null, errorDetail = null, attempts = 0, retryAfter = null, notified = false,
                reviewedAt = null, createdAt = 0, updatedAt = 0,
            )
    }
}
