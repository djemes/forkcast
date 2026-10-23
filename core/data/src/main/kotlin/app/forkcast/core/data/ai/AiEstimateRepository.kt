package app.forkcast.core.data.ai

import android.content.Context
import android.net.Uri
import app.forkcast.core.data.R
import dagger.hilt.android.qualifiers.ApplicationContext
import app.forkcast.core.data.db.AiComponentEntity
import app.forkcast.core.data.db.AiDao
import app.forkcast.core.data.db.AiEstimateEntity
import app.forkcast.core.data.db.AttemptRecord
import app.forkcast.core.data.db.EstimateKind
import app.forkcast.core.data.db.EstimatePhase
import app.forkcast.core.data.db.EstimateStatus
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.DrinkWater
import app.forkcast.core.domain.MealTimes
import app.forkcast.core.model.EstimatedItem
import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.ServingSize
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.WeightUnit
import app.forkcast.core.model.forGrams
import app.forkcast.core.model.sumNutrients
import app.forkcast.core.network.ai.AiChain
import app.forkcast.core.network.ai.AiError
import app.forkcast.core.network.ai.EncodedImage
import app.forkcast.core.network.ai.MealEstimateRequest
import app.forkcast.core.network.ai.MealPrompt
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** What a background run should do next. */
sealed interface RunOutcome {
    /** Finished (DONE or NEEDS_INPUT); [notify] = post "Estimate ready" if the app is in the background. */
    data class Finished(val estimateId: String, val notify: Boolean) : RunOutcome
    /** Try again at [atMs]: a quota reset, the provider's delay, or the backoff after a slow or busy try. */
    data class RetryAt(val atMs: Long) : RunOutcome
    /** Offline: run again once there is a connection. */
    data object RetryWhenOnline : RunOutcome
    /** Stopped; the user has to act (refusal, unusable output, no profile, bad key, or tries used up). */
    data class Failed(val estimateId: String, val notifyKeyProblem: Boolean) : RunOutcome
    /** Deleted, already done, or AI turned off. */
    data object Nothing : RunOutcome
}

/** An estimate with its components, for the review screen. */
data class EstimateDetail(val estimate: AiEstimateEntity, val components: List<AiComponentEntity>)

/** Queues background estimate runs; WorkManager in the app, a fake in tests. */
interface EstimateScheduler {
    /** Run after [delayMs] (0 = now, expedited), once there is a connection. */
    fun enqueue(estimateId: String, delayMs: Long = 0)
    fun cancel(estimateId: String)
    /** A run is queued or running for this estimate (WorkManager knows about it). */
    suspend fun isScheduled(estimateId: String): Boolean
}

/** Error codes stored on the estimate so the UI can show a specific message later. */
object AiErrorCodes {
    const val NO_PROFILE = "NoProfile"
    const val DISABLED = "Disabled"
    /** Stuck in a version before 1.2 and stopped by the update: Retry starts it again. */
    const val STOPPED = "Stopped"
    const val OK = "OK"
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

    /** Errors where waiting and trying again usually works. */
    fun isTransient(code: String?): Boolean = code in setOf("Timeout", "Server", "RateLimited")
}

