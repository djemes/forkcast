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
    override suspend fun create(date: LocalDate, mealSlotId: String, photos: List<Uri>, description: String?): String {
        created += Triple(mealSlotId, photos, description)
        return "e${created.size}"
    }
    override suspend fun run(id: String): RunOutcome = RunOutcome.Nothing
    override suspend fun correct(id: String, text: String) { calls += "correct:$text" }
    override suspend fun acceptAsIs(id: String) { calls += "accept" }
    override suspend fun retry(id: String) { calls += "retry" }
    override suspend fun setGrams(componentId: String, grams: Double) { calls += "grams:$componentId:$grams" }
    override suspend fun setSource(componentId: String, source: ComponentSource) { calls += "source:$componentId:$source" }
    override suspend fun removeComponent(componentId: String) { calls += "remove:$componentId" }
    override suspend fun save(id: String) { calls += "save" }
    override suspend fun delete(id: String) {
        calls += "delete"
        details.update { it - id }
    }
    override suspend fun cleanUpPhotos() = Unit
    override suspend fun markNotified(id: String) = Unit

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
