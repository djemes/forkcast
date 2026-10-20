package app.forkcast.core.data.ai

import android.net.Uri
import app.forkcast.core.data.db.AiComponentEntity
import app.forkcast.core.data.db.AiDao
import app.forkcast.core.data.db.AiEstimateEntity
import app.forkcast.core.data.db.ComponentSource
import app.forkcast.core.data.db.EstimateStatus
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.model.EstimatedItem
import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.WeightUnit
import app.forkcast.core.model.forGrams
import app.forkcast.core.model.kcal
import app.forkcast.core.model.sumNutrients
import app.forkcast.core.network.ai.AiChain
import app.forkcast.core.network.ai.AiError
import app.forkcast.core.network.ai.EncodedImage
import app.forkcast.core.network.ai.MealEstimateRequest
import app.forkcast.core.network.ai.MealPrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What a background run should do next. */
sealed interface RunOutcome {
    /** Finished (DONE or NEEDS_INPUT); [notify] = post "Estimate ready" if the app is in the background. */
    data class Finished(val estimateId: String, val notify: Boolean) : RunOutcome
    /** Every profile is out of quota; try again at [atMs]. */
    data class RetryAt(val atMs: Long) : RunOutcome
    /** Offline or a transient failure: retry with backoff when the network is back. */
    data object RetryWithBackoff : RunOutcome
    /** Stopped; the user has to act (refusal, unusable output, no profile, bad key). */
    data class Failed(val estimateId: String, val notifyKeyProblem: Boolean) : RunOutcome
    /** Deleted, already done, or AI turned off. */
    data object Nothing : RunOutcome
}

/** An estimate with its components, for the review screen. */
data class EstimateDetail(val estimate: AiEstimateEntity, val components: List<AiComponentEntity>)

/** Queues background estimate runs; WorkManager in the app, a fake in tests. */
interface EstimateScheduler {
    fun enqueue(estimateId: String, delayMs: Long = 0)
    fun cancel(estimateId: String)
}

/** Error codes stored on the estimate so the UI can show a specific message later. */
object AiErrorCodes {
    const val NO_PROFILE = "NoProfile"
    const val DISABLED = "Disabled"
    /**
     * Explicit names, not `e::class.simpleName`: R8 renames classes in release builds, which
     * would store "c" and lose the specific message on the review screen.
     */
    fun of(e: AiError): String = when (e) {
        is AiError.InvalidKey -> "InvalidKey"
        is AiError.RateLimited -> "RateLimited"
        is AiError.QuotaExhausted -> "QuotaExhausted"
        is AiError.ModelNotFound -> "ModelNotFound"
        is AiError.ContentRefused -> "ContentRefused"
        is AiError.BadResponse -> "BadResponse"
        is AiError.InvalidRequest -> "InvalidRequest"
        is AiError.Network -> "Network"
        is AiError.Timeout -> "Timeout"
        is AiError.Server -> "Server"
    }
}

interface AiEstimateRepository {
    fun observeDay(date: LocalDate): Flow<List<AiEstimateEntity>>
    fun observeDetail(id: String): Flow<EstimateDetail?>
    /** Stores processed photos and a pending estimate, then queues its run. Returns the id. */
    suspend fun create(date: LocalDate, mealSlotId: String, photos: List<Uri>, description: String?): String
    /** The background job: one pass through the profile chain. */
    suspend fun run(id: String): RunOutcome
    /** "Correct it" or an answer to the model's question: re-runs with the previous result as context. */
    suspend fun correct(id: String, text: String)
    /** Log a NEEDS_INPUT estimate without answering its question. */
    suspend fun acceptAsIs(id: String)
    suspend fun retry(id: String)
    suspend fun setGrams(componentId: String, grams: Double)
    suspend fun setSource(componentId: String, source: ComponentSource)
    /** "Change match": use [food] (picked from a search) for this component, and for this food from now on. */
    suspend fun setMatch(componentId: String, food: Food)
    suspend fun removeComponent(componentId: String)
    /** Review "Save": logs (or updates) the entry and clears the AI·review badge. */
    suspend fun save(id: String)
    suspend fun delete(id: String)
    /** Deletes photos older than the retention setting (SPEC §4.6). */
    suspend fun cleanUpPhotos()
    suspend fun markNotified(id: String)
    /** Retries ran out while offline: show the network message with Try again instead of "Estimating…" forever. */
    suspend fun stopRetrying(id: String)
}

