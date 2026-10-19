package app.forkcast.core.data.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.room.withTransaction
import app.forkcast.core.data.db.BackupDao
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.GoalEntity
import app.forkcast.core.data.db.MealSlotEntity
import app.forkcast.core.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

const val APP_VERSION = "appVersion"

enum class ImportMode { MERGE, REPLACE }

enum class RejectReason { UNREADABLE, NOT_FORKCAST, NEWER_VERSION }

/** Visible (not deleted) row counts, for the import preview. */
data class BackupCounts(
    val foods: Int,
    val logEntries: Int,
    val savedMeals: Int,
    val weights: Int,
    val water: Int,
    val goals: Int,
    val hasProfile: Boolean,
    /** Export v2 (Phase 2); 0 for v1 files. */
    val aiEstimates: Int = 0,
    val aiProfiles: Int = 0,
)

data class BackupPreview(
    val exportedAt: Instant?,
    val appVersion: String,
    val counts: BackupCounts,
    /** First and last day with any logged food, water, or weight. */
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
)

sealed interface ParsedBackup {
    data class Ok(val export: ForkcastExport, val preview: BackupPreview) : ParsedBackup
    data class Rejected(val reason: RejectReason) : ParsedBackup
}

data class ImportSummary(
    val mode: ImportMode,
    /** Rows new to this phone. */
    val added: Int,
    /** Rows that replaced an older copy. */
    val updated: Int,
    /** Rows skipped because this phone's copy is the same or newer (merge only). */
    val unchanged: Int,
)

/** JSON and CSV export, import with preview (SPEC §4.11, §6.3). */
interface BackupRepository {
    suspend fun exportJson(): String
    suspend fun foodLogCsv(): String
    suspend fun weightsCsv(): String
    fun parse(text: String): ParsedBackup
    suspend fun import(export: ForkcastExport, mode: ImportMode): ImportSummary
    /** The newest safety backup taken before a Replace, or null if there is none. */
    suspend fun readSafetyBackup(): String?
}

/**
 * JSON export/import (SPEC §4.11, §6.3). Merge upserts by id with the newer updatedAt
 * winning; soft-deleted rows travel like any other row, so a newer deletion wins too.
 * Replace wipes everything after writing a safety backup to app storage.
 */
