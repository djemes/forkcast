package app.forkcast.core.data.health

import android.content.Context
import android.os.RemoteException
import androidx.datastore.core.DataStore
import androidx.room.withTransaction
import app.forkcast.core.data.db.ActivityDayEntity
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.HealthDao
import app.forkcast.core.data.db.LogEntryEntity
import app.forkcast.core.data.db.MealSlotDao
import app.forkcast.core.data.db.WaterEntryEntity
import app.forkcast.core.data.db.WeightEntryEntity
import app.forkcast.core.domain.HealthRules
import app.forkcast.core.model.ActivityDay
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.HealthSettings
import app.forkcast.core.model.WeightSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** What Settings › Health Connect shows. */
data class HealthStatus(
    val availability: HcAvailability,
    val settings: HealthSettings,
    /** What Health Connect has granted Forkcast right now (refreshed on [HealthRepository.refresh]). */
    val granted: Set<String>,
    val historySupported: Boolean,
    val backgroundSupported: Boolean,
    val lastSyncAt: Instant?,
    val lastError: HealthError?,
    /** Latest height in Health Connect, for an offered profile update. */
    val heightCm: Double?,
) {
    val connected get() = settings.connected && availability == HcAvailability.AVAILABLE
}

enum class SyncReason {
    /** App opened: reads and writes, throttled. */
    OPEN,
    /** Sync now: reads and writes, never throttled. */
    MANUAL,
    /** Periodic worker: writes, and reads when background reads are granted. */
    BACKGROUND,
    /** A log or water change: writes only. */
    WRITES,
}

interface HealthRepository {
    val status: Flow<HealthStatus>
    /** Re-asks Health Connect what is granted (call when the screen resumes). */
    suspend fun refresh()
    /** Permissions to request for the current choices. */
    suspend fun permissionsToRequest(): Set<String>
    /** After the Health Connect permission screen: connects if anything was granted, then syncs. */
    suspend fun onPermissionsResult(granted: Set<String>)
    suspend fun updateSettings(transform: (HealthSettings) -> HealthSettings)
    /** Revokes every permission and stops syncing. What was written stays in Health Connect. */
    suspend fun disconnect()
    suspend fun sync(reason: SyncReason)
    fun observeActivity(date: LocalDate): Flow<ActivityDay?>
    fun observeActivityRange(from: LocalDate, to: LocalDate): Flow<List<ActivityDay>>
    suspend fun activityRange(from: LocalDate, to: LocalDate): List<ActivityDay>
}

/**
 * Health Connect sync (SPEC §4.9). Reads weight/body fat (changes tokens, full history when
 * granted), height, and daily activity; writes every log entry as a Nutrition record and every
 * water entry as Hydration, keyed by our row id so edits replace and deletions remove them.
 * Nothing here needs the network. Failures are recorded and retried on the next sync.
 */
