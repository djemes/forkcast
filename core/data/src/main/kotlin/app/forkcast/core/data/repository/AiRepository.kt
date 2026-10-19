package app.forkcast.core.data.repository

import app.forkcast.core.data.db.AiDao
import app.forkcast.core.data.db.AiProfileEntity
import app.forkcast.core.data.db.UsageRecordEntity
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.network.ai.AiChain
import app.forkcast.core.network.ai.AiDefaults
import app.forkcast.core.network.ai.AiError
import app.forkcast.core.network.ai.ConnectionReport
import app.forkcast.core.network.ai.MealEstimator
import app.forkcast.core.network.ai.MealEstimatorFactory
import app.forkcast.core.network.ai.ModelInfo
import app.forkcast.core.network.ai.Price
import app.forkcast.core.network.ai.QuotaWindows
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A profile as Settings › AI shows it. */
data class AiProfileStatus(val profile: AiProfile, val hasKey: Boolean, val usedToday: Int)

/** What the user enters when adding or editing a profile. */
data class AiProfileDraft(
    val id: String?,
    val name: String,
    val provider: ProviderId,
    val model: String,
    val baseUrl: String?,
    val tierLabel: TierLabel,
)

/**
 * What [AiRepository.deleteProfile] removed, kept by the screen for Undo. The key stays in memory
 * only for the Undo window and never prints ([toString] leaves it out).
 */
class DeletedAiProfile(val id: String, val name: String, val position: Int, val key: String?) {
    override fun toString() = "DeletedAiProfile(id=$id, position=$position)"
}

/** Usage for one model in a month (SPEC §4.7 "Usage"). */
data class ModelUsage(
    val model: String,
    val provider: ProviderId,
    val estimates: Int,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    /** Null when no price is known for the model. */
    val costUsd: Double?,
)

data class MonthUsage(val month: YearMonth, val models: List<ModelUsage>) {
    val estimates: Int get() = models.sumOf { it.estimates }
    val inputTokens: Long get() = models.sumOf { it.inputTokens }
    val outputTokens: Long get() = models.sumOf { it.outputTokens }
    val costUsd: Double get() = models.sumOf { it.costUsd ?: 0.0 }
}

interface AiRepository {
    val profiles: Flow<List<AiProfileStatus>>
    val defaults: AiDefaults
    suspend fun profilesNow(): List<AiProfile>
    suspend fun profile(id: String): AiProfile?
    /** Creates or updates; [key] null leaves the stored key unchanged, blank clears it. */
    suspend fun saveProfile(draft: AiProfileDraft, key: String?): String
    /**
     * Deletes the profile and its key. The profiles after it move up one place, so the fallback
     * order stays 1, 2, 3… Returns what [restoreProfile] needs for Undo.
     */
    suspend fun deleteProfile(id: String): DeletedAiProfile?
    /** Undo for [deleteProfile]: back at its old place, with its key. */
    suspend fun restoreProfile(deleted: DeletedAiProfile)
    /** The saved key, for the show/copy buttons on the profile screen. Never log it. */
    suspend fun savedKey(id: String): String?
    suspend fun reorder(idsInOrder: List<String>)
    suspend fun setEnabled(id: String, enabled: Boolean)
    /** Same key in another profile (e.g. Flash-Lite on the free Gemini key). */
    suspend fun copyKey(fromId: String, toId: String)
    suspend fun test(id: String): Result<ConnectionReport>
    /** Lists models with a saved profile's key, or with a key typed but not saved yet. */
    suspend fun listModels(provider: ProviderId, baseUrl: String?, profileId: String?, draftKey: String?): Result<List<ModelInfo>>
    fun estimatorFor(profile: AiProfile): MealEstimator
    /** Persists what a chain run learned: cooldowns, rejected keys, usage. */
    suspend fun recordAttempts(attempts: List<AiChain.Attempt>)
    fun monthUsage(month: YearMonth): Flow<MonthUsage>
}