interface AiEstimateRepository {
    fun observeDay(date: LocalDate): Flow<List<AiEstimateEntity>>
    fun observeDetail(id: String): Flow<EstimateDetail?>
    /** Stores processed photos and a pending estimate, then queues its run. Returns the id. */
    suspend fun create(date: LocalDate, mealSlotId: String, photos: List<Uri>, description: String?, eatenMinute: Int? = null): String
    /** "Describe" (SPEC §4.6.2): the same pipeline from a description alone. */
    suspend fun createDescribed(date: LocalDate, mealSlotId: String, description: String, eatenMinute: Int? = null): String
    /** The background job: one pass through the profile chain. */
    suspend fun run(id: String): RunOutcome
    /** "Correct it" or an answer to the model's question: re-runs with the previous result as context. */
    suspend fun correct(id: String, text: String)
    /** Log a NEEDS_INPUT estimate without answering its question. */
    suspend fun acceptAsIs(id: String)
    /** "Retry now": a fresh run, profile pauses ignored once. */
    suspend fun retry(id: String)
    /** "Try with another profile": a fresh run on [profileId] alone. */
    suspend fun retryWith(id: String, profileId: String)
    /** "Edit description and retry". */
    suspend fun editDescriptionAndRetry(id: String, description: String)
    /** "Log manually instead": the estimate goes; its photos stay until the retention setting removes them. */
    suspend fun abandon(id: String)
    /** A food picked in "Change match", cached locally so the draft can point at it. */
    suspend fun localFood(food: Food): Food
    /**
     * Review "Save": the draft's components and portion replace the stored ones, picked matches are
     * remembered, and the entry is logged (or updated) without the AI·review badge.
     */
    suspend fun saveReview(id: String, components: List<AiComponentEntity>, portion: Double)
    /** "Save as food" after a described meal: a food (AI · text) with the estimate's nutrition. Returns its id. */
    suspend fun saveAsFood(id: String, name: String): String?
    suspend fun delete(id: String)
    /** Deletes photos older than the retention setting (SPEC §4.6). */
    suspend fun cleanUpPhotos()
    suspend fun markNotified(id: String)
    /**
     * At start-up: estimates left pending or running with no live job (the app was killed, an
     * older version got stuck, Doze held a retry) are queued again, or marked Failed when past
     * their cap, so every one has a status and a Retry button.
     */
    suspend fun recover()
}

