package app.forkcast.fakes

import app.forkcast.core.data.repository.AiProfileDraft
import app.forkcast.core.data.repository.AiProfileStatus
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.MonthUsage
import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.ProviderId
import app.forkcast.core.network.ai.AiChain
import app.forkcast.core.network.ai.AiDefaults
import app.forkcast.core.network.ai.AiError
import app.forkcast.core.network.ai.ConnectionReport
import app.forkcast.core.network.ai.MealEstimator
import app.forkcast.core.network.ai.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.YearMonth

class FakeAiRepository(
    /** Which profile ids fail their test, and how. */
    var testErrors: Map<String, AiError> = emptyMap(),
    var models: List<ModelInfo> = listOf(ModelInfo("gemini-3.8-flash", "Gemini 3.8 Flash", null), ModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", null)),
    var estimator: (AiProfile) -> MealEstimator = { error("no estimator in this test") },
) : AiRepository {
    val state = MutableStateFlow<List<AiProfileStatus>>(emptyList())
    val keys = mutableMapOf<String, String>()
    val recorded = mutableListOf<AiChain.Attempt>()
    private var next = 0

    override val profiles: Flow<List<AiProfileStatus>> = state
    override val defaults: AiDefaults = AiDefaults.load()
    override suspend fun profilesNow(): List<AiProfile> = state.value.map { it.profile }
    override suspend fun profile(id: String): AiProfile? = state.value.firstOrNull { it.profile.id == id }?.profile

    override suspend fun saveProfile(draft: AiProfileDraft, key: String?): String {
        val id = draft.id ?: "p${next++}"
        val old = state.value.firstOrNull { it.profile.id == id }
        val p = AiProfile(
            id, draft.name, draft.provider, draft.model, draft.baseUrl, draft.tierLabel,
            old?.profile?.order ?: state.value.size, old?.profile?.enabled ?: true, false, null,
        )
        if (key != null) keys[id] = key
        state.update { list -> list.filterNot { it.profile.id == id } + AiProfileStatus(p, id in keys, 0) }
        state.update { list -> list.sortedBy { it.profile.order } }
        return id
    }

    override suspend fun deleteProfile(id: String) = state.update { l -> l.filterNot { it.profile.id == id } }

    override suspend fun reorder(idsInOrder: List<String>) = state.update { l ->
        idsInOrder.mapIndexedNotNull { i, id -> l.firstOrNull { it.profile.id == id }?.let { it.copy(profile = it.profile.copy(order = i)) } }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = state.update { l ->
        l.map { if (it.profile.id == id) it.copy(profile = it.profile.copy(enabled = enabled)) else it }
    }

    override suspend fun copyKey(fromId: String, toId: String) {
        keys[fromId]?.let { keys[toId] = it }
        state.update { l -> l.map { if (it.profile.id == toId) it.copy(hasKey = toId in keys) else it } }
    }

    override suspend fun test(id: String): Result<ConnectionReport> =
        testErrors[id]?.let { Result.failure(it) } ?: Result.success(ConnectionReport(profile(id)?.model ?: "m", 420))

    override suspend fun listModels(provider: ProviderId, baseUrl: String?, profileId: String?, draftKey: String?) = Result.success(models)
    override fun estimatorFor(profile: AiProfile): MealEstimator = estimator(profile)
    override suspend fun recordAttempts(attempts: List<AiChain.Attempt>) { recorded += attempts }
    override fun monthUsage(month: YearMonth): Flow<MonthUsage> = state.map { MonthUsage(month, emptyList()) }
}