@Singleton
class DefaultAiRepository @Inject constructor(
    private val dao: AiDao,
    private val secrets: SecretStore,
    private val factory: MealEstimatorFactory,
    override val defaults: AiDefaults,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : AiRepository {

    // Deferred so the usage window is computed when a screen subscribes, not once per process.
    override val profiles: Flow<List<AiProfileStatus>> = flow {
        val today = LocalDate.now(clock)
        val usage = dao.observeUsage(today.minusDays(2), today.plusDays(2))
        emitAll(
            combine(dao.observeProfiles(), secrets.aiKeysPresent, usage) { rows, keys, records ->
                rows.map { row ->
                    val p = row.toModel()
                    val day = quotaDay(p.provider)
                    val used = records.filter { it.profileId == p.id && it.day == day }.sumOf { it.requests }
                    AiProfileStatus(p, row.id in keys, used)
                }
            },
        )
    }

    override suspend fun profilesNow(): List<AiProfile> = dao.profiles().map { it.toModel() }

    override suspend fun profile(id: String): AiProfile? = dao.profile(id)?.takeIf { it.deletedAt == null }?.toModel()

    override suspend fun saveProfile(draft: AiProfileDraft, key: String?): String {
        val now = clock.millis()
        val existing = draft.id?.let { dao.profile(it) }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val order = existing?.sortOrder ?: ((dao.profiles().maxOfOrNull { it.sortOrder } ?: -1) + 1)
        val keyChanged = key != null
        dao.upsertProfile(
            AiProfileEntity(
                id = id,
                name = draft.name.trim().ifBlank { defaults.displayName(draft.model) },
                provider = draft.provider,
                model = draft.model.trim(),
                baseUrl = draft.baseUrl?.trim()?.takeIf { it.isNotBlank() && draft.provider == ProviderId.OPENAI_COMPAT },
                tierLabel = draft.tierLabel,
                sortOrder = order,
                enabled = existing?.enabled ?: true,
                // A new key (or a changed model) gets a fresh start.
                needsAttention = if (keyChanged || existing?.model != draft.model) false else existing.needsAttention,
                cooldownUntil = if (keyChanged || existing?.model != draft.model) null else existing.cooldownUntil,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        if (key != null) secrets.setAiKey(id, key)
        return id
    }

    override suspend fun deleteProfile(id: String): DeletedAiProfile? {
        val p = dao.profile(id)?.takeIf { it.deletedAt == null } ?: return null
        val now = clock.millis()
        val position = dao.profiles().indexOfFirst { it.id == id }
        val deleted = DeletedAiProfile(id, p.name, position, secrets.aiKey(id))
        dao.upsertProfile(p.copy(deletedAt = now, updatedAt = now))
        secrets.setAiKey(id, null)
        renumber(dao.profiles(), now)
        return deleted
    }

    override suspend fun restoreProfile(deleted: DeletedAiProfile) {
        val p = dao.profile(deleted.id) ?: return
        val now = clock.millis()
        val restored = p.copy(deletedAt = null, updatedAt = now)
        val others = dao.profiles().filterNot { it.id == deleted.id }.toMutableList()
        others.add(deleted.position.coerceIn(0, others.size), restored)
        renumber(others, now)
        if (deleted.key != null) secrets.setAiKey(deleted.id, deleted.key)
    }

    override suspend fun savedKey(id: String): String? = secrets.aiKey(id)

    /** Writes sortOrder 0, 1, 2… in list order (no gaps after a delete or restore). */
    private suspend fun renumber(inOrder: List<AiProfileEntity>, now: Long) {
        dao.upsertProfiles(inOrder.mapIndexed { i, row -> if (row.sortOrder == i) row else row.copy(sortOrder = i, updatedAt = now) })
    }

    override suspend fun reorder(idsInOrder: List<String>) {
        val now = clock.millis()
        val byId = dao.profiles().associateBy { it.id }
        dao.upsertProfiles(idsInOrder.mapIndexedNotNull { i, id -> byId[id]?.copy(sortOrder = i, updatedAt = now) })
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        val p = dao.profile(id) ?: return
        dao.upsertProfile(p.copy(enabled = enabled, updatedAt = clock.millis()))
    }

    override suspend fun copyKey(fromId: String, toId: String) {
        secrets.setAiKey(toId, secrets.aiKey(fromId))
        dao.setNeedsAttention(toId, false, clock.millis())
    }

    override suspend fun test(id: String): Result<ConnectionReport> {
        val profile = profile(id) ?: return Result.failure(AiError.InvalidRequest("profile not found"))
        val result = factory.create(profile).testConnection()
        val error = result.exceptionOrNull() as? AiError
        val now = clock.millis()
        when (error) {
            null -> {
                dao.setNeedsAttention(id, false, now)
                dao.setCooldown(id, null, now)
            }
            is AiError.InvalidKey -> dao.setNeedsAttention(id, true, now)
            else -> Unit
        }
        recordUsage(profile, profile.model, error, isEstimate = false, inputTokens = 0, outputTokens = 0)
        return result
    }

    override suspend fun listModels(provider: ProviderId, baseUrl: String?, profileId: String?, draftKey: String?): Result<List<ModelInfo>> {
        val temp = AiProfile(
            id = profileId ?: "draft", name = "", provider = provider, model = "", baseUrl = baseUrl,
            tierLabel = TierLabel.UNKNOWN, order = 0, enabled = true, needsAttention = false, cooldownUntil = null,
        )
        val estimator = if (draftKey != null) factory.create(temp, keyOverride = draftKey) else factory.create(temp)
        return estimator.listModels().map { list -> list.filter { it.supportsImages != false }.sortedBy { it.displayName } }
    }

    override fun estimatorFor(profile: AiProfile): MealEstimator = factory.create(profile)

    override suspend fun recordAttempts(attempts: List<AiChain.Attempt>) {
        val now = clock.millis()
        for (a in attempts) {
            if (a.needsAttention) dao.setNeedsAttention(a.profile.id, true, now)
            if (a.cooldownUntil != null) dao.setCooldown(a.profile.id, a.cooldownUntil, now)
            recordUsage(
                a.profile, a.result?.model ?: a.profile.model, a.error, isEstimate = true,
                inputTokens = a.result?.inputTokens?.toLong() ?: 0, outputTokens = a.result?.outputTokens?.toLong() ?: 0,
            )
        }
    }

    override fun monthUsage(month: YearMonth): Flow<MonthUsage> = combine(
        dao.observeUsage(month.atDay(1), month.atEndOfMonth()),
        dao.observeProfiles(),
        settings.settings.map { it.ai.priceOverrides },
    ) { rows, profiles, overrides ->
        val freeProfiles = profiles.filter { it.tierLabel == TierLabel.FREE }.map { it.id }.toSet()
        val priceDay = minOf(month.atEndOfMonth(), LocalDate.now(clock))
        val models = rows.groupBy { it.model }.map { (model, list) ->
            val input = list.sumOf { it.inputTokens }
            val output = list.sumOf { it.outputTokens }
            val price = overrides[model]?.let { Price(it.inputPerM, it.outputPerM) } ?: defaults.price(model, priceDay)
            // Free-tier Gemini usage costs nothing; only paid (or unknown) keys are priced.
            val paid = list.filterNot { it.profileId in freeProfiles }
            val cost = price?.cost(paid.sumOf { it.inputTokens }, paid.sumOf { it.outputTokens })
            ModelUsage(model, list.first().provider, list.sumOf { it.estimates }, list.sumOf { it.requests }, input, output, cost)
        }.sortedByDescending { it.requests }
        MonthUsage(month, models)
    }

    private suspend fun recordUsage(profile: AiProfile, model: String, error: AiError?, isEstimate: Boolean, inputTokens: Long, outputTokens: Long) {
        val day = quotaDay(profile.provider)
        val now = clock.millis()
        val old = dao.usage(day, profile.id, model)
        val rateLimited = error is AiError.RateLimited || error is AiError.QuotaExhausted
        dao.upsertUsage(
            UsageRecordEntity(
                id = old?.id ?: "$day|${profile.id}|$model",
                day = day,
                profileId = profile.id,
                provider = profile.provider,
                model = model,
                requests = (old?.requests ?: 0) + 1,
                estimates = (old?.estimates ?: 0) + if (isEstimate && error == null) 1 else 0,
                rateLimited = (old?.rateLimited ?: 0) + if (rateLimited) 1 else 0,
                failures = (old?.failures ?: 0) + if (error != null && !rateLimited) 1 else 0,
                inputTokens = (old?.inputTokens ?: 0) + inputTokens,
                outputTokens = (old?.outputTokens ?: 0) + outputTokens,
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    /** "Used today" follows the provider's quota day: Pacific for Gemini, local otherwise. */
    private fun quotaDay(provider: ProviderId): LocalDate =
        if (provider == ProviderId.GEMINI) QuotaWindows.pacificDay(clock.instant()) else LocalDate.now(clock)
}

internal fun AiProfileEntity.toModel() = AiProfile(
    id = id, name = name, provider = provider, model = model, baseUrl = baseUrl, tierLabel = tierLabel,
    order = sortOrder, enabled = enabled, needsAttention = needsAttention, cooldownUntil = cooldownUntil,
)