@Singleton
class DefaultAiEstimateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AiDao,
    private val ai: AiRepository,
    private val photos: PhotoStore,
    private val matcher: ComponentMatcher,
    private val log: LogRepository,
    private val mealSlots: MealSlotRepository,
    private val settings: SettingsRepository,
    private val scheduler: EstimateScheduler,
    private val clock: Clock,
    private val foods: FoodRepository,
) : AiEstimateRepository {

    override fun observeDay(date: LocalDate): Flow<List<AiEstimateEntity>> = dao.observeEstimatesForDay(date)

    override fun observeDetail(id: String): Flow<EstimateDetail?> =
        combine(dao.observeEstimate(id), dao.observeComponents(id)) { e, c -> e?.takeIf { it.deletedAt == null }?.let { EstimateDetail(it, c) } }

    override suspend fun create(date: LocalDate, mealSlotId: String, photos: List<Uri>, description: String?, eatenMinute: Int?): String {
        val id = UUID.randomUUID().toString()
        val defaults = ai.defaults
        val paths = photos.take(defaults.maxImages).mapIndexed { i, uri -> this.photos.save(uri, id, i, defaults.maxImageEdgePx, defaults.jpegQuality) }
        insert(id, date, mealSlotId, paths, description, EstimateKind.PHOTO, eatenMinute)
        return id
    }

    override suspend fun createDescribed(date: LocalDate, mealSlotId: String, description: String, eatenMinute: Int?): String {
        val id = UUID.randomUUID().toString()
        insert(id, date, mealSlotId, emptyList(), description, EstimateKind.TEXT, eatenMinute)
        return id
    }

    private suspend fun insert(id: String, date: LocalDate, mealSlotId: String, paths: List<String>, description: String?, kind: EstimateKind, eatenMinute: Int?) {
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
                kind = kind, runStartedAt = now, eatenMinute = eatenMinute,
            ),
        )
        scheduler.enqueue(id)
    }

    override suspend fun run(id: String): RunOutcome {
        val e = dao.estimate(id) ?: return RunOutcome.Nothing
        if (e.deletedAt != null || e.status == EstimateStatus.DONE || e.status == EstimateStatus.NEEDS_INPUT) return RunOutcome.Nothing
        val s = settings.settings.first()
        if (!s.ai.enabled) return fail(e, AiErrorCodes.DISABLED, null, keyProblem = false)
        val runStart = e.runStartedAt ?: e.createdAt
        val tries = e.tries + 1
        patch(id) {
            it.copy(status = EstimateStatus.RUNNING, attempts = it.attempts + 1, tries = tries, phase = EstimatePhase.SENDING,
                phaseSince = clock.millis(), activeProfileName = null, retryAfter = null)
        }
        return try {
            runChain(e, s.ai.autoAccept, runStart, tries)
        } catch (c: CancellationException) {
            // WorkManager stopped the job (constraints, quota, app update): it runs again later.
            withContext(NonCancellable) { patch(id) { it.copy(status = EstimateStatus.PENDING, phase = null, activeProfileName = null) } }
            throw c
        }
    }

    private suspend fun runChain(e: AiEstimateEntity, autoAccept: Boolean, runStart: Long, tries: Int): RunOutcome = coroutineScope {
        val id = e.id
        val images = requestImages(e)
        val mealName = mealSlots.observeMealSlots().first().firstOrNull { it.id == e.mealSlotId }?.name ?: "Meal"
        val profiles = ai.profilesNow()
        val textOnly = e.kind == EstimateKind.TEXT
        val outcome = AiChain.run(
            profiles = profiles,
            now = clock.instant(),
            estimatorFor = { ai.estimatorFor(it, textOnly) },
            requestFor = { p ->
                MealEstimateRequest(
                    images = images, description = e.description, mealSlot = mealName, localTime = e.localTime,
                    unitsHint = e.unitsHint, personalContext = e.personalContext,
                    previous = e.result.takeIf { e.correction != null }, correction = e.correction, model = p.model,
                    onSent = {
                        launch { patch(id) { if (it.status == EstimateStatus.RUNNING) it.copy(phase = EstimatePhase.WAITING, phaseSince = clock.millis()) else it } }
                    },
                )
            },
            only = e.onlyProfileId,
            ignoreCooldown = e.ignoreCooldown,
            onAttempt = { p -> patch(id) { it.copy(phase = EstimatePhase.SENDING, activeProfileName = p.name, phaseSince = clock.millis()) } },
            // Each request goes into Details as soon as it ends, so the screen can say "Trying the next profile".
            onResult = { a -> patch(id) { it.copy(attemptLog = it.attemptLog.plusRecords(listOf(record(a)))) } },
        )
        ai.recordAttempts(outcome.attempts)
        val now = clock.millis()

        when (outcome) {
            is AiChain.Outcome.Success -> {
                val p = outcome.profile
                val r = outcome.result
                patch(id) { it.copy(phase = EstimatePhase.MATCHING, phaseSince = clock.millis(), activeProfileName = null) }
                val components = buildComponents(id, r.parsed.items)
                dao.replaceComponents(id, components, clock.millis())
                val cur = dao.estimate(id) ?: e
                val totals = ComponentEdits.totals(components, cur.portion)
                val question = r.parsed.clarifyingQuestion
                val done = cur.copy(
                    status = if (question != null) EstimateStatus.NEEDS_INPUT else EstimateStatus.DONE,
                    profileId = p.id, profileName = p.name, provider = p.provider, model = r.model, tierLabel = p.tierLabel,
                    rawJson = r.rawJson, result = r.parsed, warnings = r.warnings, mealName = r.parsed.mealName.ifBlank { null },
                    kcalLow = totals.low, kcalExpected = totals.expected, kcalHigh = totals.high,
                    inputTokens = r.inputTokens, outputTokens = r.outputTokens, latencyMs = r.latencyMs,
                    errorCode = null, errorDetail = null, retryAfter = null, correction = null, notified = false,
                    phase = null, phaseSince = null, activeProfileName = null, onlyProfileId = null, ignoreCooldown = false,
                )
                update(done)
                if (done.status == EstimateStatus.DONE && (autoAccept || done.logEntryId != null)) writeLogEntry(done, components)
                RunOutcome.Finished(id, notify = true)
            }
            is AiChain.Outcome.Failure -> {
                val err = outcome.finalError
                val retryAt = outcome.retryAtMs
                // Name the profile that gave this error, so the message says "Gemini rejected…".
                val last = outcome.attempts.lastOrNull { it.error === err }?.profile
                val cur = dao.estimate(id) ?: e
                val failed = cur.copy(
                    errorCode = err?.let(AiErrorCodes::of), errorDetail = err?.message,
                    profileId = last?.id ?: cur.profileId, profileName = last?.name ?: cur.profileName,
                    provider = last?.provider ?: cur.provider, tierLabel = last?.tierLabel ?: cur.tierLabel,
                    phase = null, phaseSince = null, activeProfileName = null, onlyProfileId = null, ignoreCooldown = false,
                )
                val transient = err is AiError.Timeout || err is AiError.Server || err is AiError.RateLimited
                when {
                    // Nothing could be tried: no profile, all off, or all keys rejected.
                    outcome.attempts.isEmpty() && retryAt == null ->
                        fail(failed, AiErrorCodes.NO_PROFILE, null, keyProblem = profiles.any { it.needsAttention })
                    // Offline: waits for a connection (WorkManager's network constraint), within the hard cap.
                    err is AiError.Network ->
                        if (EstimatePolicy.pastHardCap(runStart, now)) {
                            fail(failed, AiErrorCodes.of(err), err.message, keyProblem = false)
                        } else {
                            update(failed.copy(status = EstimateStatus.PENDING))
                            RunOutcome.RetryWhenOnline
                        }
                    // Every profile is paused (quota): a long wait is a daily quota, a short one a busy minute.
                    outcome.retryable && retryAt != null && retryAt - now > EstimatePolicy.SHORT_WAIT_MS ->
                        if (EstimatePolicy.pastHardCap(runStart, retryAt)) {
                            fail(failed, failed.errorCode ?: "QuotaExhausted", failed.errorDetail, keyProblem = false)
                        } else {
                            update(failed.copy(status = EstimateStatus.PENDING, retryAfter = retryAt, errorCode = failed.errorCode ?: "QuotaExhausted"))
                            RunOutcome.RetryAt(retryAt)
                        }
                    // Slow or busy: try again with backoff (the same profile when it's the only one).
                    outcome.retryable && (transient || retryAt != null) ->
                        if (EstimatePolicy.canRetryTransient(tries, runStart, now)) {
                            val at = maxOf(retryAt ?: 0L, now + EstimatePolicy.backoffMs(tries))
                            update(failed.copy(status = EstimateStatus.PENDING, retryAfter = at, errorCode = failed.errorCode ?: "RateLimited"))
                            RunOutcome.RetryAt(at)
                        } else {
                            fail(failed, failed.errorCode ?: "RateLimited", failed.errorDetail, keyProblem = false)
                        }
                    // Refusal, unusable output, or a setup problem (key, model): the user has to act.
                    else -> fail(failed, err?.let(AiErrorCodes::of) ?: AiErrorCodes.NO_PROFILE, err?.message, keyProblem = err is AiError.InvalidKey)
                }
            }
        }
    }

    /**
     * Photos for this run. "Correct it" resends only the first, smaller: the previous result
     * already lists what's on the plate (owner, 2026-10-23: corrections send only what's needed).
     */
    private suspend fun requestImages(e: AiEstimateEntity): List<EncodedImage> {
        val paths = if (e.correction != null) e.photoPaths.take(1) else e.photoPaths
        return paths.mapNotNull { p ->
            runCatching {
                val bytes = photos.read(p)
                EncodedImage(if (e.correction != null) PhotoStore.process(bytes, ai.defaults.correctionImageEdgePx, ai.defaults.jpegQuality) else bytes)
            }.getOrNull()
        }
    }

    private fun record(a: AiChain.Attempt): AttemptRecord {
        val err = a.error
        return AttemptRecord(
            atMs = a.startedAtMs, profileId = a.profile.id, profileName = a.profile.name, model = a.result?.model ?: a.profile.model,
            durationMs = a.durationMs, result = err?.let(AiErrorCodes::of) ?: AiErrorCodes.OK,
            httpStatus = err?.reply?.httpStatus, providerCode = err?.reply?.code,
            quota = (err as? AiError.RateLimited)?.quota ?: (err as? AiError.QuotaExhausted)?.quota,
            message = (err?.reply?.message ?: err?.message)?.take(300),
            retryAfterMs = (err as? AiError.RateLimited)?.retryAfterMs,
        )
    }

    private fun List<AttemptRecord>.plusRecords(more: List<AttemptRecord>) = (this + more).takeLast(EstimatePolicy.MAX_ATTEMPT_RECORDS)

    private suspend fun fail(e: AiEstimateEntity, code: String, detail: String?, keyProblem: Boolean): RunOutcome {
        update(e.copy(status = EstimateStatus.FAILED, errorCode = code, errorDetail = detail, retryAfter = null, phase = null, activeProfileName = null))
        return RunOutcome.Failed(e.id, keyProblem)
    }

    private suspend fun buildComponents(estimateId: String, items: List<EstimatedItem>): List<AiComponentEntity> {
        val now = clock.millis()
        return items.mapIndexed { i, item ->
            val aiPer100g = item.nutrients.toNutrients().mapValues { (_, v) -> v * 100.0 / item.grams }
            val match = matcher.match(item)
            val source = if (match != null) app.forkcast.core.data.db.ComponentSource.USDA else app.forkcast.core.data.db.ComponentSource.AI
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

    /**
     * The estimate's log entry: the components × the portion eaten, its meal time, and the water
     * in drinks among the components (bug, owner 2026-10-23: sparkling water in a photo meal
     * didn't count toward water).
     */
    private suspend fun writeLogEntry(e: AiEstimateEntity, components: List<AiComponentEntity>) {
        val portion = e.portion.takeIf { it > 0 } ?: 1.0
        val nutrients: Nutrients = components.map { it.nutrientsUsed }.sumNutrients().mapValues { (_, v) -> v * portion }
        val grams = components.sumOf { it.grams } * portion
        val waterMl = components.filter { DrinkWater.snapshotCountsAsWater(it.name, it.usdaQuery, it.nutrientsUsed, it.grams) }
            .sumOf { it.grams * portion }.takeIf { it > 0 }
        val name = e.mealName ?: e.result?.mealName?.ifBlank { null } ?: context.getString(if (e.kind == EstimateKind.TEXT) R.string.described_meal else R.string.photo_meal)
        val source = if (e.kind == EstimateKind.TEXT) FoodSource.AI_TEXT else FoodSource.AI
        val existing = e.logEntryId?.let { log.get(it) }
        if (existing != null) {
            log.update(existing.copy(displayName = name, quantity = portion, grams = grams, nutrients = nutrients, waterMl = waterMl, source = source))
        } else {
            val entry = LogEntry(
                id = "", date = e.date, mealSlotId = e.mealSlotId, foodId = null, displayName = name, brand = null,
                quantity = portion, servingLabel = "meal", grams = grams, nutrients = nutrients, source = source,
                note = null, loggedAt = clock.instant(), aiEstimateId = e.id, waterMl = waterMl,
                eatenMinute = e.eatenMinute,
            )
            val entryId = log.add(listOf(entry)).single()
            patch(e.id) { it.copy(logEntryId = entryId) }
        }
    }

    override suspend fun correct(id: String, text: String) {
        if (text.isBlank()) return
        patch(id) { it.copy(status = EstimateStatus.PENDING, correction = text.trim(), errorCode = null, errorDetail = null, retryAfter = null, runStartedAt = clock.millis(), tries = 0) }
        scheduler.enqueue(id)
    }

    override suspend fun acceptAsIs(id: String) {
        val e = dao.estimate(id) ?: return
        if (e.status != EstimateStatus.NEEDS_INPUT) return
        val done = e.copy(status = EstimateStatus.DONE)
        update(done)
        writeLogEntry(done, dao.components(id))
    }

    override suspend fun retry(id: String) = restart(id) { it.copy(ignoreCooldown = true, onlyProfileId = null) }

    override suspend fun retryWith(id: String, profileId: String) = restart(id) { it.copy(onlyProfileId = profileId, ignoreCooldown = true) }

    override suspend fun editDescriptionAndRetry(id: String, description: String) =
        restart(id) { it.copy(description = description.trim().takeIf { d -> d.isNotBlank() }, ignoreCooldown = true, onlyProfileId = null) }

    private suspend fun restart(id: String, change: (AiEstimateEntity) -> AiEstimateEntity) {
        val e = dao.estimate(id) ?: return
        if (e.deletedAt != null) return
        // A running pass would be cancelled mid-request (already counted by the provider): let it finish.
        if (e.status == EstimateStatus.RUNNING && scheduler.isScheduled(id)) return
        update(change(e).copy(status = EstimateStatus.PENDING, errorCode = null, errorDetail = null, retryAfter = null, runStartedAt = clock.millis(), tries = 0, phase = null))
        scheduler.enqueue(id)
    }

    override suspend fun abandon(id: String) {
        val e = dao.estimate(id) ?: return
        scheduler.cancel(id)
        e.logEntryId?.let { log.delete(listOf(it)) }
        update(e.copy(deletedAt = clock.millis(), status = if (e.status == EstimateStatus.DONE) e.status else EstimateStatus.FAILED))
    }

    override suspend fun localFood(food: Food): Food {
        val localId = if (food.id.isNotEmpty() && foods.get(food.id) != null) food.id else foods.cache(food)
        return foods.get(localId) ?: food.copy(id = localId)
    }

    override suspend fun saveReview(id: String, components: List<AiComponentEntity>, portion: Double) {
        val e = dao.estimate(id) ?: return
        val stored = dao.components(id).associateBy { it.id }
        val now = clock.millis()
        val kept = components.map { it.copy(updatedAt = now) }
        val removed = stored.values.filter { s -> kept.none { it.id == s.id } }.map { it.copy(deletedAt = now, updatedAt = now) }
        dao.upsertComponents(kept + removed)
        // "Change match" picks are remembered for this food from now on (SPEC §4.6 step 8).
        kept.filter { it.matchScore == ComponentMatcher.USER_CHOICE_SCORE && it.matchedFoodId != stored[it.id]?.matchedFoodId }
            .forEach { c -> c.matchedFoodId?.let { fid -> foods.get(fid) }?.let { matcher.remember(c.usdaQuery, c.preparation, it) } }
        val p = portion.coerceIn(0.01, 10.0)
        val t = ComponentEdits.totals(kept, p)
        val saved = e.copy(
            status = if (e.status == EstimateStatus.NEEDS_INPUT) EstimateStatus.DONE else e.status, reviewedAt = now, portion = p,
            kcalLow = t.low, kcalExpected = t.expected, kcalHigh = t.high,
        )
        update(saved)
        if (saved.status == EstimateStatus.DONE) writeLogEntry(dao.estimate(id) ?: saved, kept)
    }

    override suspend fun saveAsFood(id: String, name: String): String? {
        val e = dao.estimate(id) ?: return null
        val components = dao.components(id)
        val grams = components.sumOf { it.grams }
        if (grams <= 0) return null
        val total = components.map { it.nutrientsUsed }.sumNutrients()
        val waterOnly = components.isNotEmpty() && components.all { DrinkWater.snapshotCountsAsWater(it.name, it.usdaQuery, it.nutrientsUsed, it.grams) }
        val foodId = foods.saveCustom(
            Food(
                id = "", name = name.trim().ifBlank { e.mealName ?: e.description.orEmpty() }, brand = null, source = FoodSource.AI_TEXT,
                sourceId = e.id, barcode = null, servingSizes = listOf(ServingSize(SERVING_LABEL, grams)),
                nutrientsPer100g = total.mapValues { (_, v) -> v * 100.0 / grams },
                isFavorite = false, lastUsedAt = null, lastQuantity = null, lastServingLabel = null,
                countsAsWater = if (waterOnly) true else null, describedAs = e.description,
            ),
        )
        // The logged entry points at the new food, so it shows in Recents too.
        e.logEntryId?.let { log.get(it) }?.let { log.update(it.copy(foodId = foodId)) }
        return foodId
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
        patch(id) { it.copy(notified = true) }
    }

    private val recovering = kotlinx.coroutines.sync.Mutex()

    override suspend fun recover() = recovering.withLock { recoverLocked() }

    private suspend fun recoverLocked() {
        val now = clock.millis()
        for (e in dao.unfinishedEstimates()) {
            val live = scheduler.isScheduled(e.id)
            val start = e.runStartedAt
            when {
                // From a version before 1.2: no run start, maybe a retry hours away. Stop it so
                // the new status screen and Retry work on it; a recent one simply starts again.
                start == null -> if (now - e.createdAt > EstimatePolicy.TRANSIENT_CAP_MS) {
                    scheduler.cancel(e.id)
                    update(e.copy(status = EstimateStatus.FAILED, errorCode = e.errorCode ?: AiErrorCodes.STOPPED, retryAfter = null, phase = null))
                } else {
                    update(e.copy(status = EstimateStatus.PENDING, runStartedAt = now, tries = 0, retryAfter = null, phase = null))
                    scheduler.enqueue(e.id)
                }
                EstimatePolicy.pastHardCap(start, now) -> {
                    scheduler.cancel(e.id)
                    update(e.copy(status = EstimateStatus.FAILED, errorCode = e.errorCode ?: AiErrorCodes.STOPPED, retryAfter = null, phase = null))
                }
                // Killed mid-run, or its job is gone: queue it again (at its retry time, if one is set).
                !live -> {
                    update(e.copy(status = EstimateStatus.PENDING, phase = null, activeProfileName = null))
                    scheduler.enqueue(e.id, delayMs = ((e.retryAfter ?: now) - now).coerceAtLeast(0))
                }
                // Doze held a timed retry well past its time: run it now.
                e.status == EstimateStatus.PENDING && e.retryAfter != null && now - e.retryAfter > EstimatePolicy.LATE_MS ->
                    scheduler.enqueue(e.id)
            }
        }
    }

    /** Reads the current row, changes it and writes it back (phase updates race with the run otherwise). */
    private suspend fun patch(id: String, change: (AiEstimateEntity) -> AiEstimateEntity) {
        dao.estimate(id)?.let { update(change(it)) }
    }

    private suspend fun update(e: AiEstimateEntity) = dao.upsertEstimate(e.copy(updatedAt = clock.millis()))

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
        /** The serving of a food saved from "Describe": the meal as described (shown as "serving"). */
        const val SERVING_LABEL = "serving"

        fun unitsHint(units: UnitPrefs?): String = if (units?.weight == WeightUnit.KG) "metric" else "US customary"

        /** A new estimate's meal time: now for today, else none (the log fills in the meal's typical time). */
        fun defaultEatenMinute(date: LocalDate, clock: Clock): Int? =
            MealTimes.minuteOn(date, clock.instant(), clock.zone)
    }
}