@Singleton
class RoomBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ForkcastDatabase,
    private val settings: DataStore<AppSettings>,
    private val clock: Clock,
    @Named(APP_VERSION) private val appVersion: String,
    private val csv: CsvExporter,
) : BackupRepository {
    private val dao: BackupDao = db.backupDao()

    private val safetyDir get() = File(context.filesDir, SAFETY_DIR)

    suspend fun export(): ForkcastExport = db.withTransaction {
        ForkcastExport(
            appVersion = appVersion,
            exportedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString(),
            timeZone = clock.zone.id,
            profile = dao.profiles().firstOrNull(),
            goals = dao.goals(),
            targetOverrides = dao.targetOverrides(),
            mealSlots = dao.mealSlots(),
            foods = dao.foods(),
            savedMeals = dao.savedMeals(),
            savedMealItems = dao.savedMealItems(),
            logEntries = dao.logEntries(),
            weights = dao.weights(),
            water = dao.water(),
            dayStatus = dao.dayStatus(),
            aiEstimates = dao.aiEstimates(),
            aiComponents = dao.aiComponents(),
            aiProfiles = dao.aiProfiles(),
            usageRecords = dao.usageRecords(),
            notificationLog = dao.notificationLog(),
            aiMatchCache = dao.aiMatchCache(),
            tdeeEstimates = dao.tdeeEstimates(),
            activityDays = dao.activityDays(),
        )
    }.copy(settings = settings.data.first())

    override suspend fun exportJson(): String = BackupJson.encodeToString(ForkcastExport.serializer(), export())

    override suspend fun foodLogCsv(): String = csv.foodLog()

    override suspend fun weightsCsv(): String = csv.weights()

    override fun parse(text: String): ParsedBackup {
        val root = try {
            BackupJson.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) { // includes SerializationException
            return ParsedBackup.Rejected(RejectReason.UNREADABLE)
        }
        if (root.string("app") != "Forkcast") return ParsedBackup.Rejected(RejectReason.NOT_FORKCAST)
        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: return ParsedBackup.Rejected(RejectReason.UNREADABLE)
        if (version > ForkcastExport.SCHEMA_VERSION) return ParsedBackup.Rejected(RejectReason.NEWER_VERSION)
        // v1 → v2 → v3 only added arrays and nullable fields, which default to empty/null;
        // older files decode as they are (v1/v2 wrote tdeeEstimates and activityDays as []).
        val export = try {
            BackupJson.decodeFromJsonElement(ForkcastExport.serializer(), root)
        } catch (e: IllegalArgumentException) {
            return ParsedBackup.Rejected(RejectReason.UNREADABLE)
        } catch (e: DateTimeException) {
            return ParsedBackup.Rejected(RejectReason.UNREADABLE)
        }
        return ParsedBackup.Ok(export, preview(export))
    }

    override suspend fun import(export: ForkcastExport, mode: ImportMode): ImportSummary = when (mode) {
        ImportMode.REPLACE -> replace(export)
        ImportMode.MERGE -> merge(export)
    }

    override suspend fun readSafetyBackup(): String? = withContext(Dispatchers.IO) {
        safetyDir.listFiles { f -> f.name.endsWith(".json") }?.maxByOrNull { it.name }?.readText()
    }

    private suspend fun replace(export: ForkcastExport): ImportSummary {
        writeSafetyBackup()
        db.withTransaction {
            dao.clearAll()
            export.profile?.let { dao.upsertProfiles(listOf(it)) }
            dao.upsertGoals(export.goals)
            dao.upsertTargetOverrides(export.targetOverrides)
            dao.upsertMealSlots(export.mealSlots)
            dao.upsertFoods(export.foods)
            dao.upsertSavedMeals(export.savedMeals)
            dao.upsertSavedMealItems(export.savedMealItems)
            dao.upsertLogEntries(export.logEntries)
            dao.upsertWeights(export.weights)
            dao.upsertWater(export.water)
            dao.upsertDayStatus(export.dayStatus)
            dao.upsertAiEstimates(export.aiEstimates)
            dao.upsertAiComponents(export.aiComponents)
            dao.upsertAiProfiles(export.aiProfiles)
            dao.upsertUsageRecords(export.usageRecords)
            dao.upsertNotificationLog(export.notificationLog)
            dao.upsertAiMatchCache(export.aiMatchCache)
            dao.upsertTdeeEstimates(export.tdeeEstimates)
            dao.upsertActivityDays(export.activityDays)
        }
        export.settings?.let { imported -> settings.updateData { imported } }
        return ImportSummary(ImportMode.REPLACE, added = export.rowCount(), updated = 0, unchanged = 0)
    }

    /** Settings are kept as they are on a merge; they have no edit times to compare. */
    private suspend fun merge(export: ForkcastExport): ImportSummary {
        val tally = Tally()
        db.withTransaction {
            val slotIds = mergeMealSlots(export.mealSlots, tally)
            dao.upsertProfiles(newer(dao.profiles(), listOfNotNull(export.profile), { it.id }, { it.updatedAt }, tally))
            dao.upsertGoals(newer(dao.goals(), export.goals, { it.id }, { it.updatedAt }, tally))
            keepOneActiveGoal(dao.goals())
            dao.upsertTargetOverrides(newer(dao.targetOverrides(), export.targetOverrides, { it.id }, { it.updatedAt }, tally))
            dao.upsertFoods(newer(dao.foods(), export.foods, { it.id }, { it.updatedAt }, tally))
            dao.upsertSavedMeals(newer(dao.savedMeals(), export.savedMeals, { it.id }, { it.updatedAt }, tally))
            dao.upsertSavedMealItems(newer(dao.savedMealItems(), export.savedMealItems, { it.id }, { it.updatedAt }, tally))
            val entries = export.logEntries.map { it.copy(mealSlotId = slotIds[it.mealSlotId] ?: it.mealSlotId) }
            dao.upsertLogEntries(newer(dao.logEntries(), entries, { it.id }, { it.updatedAt }, tally))
            dao.upsertWeights(newer(dao.weights(), export.weights, { it.id }, { it.updatedAt }, tally))
            dao.upsertWater(newer(dao.water(), export.water, { it.id }, { it.updatedAt }, tally))
            // One status row per date (unique index), so match by date rather than id.
            val localStatus = dao.dayStatus().associateBy { it.date }
            val status = newer(localStatus.values.toList(), export.dayStatus, { it.date.toString() }, { it.updatedAt }, tally)
            status.forEach { row -> localStatus[row.date]?.takeIf { it.id != row.id }?.let { dao.deleteDayStatus(it.id) } }
            dao.upsertDayStatus(status)
            val estimates = export.aiEstimates.map { it.copy(mealSlotId = slotIds[it.mealSlotId] ?: it.mealSlotId) }
            dao.upsertAiEstimates(newer(dao.aiEstimates(), estimates, { it.id }, { it.updatedAt }, tally))
            dao.upsertAiComponents(newer(dao.aiComponents(), export.aiComponents, { it.id }, { it.updatedAt }, tally))
            dao.upsertAiProfiles(newer(dao.aiProfiles(), export.aiProfiles, { it.id }, { it.updatedAt }, tally))
            dao.upsertUsageRecords(newer(dao.usageRecords(), export.usageRecords, { it.id }, { it.updatedAt }, tally))
            // One row per (date, type, threshold) (unique index): only add what this phone hasn't logged.
            val fired = dao.notificationLog().map { Triple(it.date, it.type, it.threshold) }.toSet()
            dao.upsertNotificationLog(export.notificationLog.filter { Triple(it.date, it.type, it.threshold) !in fired })
            dao.upsertAiMatchCache(newer(dao.aiMatchCache(), export.aiMatchCache, { it.id }, { it.updatedAt }, tally))
            dao.upsertTdeeEstimates(newer(dao.tdeeEstimates(), export.tdeeEstimates, { it.id }, { it.updatedAt }, tally))
            // One activity row per date (unique index), like day status.
            val localActivity = dao.activityDays().associateBy { it.date }
            val activity = newer(localActivity.values.toList(), export.activityDays, { it.date.toString() }, { it.updatedAt }, tally)
            activity.forEach { row -> localActivity[row.date]?.takeIf { it.id != row.id }?.let { dao.deleteActivityDay(it.id) } }
            dao.upsertActivityDays(activity)
        }
        return ImportSummary(ImportMode.MERGE, tally.added, tally.updated, tally.unchanged)
    }

    /**
     * Meal slots get random ids on each install, so a backup from another phone has its own
     * Breakfast/Lunch/... ids. Imported slots unknown here are matched to a local slot by
     * name; the returned map rewrites log entries to the local ids.
     */
    private suspend fun mergeMealSlots(imported: List<MealSlotEntity>, tally: Tally): Map<String, String> {
        val local = dao.mealSlots()
        val localIds = local.map { it.id }.toSet()
        val byName = local.filter { it.deletedAt == null }.associateBy { it.name.trim().lowercase() }
        val remap = mutableMapOf<String, String>()
        val incoming = imported.filter { slot ->
            if (slot.id in localIds) return@filter true
            val match = byName[slot.name.trim().lowercase()]
            if (match != null && slot.deletedAt == null) {
                remap[slot.id] = match.id
                tally.unchanged++
                false
            } else {
                true
            }
        }
        dao.upsertMealSlots(newer(local, incoming, { it.id }, { it.updatedAt }, tally))
        return remap
    }

    /** Two phones can each have an active goal; the most recently edited one stays active. */
    private suspend fun keepOneActiveGoal(goals: List<GoalEntity>) {
        val active = goals.filter { it.isActive && it.deletedAt == null }
        if (active.size <= 1) return
        val keep = active.maxBy { it.updatedAt }
        dao.upsertGoals(active.filter { it.id != keep.id }.map { it.copy(isActive = false) })
    }

    private fun <T> newer(
        local: List<T>,
        incoming: List<T>,
        key: (T) -> String,
        updatedAt: (T) -> Long,
        tally: Tally,
    ): List<T> {
        val existing = local.associateBy(key)
        return incoming.filter { row ->
            val mine = existing[key(row)]
            when {
                mine == null -> { tally.added++; true }
                updatedAt(row) > updatedAt(mine) -> { tally.updated++; true }
                else -> { tally.unchanged++; false }
            }
        }
    }

    private suspend fun writeSafetyBackup() = withContext(Dispatchers.IO) {
        val dir = safetyDir.apply { mkdirs() }
        val stamp = FILE_STAMP.format(clock.instant().atZone(clock.zone))
        File(dir, "forkcast-before-replace-$stamp.json").writeText(exportJson())
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP_SAFETY_BACKUPS)
            ?.forEach { it.delete() }
    }

    private class Tally(var added: Int = 0, var updated: Int = 0, var unchanged: Int = 0)

    companion object {
        /** App-private and excluded from Android Auto Backup (it duplicates the database). */
        const val SAFETY_DIR = "safety-backups"
        private const val KEEP_SAFETY_BACKUPS = 3
        private val FILE_STAMP = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

/** Export JSON: every field written (including nulls), unknown fields ignored on read. */
val BackupJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
    prettyPrint = true
}