@Singleton
class DefaultAiEstimateRepository @Inject constructor(
    private val dao: AiDao,
    private val ai: AiRepository,
    private val photos: PhotoStore,
    private val matcher: ComponentMatcher,
    private val log: LogRepository,
    private val mealSlots: MealSlotRepository,
    private val settings: SettingsRepository,
    private val scheduler: EstimateScheduler,
    private val clock: Clock,
) : AiEstimateRepository {

    override fun observeDay(date: LocalDate): Flow<List<AiEstimateEntity>> = dao.observeEstimatesForDay(date)

    override fun observeDetail(id: String): Flow<EstimateDetail?> =
        combine(dao.observeEstimate(id), dao.observeComponents(id)) { e, c -> e?.takeIf { it.deletedAt == null }?.let { EstimateDetail(it, c) } }

    override suspend fun create(date: LocalDate, mealSlotId: String, photos: List<Uri>, description: String?): String {
        val id = UUID.randomUUID().toString()
        val defaults = ai.defaults
        val paths = photos.take(defaults.maxImages).mapIndexed { i, uri -> this.photos.save(uri, id, i, defaults.maxImageEdgePx, defaults.jpegQuality) }
        val s = settings.settings.first()
        val now = clock.millis()
        dao.upsertEstimate(
            AiEstimateEntity(
                id = id, date = date, zoneId = clock.zone.id, mealSlotId = mealSlotId, logEntryId = null,
                status = EstimateStatus.PENDING, profileId = null, profileName = null, provider = null, model = null, tierLabel = null,
                description = description?.trim()?.takeIf { it.isNotBlank() },
                personalContext = s.ai.personalContext.trim().takeIf { it.isNotBlank() },
                photoPaths = paths,
                localTime = LocalTime.now(clock).format(TIME),
                unitsHint = unitsHint(s.units),
                correction = null, promptVersion = MealPrompt.VERSION, rawJson = null, result = null, warnings = emptyList(),
                mealName = null, kcalLow = null, kcalExpected = null, kcalHigh = null, inputTokens = null, outputTokens = null,
                latencyMs = null, errorCode = null, errorDetail = null, attempts = 0, retryAfter = null, notified = false,
                reviewedAt = null, createdAt = now, updatedAt = now,
            ),
        )
        scheduler.enqueue(id)
        return id
    }

    override suspend fun run(id: String): RunOutcome {
        val e = dao.estimate(id) ?: return RunOutcome.Nothing
        if (e.deletedAt != null || e.status == EstimateStatus.DONE || e.status == EstimateStatus.NEEDS_INPUT) return RunOutcome.Nothing
        val s = settings.settings.first()
        if (!s.ai.enabled) return fail(e, AiErrorCodes.DISABLED, null, keyProblem = false)
        update(e.copy(status = EstimateStatus.RUNNING, attempts = e.attempts + 1))

        val images = e.photoPaths.mapNotNull { p -> runCatching { EncodedImage(photos.read(p)) }.getOrNull() }
        val mealName = mealSlots.observeMealSlots().first().firstOrNull { it.id == e.mealSlotId }?.name ?: "Meal"
        val profiles = ai.profilesNow()
        val outcome = AiChain.run(
            profiles = profiles,
            now = clock.instant(),
            estimatorFor = ai::estimatorFor,
            requestFor = { p ->
                MealEstimateRequest(
                    images = images, description = e.description, mealSlot = mealName, localTime = e.localTime,
                    unitsHint = e.unitsHint, personalContext = e.personalContext,
                    previous = e.result.takeIf { e.correction != null }, correction = e.correction, model = p.model,
                )
            },
        )
        ai.recordAttempts(outcome.attempts)

        return when (outcome) {
            is AiChain.Outcome.Success -> {
                val p = outcome.profile
                val r = outcome.result
                val components = buildComponents(id, r.parsed.items)
                dao.replaceComponents(id, components, clock.millis())
                val totals = totals(components)
                val question = r.parsed.clarifyingQuestion
                val done = e.copy(
                    status = if (question != null) EstimateStatus.NEEDS_INPUT else EstimateStatus.DONE,
                    profileId = p.id, profileName = p.name, provider = p.provider, model = r.model, tierLabel = p.tierLabel,
                    rawJson = r.rawJson, result = r.parsed, warnings = r.warnings, mealName = r.parsed.mealName.ifBlank { null },
                    kcalLow = totals.low, kcalExpected = totals.expected, kcalHigh = totals.high,
                    inputTokens = r.inputTokens, outputTokens = r.outputTokens, latencyMs = r.latencyMs,
                    errorCode = null, errorDetail = null, retryAfter = null, attempts = e.attempts + 1,
                    correction = null, notified = false,
                )
                update(done)
                if (done.status == EstimateStatus.DONE && (s.ai.autoAccept || done.logEntryId != null)) writeLogEntry(done, components)
                RunOutcome.Finished(id, notify = true)
            }
            is AiChain.Outcome.Failure -> {
                val err = outcome.finalError
                val retryAt = outcome.retryAtMs
                // Name the profile that gave this error, so the message says "Gemini rejected…".
                val last = outcome.attempts.lastOrNull { it.error === err }?.profile
                val failed = e.copy(
                    attempts = e.attempts + 1, errorCode = err?.let(AiErrorCodes::of), errorDetail = err?.message,
                    profileId = last?.id ?: e.profileId, profileName = last?.name ?: e.profileName,
                    provider = last?.provider ?: e.provider, tierLabel = last?.tierLabel ?: e.tierLabel,
                )
                when {
                    // Nothing could be tried: no profile, all off, or all keys rejected.
                    outcome.attempts.isEmpty() && retryAt == null ->
                        fail(failed, AiErrorCodes.NO_PROFILE, null, keyProblem = profiles.any { it.needsAttention })
                    // Every profile is out of quota: wait for the earliest reset.
                    outcome.retryable && retryAt != null -> {
                        update(failed.copy(status = EstimateStatus.PENDING, retryAfter = retryAt))
                        RunOutcome.RetryAt(retryAt)
                    }
                    // Offline or providers having trouble: retry with backoff, entry stays pending.
                    err is AiError.Network || err is AiError.Timeout || err is AiError.Server || err is AiError.RateLimited -> {
                        update(failed.copy(status = EstimateStatus.PENDING))
                        RunOutcome.RetryWithBackoff
                    }
                    // Refusal, unusable output, or a setup problem (key, model): the user has to act.
                    else -> fail(failed, err?.let(AiErrorCodes::of) ?: AiErrorCodes.NO_PROFILE, err?.message, keyProblem = err is AiError.InvalidKey)
                }
            }
        }
    }

    private suspend fun fail(e: AiEstimateEntity, code: String, detail: String?, keyProblem: Boolean): RunOutcome {
        update(e.copy(status = EstimateStatus.FAILED, errorCode = code, errorDetail = detail))
        return RunOutcome.Failed(e.id, keyProblem)
    }

    private suspend fun buildComponents(estimateId: String, items: List<EstimatedItem>): List<AiComponentEntity> {
        val now = clock.millis()
        return items.mapIndexed { i, item ->
            val aiPer100g = item.nutrients.toNutrients().mapValues { (_, v) -> v * 100.0 / item.grams }
            val match = matcher.match(item)
            val source = if (match != null) ComponentSource.USDA else ComponentSource.AI
            val per100 = match?.food?.nutrientsPer100g ?: aiPer100g
            AiComponentEntity(
                id = UUID.randomUUID().toString(), estimateId = estimateId, sortOrder = i,
                name = item.name, preparation = item.preparation, grams = item.grams, gramsLow = item.gramsLow, gramsHigh = item.gramsHigh,
                confidence = item.confidence, isHiddenIngredient = item.isHiddenIngredient, usdaQuery = item.usdaQuery,
                matchedFoodId = match?.food?.id, matchedFdcId = match?.food?.sourceId, matchedName = match?.food?.name, matchScore = match?.score,
                aiPer100g = aiPer100g, usdaPer100g = match?.food?.nutrientsPer100g, useSource = source,
                nutrientsUsed = per100.forGrams(item.grams), createdAt = now, updatedAt = now,
            )
        }
    }

    private data class Totals(val low: Double, val expected: Double, val high: Double)

    /** Expected = sum of components; range = each component's kcal/g × its low/high grams. */
    private fun totals(components: List<AiComponentEntity>): Totals {
        var low = 0.0
        var exp = 0.0
        var high = 0.0
        for (c in components) {
            val kcal = c.nutrientsUsed.kcal
            val perG = if (c.grams > 0) kcal / c.grams else 0.0
            exp += kcal
            low += perG * minOf(c.gramsLow, c.grams)
            high += perG * maxOf(c.gramsHigh, c.grams)
        }
        return Totals(low, exp, high)
    }

    private suspend fun writeLogEntry(e: AiEstimateEntity, components: List<AiComponentEntity>) {
        val nutrients: Nutrients = components.map { it.nutrientsUsed }.sumNutrients()
        val grams = components.sumOf { it.grams }
        val name = e.mealName ?: e.result?.mealName?.ifBlank { null } ?: "Photo meal"
        val existing = e.logEntryId?.let { log.get(it) }
        if (existing != null) {
            log.update(existing.copy(displayName = name, grams = grams, nutrients = nutrients, mealSlotId = existing.mealSlotId))
        } else {
            val entry = LogEntry(
                id = "", date = e.date, mealSlotId = e.mealSlotId, foodId = null, displayName = name, brand = null,
                quantity = 1.0, servingLabel = "meal", grams = grams, nutrients = nutrients, source = FoodSource.AI,
                note = null, loggedAt = clock.instant(), aiEstimateId = e.id,
            )
            val entryId = log.add(listOf(entry)).single()
            dao.estimate(e.id)?.let { update(it.copy(logEntryId = entryId)) }
        }
    }

    override suspend fun correct(id: String, text: String) {
        val e = dao.estimate(id) ?: return
        if (text.isBlank()) return
        update(e.copy(status = EstimateStatus.PENDING, correction = text.trim(), errorCode = null, errorDetail = null, retryAfter = null))
        scheduler.enqueue(id)
    }

    override suspend fun acceptAsIs(id: String) {
        val e = dao.estimate(id) ?: return
        if (e.status != EstimateStatus.NEEDS_INPUT) return
        val done = e.copy(status = EstimateStatus.DONE)
        update(done)
        writeLogEntry(done, dao.components(id))
    }

    override suspend fun retry(id: String) {
        val e = dao.estimate(id) ?: return
        update(e.copy(status = EstimateStatus.PENDING, errorCode = null, errorDetail = null, retryAfter = null))
        scheduler.enqueue(id)
    }

    override suspend fun setGrams(componentId: String, grams: Double) = editComponent(componentId) { c ->
        val g = grams.coerceAtLeast(0.0)
        c.copy(grams = g, nutrientsUsed = per100(c).forGrams(g))
    }

    override suspend fun setSource(componentId: String, source: ComponentSource) = editComponent(componentId) { c ->
        if (source == ComponentSource.USDA && c.usdaPer100g == null) return@editComponent c
        val next = c.copy(useSource = source)
        next.copy(nutrientsUsed = per100(next).forGrams(next.grams))
    }

    override suspend fun setMatch(componentId: String, food: Food) {
        val c = dao.componentById(componentId) ?: return
        val local = matcher.remember(c.usdaQuery, c.preparation, food)
        editComponent(componentId) { cur ->
            cur.copy(
                matchedFoodId = local.id, matchedFdcId = local.sourceId, matchedName = local.name, matchScore = ComponentMatcher.USER_CHOICE_SCORE,
                usdaPer100g = local.nutrientsPer100g, useSource = ComponentSource.USDA,
                nutrientsUsed = local.nutrientsPer100g.forGrams(cur.grams),
            )
        }
    }

    override suspend fun removeComponent(componentId: String) = editComponent(componentId) { it.copy(deletedAt = clock.millis()) }

    private fun per100(c: AiComponentEntity): Nutrients =
        if (c.useSource == ComponentSource.USDA && c.usdaPer100g != null) c.usdaPer100g else c.aiPer100g

    private suspend fun editComponent(componentId: String, change: (AiComponentEntity) -> AiComponentEntity) {
        val estimateId = findEstimateOf(componentId) ?: return
        val all = dao.components(estimateId)
        val target = all.firstOrNull { it.id == componentId } ?: return
        dao.upsertComponents(listOf(change(target).copy(updatedAt = clock.millis())))
        val e = dao.estimate(estimateId) ?: return
        val t = totals(dao.components(estimateId))
        update(e.copy(kcalLow = t.low, kcalExpected = t.expected, kcalHigh = t.high))
    }

    private suspend fun findEstimateOf(componentId: String): String? = dao.componentById(componentId)?.estimateId

    override suspend fun save(id: String) {
        val e = dao.estimate(id) ?: return
        val components = dao.components(id)
        val saved = e.copy(status = if (e.status == EstimateStatus.NEEDS_INPUT) EstimateStatus.DONE else e.status, reviewedAt = clock.millis())
        update(saved)
        if (saved.status == EstimateStatus.DONE) writeLogEntry(dao.estimate(id) ?: saved, components)
    }

    override suspend fun delete(id: String) {
        val e = dao.estimate(id) ?: return
        scheduler.cancel(id)
        e.logEntryId?.let { log.delete(listOf(it)) }
        update(e.copy(deletedAt = clock.millis()))
        photos.delete(e.photoPaths)
        update((dao.estimate(id) ?: e).copy(photoPaths = emptyList()))
    }

    override suspend fun cleanUpPhotos() {
        val days = settings.settings.first().ai.photoRetentionDays ?: return
        val cutoff = clock.millis() - days * 24L * 60 * 60 * 1000
        for (e in dao.estimatesWithPhotosBefore(cutoff)) {
            photos.delete(e.photoPaths)
            update(e.copy(photoPaths = emptyList()))
        }
    }

    override suspend fun markNotified(id: String) {
        dao.estimate(id)?.let { update(it.copy(notified = true)) }
    }

    override suspend fun stopRetrying(id: String) {
        val e = dao.estimate(id) ?: return
        if (e.deletedAt != null || e.status != EstimateStatus.PENDING) return
        update(e.copy(status = EstimateStatus.FAILED, errorCode = e.errorCode ?: AiErrorCodes.of(AiError.Network()), retryAfter = null))
    }

    private suspend fun update(e: AiEstimateEntity) = dao.upsertEstimate(e.copy(updatedAt = clock.millis()))

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        fun unitsHint(units: UnitPrefs?): String = if (units?.weight == WeightUnit.KG) "metric" else "US customary"
    }
}