@Singleton
class HealthConnectRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source: HealthConnectSource,
    private val db: ForkcastDatabase,
    private val dao: HealthDao,
    private val slots: MealSlotDao,
    private val settingsStore: DataStore<AppSettings>,
    @Named(HEALTH_SYNC_STATE) private val stateStore: DataStore<HealthSyncState>,
    private val clock: Clock,
) : HealthRepository {

    private val mutex = Mutex()
    private val granted = MutableStateFlow<Set<String>>(emptySet())
    private val supported = MutableStateFlow(false to false)

    override val status: Flow<HealthStatus> = combine(
        settingsStore.data.map { it.health },
        stateStore.data,
        granted,
        supported,
    ) { settings, state, granted, (history, background) ->
        HealthStatus(
            availability = source.availability(),
            settings = settings,
            granted = granted,
            historySupported = history,
            backgroundSupported = background,
            lastSyncAt = state.lastSyncAt?.let(Instant::ofEpochMilli),
            lastError = state.lastError,
            heightCm = state.heightCm,
        )
    }

    override suspend fun refresh() {
        if (source.availability() != HcAvailability.AVAILABLE) {
            granted.value = emptySet()
            return
        }
        granted.value = runCatching { source.grantedPermissions() }.getOrDefault(emptySet())
        supported.value = runCatching { source.historyReadSupported() to source.backgroundReadSupported() }.getOrDefault(false to false)
    }

    override suspend fun permissionsToRequest(): Set<String> {
        val settings = settingsStore.data.first().health
        return HealthPermissions.wanted(
            settings,
            historySupported = runCatching { source.historyReadSupported() }.getOrDefault(false),
            backgroundSupported = runCatching { source.backgroundReadSupported() }.getOrDefault(false),
        )
    }

    override suspend fun onPermissionsResult(granted: Set<String>) {
        this.granted.value = granted
        if (granted.intersect(HealthPermissions.ALL).isEmpty()) return
        settingsStore.updateData { it.copy(health = it.health.copy(connected = true)) }
        sync(SyncReason.MANUAL)
    }

    override suspend fun updateSettings(transform: (HealthSettings) -> HealthSettings) {
        settingsStore.updateData { it.copy(health = transform(it.health)) }
    }

    override suspend fun disconnect() = mutex.withLock {
        runCatching { source.revokeAll() }
        settingsStore.updateData { it.copy(health = it.health.copy(connected = false)) }
        stateStore.updateData { HealthSyncState() }
        // A later reconnect writes everything again; writes are idempotent by client record id.
        db.withTransaction {
            dao.resetLogSync()
            dao.resetWaterSync()
        }
        granted.value = emptySet()
    }

    override suspend fun sync(reason: SyncReason) = mutex.withLock {
        val settings = settingsStore.data.first().health
        if (!settings.connected) return@withLock
        if (source.availability() != HcAvailability.AVAILABLE) {
            stateStore.updateData { it.copy(lastError = HealthError.UNAVAILABLE) }
            return@withLock
        }
        if (!stateStore.data.first().writesInitialized) {
            db.withTransaction {
                dao.resetLogSync()
                dao.resetWaterSync()
            }
            stateStore.updateData { it.copy(writesInitialized = true) }
        }
        val state = stateStore.data.first()
        val now = clock.instant()
        val recent = state.lastSyncAt?.let { Duration.between(Instant.ofEpochMilli(it), now) < OPEN_THROTTLE } == true
        val reads = when (reason) {
            SyncReason.MANUAL -> true
            SyncReason.OPEN -> !recent
            SyncReason.BACKGROUND -> true
            SyncReason.WRITES -> false
        }
        val perms = try {
            source.grantedPermissions().also { granted.value = it }
        } catch (e: Exception) {
            stateStore.updateData { it.copy(lastError = e.toHealthError()) }
            return@withLock
        }
        val canRead = reads && (reason != SyncReason.BACKGROUND || HealthPermissions.READ_IN_BACKGROUND in perms)
        var error: HealthError? = null
        suspend fun step(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = error ?: e.toHealthError()
            }
        }
        step { writeNutrition(settings, perms) }
        step { writeHydration(settings, perms) }
        if (canRead) {
            step { syncBody(settings, perms) }
            step { syncActivity(settings, perms) }
        }
        if (canRead || error != null) {
            stateStore.updateData { it.copy(lastSyncAt = if (canRead) now.toEpochMilli() else it.lastSyncAt, lastError = error) }
        }
    }

    override fun observeActivity(date: LocalDate): Flow<ActivityDay?> = dao.observeActivity(date).map { it?.toModel() }

    override fun observeActivityRange(from: LocalDate, to: LocalDate): Flow<List<ActivityDay>> =
        dao.observeActivityRange(from, to).map { rows -> rows.map { it.toModel() } }

    override suspend fun activityRange(from: LocalDate, to: LocalDate): List<ActivityDay> =
        dao.activityRange(from, to).map { it.toModel() }

    // --- Writes --------------------------------------------------------------------------

    private suspend fun writeNutrition(settings: HealthSettings, perms: Set<String>) {
        if (!settings.writeNutrition || HealthPermissions.WRITE_NUTRITION !in perms) return
        val slotsById = slots.all().associateBy { it.id }
        repeat(MAX_BATCHES) {
            val batch = dao.pendingLogEntries(BATCH)
            if (batch.isEmpty()) return
            val (deleted, live) = batch.partition { it.deletedAt != null }
            // Drinks that count toward water are also Hydration records ("drink-<id>"), when water is written.
            val water = settings.writeHydration && HealthPermissions.WRITE_HYDRATION in perms
            // Only rows that were written have something to delete; deleting unknown ids can fail the call.
            deleteEach(deleted.filter { it.healthConnectRecordId != null }.map { it.id }) { source.deleteNutrition(it) }
            if (water) {
                val gone = deleted.filter { it.hcWaterRecordId != null } + live.filter { it.hcWaterRecordId != null && (it.waterMl ?: 0.0) <= 0.0 }
                deleteEach(gone.map { drinkId(it.id) }) { source.deleteHydration(it) }
            }
            deleted.forEach { dao.markLogEntrySynced(it.id, null, null, it.updatedAt) }
            val drinkIds = if (!water) emptyMap() else {
                val drinks = live.filter { (it.waterMl ?: 0.0) > 0.0 }.map { it.toDrinkWrite() }
                val ids = HashMap<String, String?>()
                writeEach(drinks, write = { source.writeHydration(it) }) { w, recordId -> ids[w.clientId] = recordId }
                ids
            }
            val writes = live.map { it.toWrite(slotsById[it.mealSlotId]) }
            val byId = live.associateBy { it.id }
            writeEach(writes, write = { source.writeNutrition(it) }) { w, recordId ->
                val row = byId.getValue(w.clientId)
                val waterId = if (water) drinkIds[drinkId(row.id)] else row.hcWaterRecordId
                dao.markLogEntrySynced(w.clientId, recordId, waterId, w.version)
            }
            if (batch.size < BATCH) return
        }
    }

    private suspend fun writeHydration(settings: HealthSettings, perms: Set<String>) {
        if (!settings.writeHydration || HealthPermissions.WRITE_HYDRATION !in perms) return
        repeat(MAX_BATCHES) {
            val batch = dao.pendingWater(BATCH)
            if (batch.isEmpty()) return
            val (deleted, live) = batch.partition { it.deletedAt != null }
            deleteEach(deleted.filter { it.hcRecordId != null }.map { it.id }) { source.deleteHydration(it) }
            deleted.forEach { dao.markWaterSynced(it.id, null, it.updatedAt) }
            val writes = live.map { it.toWrite() }
            writeEach(writes, write = { source.writeHydration(it) }) { w, recordId ->
                dao.markWaterSynced(w.clientId, recordId, w.version)
            }
            if (batch.size < BATCH) return
        }
    }

    /**
     * Writes a batch; if Health Connect rejects it, retries one by one so a single bad record
     * (rejected as invalid) is skipped instead of blocking every later write. Other failures
     * (permission, quota, I/O) propagate and the rows stay pending.
     */
    private suspend fun <T> writeEach(items: List<T>, write: suspend (List<T>) -> List<String>, done: suspend (T, String?) -> Unit) {
        if (items.isEmpty()) return
        val ids = try {
            write(items)
        } catch (e: IllegalArgumentException) {
            items.forEach { item ->
                val id = try {
                    write(listOf(item)).firstOrNull()
                } catch (_: IllegalArgumentException) {
                    null // invalid for Health Connect: marked done so it isn't retried forever
                }
                done(item, id)
            }
            return
        }
        items.zip(ids).forEach { (item, id) -> done(item, id) }
    }

    private suspend fun deleteEach(clientIds: List<String>, delete: suspend (List<String>) -> Unit) {
        if (clientIds.isEmpty()) return
        try {
            delete(clientIds)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || e.isPassing()) throw e
            // One unknown id fails the whole call; delete one at a time and skip the ones already gone.
            // A passing failure (Health Connect busy or unreachable) propagates, so the rows stay
            // pending and the next sync deletes them; otherwise the record would stay in Health
            // Connect (and Samsung Health) for good.
            clientIds.forEach { id ->
                runCatching { delete(listOf(id)) }.exceptionOrNull()?.let { if (it is SecurityException || it.isPassing()) throw it }
            }
        }
    }

    /** Errors worth trying again later, as opposed to a record that no longer exists. */
    private fun Throwable.isPassing(): Boolean = when (this) {
        is RemoteException, is IOException -> true
        // Health Connect unavailable or rate limited.
        is IllegalStateException -> true
        else -> false
    }

    private fun LogEntryEntity.toWrite(slot: app.forkcast.core.data.db.MealSlotEntity?): NutritionWrite {
        val zone = zoneOf(zoneId)
        val start = HealthRules.entryStart(date, eatenMinute, Instant.ofEpochMilli(loggedAt), zone, slot?.startMinute)
        return NutritionWrite(
            clientId = id,
            version = updatedAt,
            start = start,
            offset = zone.rules.getOffset(start),
            name = listOfNotNull(displayName, brand).joinToString(" · "),
            meal = HealthRules.mealKind(slot?.name, slot?.startMinute),
            nutrients = nutrients,
        )
    }

    private fun drinkId(entryId: String) = "drink-$entryId"

    private fun LogEntryEntity.toDrinkWrite(): HydrationWrite {
        val zone = zoneOf(zoneId)
        val start = HealthRules.entryStart(date, eatenMinute, Instant.ofEpochMilli(loggedAt), zone, null)
        return HydrationWrite(drinkId(id), updatedAt, start, zone.rules.getOffset(start), waterMl ?: 0.0)
    }

    private fun WaterEntryEntity.toWrite(): HydrationWrite {
        val zone = zoneOf(zoneId)
        val start = HealthRules.nutritionStart(date, Instant.ofEpochMilli(timestamp), zone, null)
        return HydrationWrite(id, updatedAt, start, zone.rules.getOffset(start), ml)
    }

    // --- Reads ---------------------------------------------------------------------------

    private suspend fun syncBody(settings: HealthSettings, perms: Set<String>) {
        if (!settings.readWeight) return
        if (HealthPermissions.READ_HEIGHT in perms) {
            val height = source.latestHeightCm(clock.instant().minus(HEIGHT_LOOKBACK))
            stateStore.updateData { it.copy(heightCm = height) }
        }
        if (HealthPermissions.READ_WEIGHT !in perms) return
        val withFat = HealthPermissions.READ_BODY_FAT in perms
        val history = HealthPermissions.READ_HISTORY in perms
        val state = stateStore.data.first()
        val saved = state.bodyToken
        val full = saved == null || state.bodyTokenHasBodyFat != withFat || (history && !state.weightsFullHistory)
        if (!full) {
            var token: String = saved
            repeat(MAX_BATCHES) {
                val changes = source.bodyChanges(token)
                if (changes.expired) {
                    fullBodyRead(withFat, history)
                    return
                }
                applyBody(changes.weights, changes.bodyFat, changes.deletedIds)
                token = changes.nextToken
                stateStore.updateData { it.copy(bodyToken = token) }
                if (!changes.hasMore) return
            }
            return
        }
        fullBodyRead(withFat, history)
    }

    /** Reads every weigh-in in range and reconciles: Health Connect rows we no longer see were deleted there. */
    private suspend fun fullBodyRead(withFat: Boolean, history: Boolean) {
        // Token first, so nothing written during the read is missed.
        val token = source.bodyChangesToken(withFat)
        val now = clock.instant()
        // Without the history permission Health Connect serves 30 days before the first grant and
        // rejects older reads (developer.android.com, Read data, checked 2026-10-20); 29 days stays inside.
        val from = if (history) HISTORY_START else now.minus(NO_HISTORY_WINDOW)
        val weights = source.readWeights(from, now.plus(Duration.ofDays(1)))
        val fat = if (withFat) source.readBodyFat(from, now.plus(Duration.ofDays(1))) else emptyList()
        val seen = weights.map { it.id }.toSet()
        val gone = dao.healthConnectWeightsSince(from.toEpochMilli()).mapNotNull { it.hcRecordId }.filter { it !in seen }
        applyBody(weights, fat, gone)
        stateStore.updateData { it.copy(bodyToken = token, bodyTokenHasBodyFat = withFat, weightsFullHistory = history) }
    }

    private suspend fun applyBody(weights: List<HcWeight>, bodyFat: List<HcBodyFat>, deletedIds: List<String>) = db.withTransaction {
        val now = clock.millis()
        val own = context.packageName
        val window = HealthRules.WEIGHT_DEDUPE_WINDOW.toMillis()
        for (w in weights.filter { it.origin != own }) {
            val t = w.time.toEpochMilli()
            val existing = dao.weightByHcId(w.id)
            if (existing != null) {
                // A reading deleted in Forkcast stays deleted; otherwise follow Health Connect's edits.
                if (existing.deletedAt == null && (existing.kg != w.kg || existing.timestamp != t)) {
                    dao.upsertWeights(listOf(existing.copy(kg = w.kg, timestamp = t, updatedAt = now)))
                }
                continue
            }
            // The same weigh-in typed by hand: the scale's reading wins (SPEC §4.9).
            val manual = HealthRules.duplicateOf(w.time, dao.manualWeightsBetween(t - window, t + window)) { Instant.ofEpochMilli(it.timestamp) }
            manual?.let { dao.upsertWeights(listOf(it.copy(deletedAt = now, updatedAt = now))) }
            dao.upsertWeights(
                listOf(WeightEntryEntity(UUID.randomUUID().toString(), t, w.kg, manual?.bodyFatPct, WeightSource.HEALTH_CONNECT, w.id, now, now)),
            )
        }
        for (f in bodyFat.filter { it.origin != own }) {
            val t = f.time.toEpochMilli()
            val target = HealthRules.duplicateOf(f.time, dao.weightsBetween(t - window, t + window)) { Instant.ofEpochMilli(it.timestamp) }
            if (target != null && target.bodyFatPct != f.pct) {
                dao.upsertWeights(listOf(target.copy(bodyFatPct = f.pct, updatedAt = now)))
            }
        }
        if (deletedIds.isNotEmpty()) {
            dao.weightsByHcIds(deletedIds).filter { it.deletedAt == null }
                .forEach { dao.upsertWeights(listOf(it.copy(deletedAt = now, updatedAt = now))) }
        }
    }

    private suspend fun syncActivity(settings: HealthSettings, perms: Set<String>) {
        if (!settings.readActivity || perms.intersect(HealthPermissions.ACTIVITY).isEmpty()) return
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val state = stateStore.data.first()
        val history = HealthPermissions.READ_HISTORY in perms
        val backfill = if (history) BACKFILL_DAYS_HISTORY else BACKFILL_DAYS
        val days = if (state.activityBackfilled && state.activityBackfillDays >= backfill) REFRESH_DAYS else backfill
        val from = today.minusDays(days - 1L)
        val fresh = source.activity(from, today, zone, perms).associateBy { it.date }
        db.withTransaction {
            val now = clock.millis()
            val existing = dao.activityRangeAll(from, today).associateBy { it.date }
            val rows = ArrayList<ActivityDayEntity>()
            var d = from
            while (!d.isAfter(today)) {
                val day = fresh[d]
                val old = existing[d]
                when {
                    day != null && old == null -> rows += day.toEntity(UUID.randomUUID().toString(), now, now)
                    day != null && old != null && (old.deletedAt != null || old.toModel() != day) ->
                        rows += day.toEntity(old.id, old.createdAt, now)
                    // Data for that day was removed from Health Connect.
                    day == null && old != null && old.deletedAt == null -> rows += old.copy(deletedAt = now, updatedAt = now)
                }
                d = d.plusDays(1)
            }
            dao.upsertActivity(rows)
        }
        if (days == backfill) stateStore.updateData { it.copy(activityBackfilled = true, activityBackfillDays = backfill) }
    }

    private fun zoneOf(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(clock.zone)

    private fun Exception.toHealthError(): HealthError = when (this) {
        is SecurityException -> HealthError.PERMISSION
        is IllegalStateException -> if (message.orEmpty().contains("quota", ignoreCase = true) || message.orEmpty().contains("rate", ignoreCase = true)) HealthError.RATE_LIMITED else HealthError.FAILED
        is RemoteException, is IOException -> HealthError.FAILED
        else -> HealthError.FAILED
    }

    companion object {
        const val HEALTH_SYNC_STATE = "healthSyncState"
        /** App-private and excluded from Auto Backup and device transfer. */
        const val SYNC_DIR = "health-sync"
        private const val BATCH = 100
        private const val MAX_BATCHES = 50
        private val OPEN_THROTTLE: Duration = Duration.ofMinutes(5)
        private val NO_HISTORY_WINDOW: Duration = Duration.ofDays(29)
        private val HEIGHT_LOOKBACK: Duration = Duration.ofDays(3650)
        private val HISTORY_START: Instant = Instant.parse("2000-01-01T00:00:00Z")
        private const val BACKFILL_DAYS = 29
        private const val BACKFILL_DAYS_HISTORY = 90
        private const val REFRESH_DAYS = 7
    }
}

fun ActivityDayEntity.toModel() = ActivityDay(date, steps, activeKcal, totalKcal, exerciseMinutes, sessions)

private fun ActivityDay.toEntity(id: String, createdAt: Long, now: Long) =
    ActivityDayEntity(id, date, steps, activeKcal, totalKcal, exerciseMinutes, sessions, createdAt, now)