private fun JsonObject.string(name: String): String? =
    runCatching { get(name)?.jsonPrimitive?.content }.getOrNull()

private fun ForkcastExport.rowCount() =
    (if (profile != null) 1 else 0) + goals.size + targetOverrides.size + mealSlots.size + foods.size +
        savedMeals.size + savedMealItems.size + logEntries.size + weights.size + water.size + dayStatus.size +
        tdeeEstimates.size + activityDays.size

internal fun preview(export: ForkcastExport): BackupPreview {
    val dates = export.logEntries.filter { it.deletedAt == null }.map { it.date } +
        export.water.filter { it.deletedAt == null }.map { it.date } +
        export.weights.filter { it.deletedAt == null }.map {
            Instant.ofEpochMilli(it.timestamp).atZone(zoneOrDefault(export.timeZone)).toLocalDate()
        }
    return BackupPreview(
        exportedAt = runCatching { Instant.parse(export.exportedAt) }.getOrNull(),
        appVersion = export.appVersion,
        counts = BackupCounts(
            foods = export.foods.count { it.deletedAt == null },
            logEntries = export.logEntries.count { it.deletedAt == null },
            savedMeals = export.savedMeals.count { it.deletedAt == null },
            weights = export.weights.count { it.deletedAt == null },
            water = export.water.count { it.deletedAt == null },
            goals = export.goals.count { it.deletedAt == null },
            hasProfile = export.profile != null && export.profile.deletedAt == null,
            aiEstimates = export.aiEstimates.count { it.deletedAt == null },
            aiProfiles = export.aiProfiles.count { it.deletedAt == null },
        ),
        firstDate = dates.minOrNull(),
        lastDate = dates.maxOrNull(),
    )
}

private fun zoneOrDefault(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault())
