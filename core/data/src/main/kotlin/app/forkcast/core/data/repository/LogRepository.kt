package app.forkcast.core.data.repository

import androidx.room.withTransaction
import app.forkcast.core.data.db.FoodDao
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.LogEntryDao
import app.forkcast.core.data.db.LogEntryEntity
import app.forkcast.core.model.LogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface LogRepository {
    fun observeDay(date: LocalDate): Flow<List<LogEntry>>
    /** Entries from [from] to [to], inclusive. */
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<LogEntry>>
    suspend fun range(from: LocalDate, to: LocalDate): List<LogEntry>
    suspend fun entriesForMeal(date: LocalDate, mealSlotId: String): List<LogEntry>
    suspend fun get(id: String): LogEntry?
    /** Adds entries (ids are assigned) and marks their foods as recently used. Returns the new ids. */
    suspend fun add(entries: List<LogEntry>): List<String>
    suspend fun update(entry: LogEntry)
    suspend fun delete(ids: List<String>)
    /** Undo for [delete]. */
    suspend fun restore(ids: List<String>)
    /** Copies entries to another day (and optionally meal), keeping their snapshots. */
    suspend fun copy(ids: List<String>, toDate: LocalDate, toMealSlotId: String?): List<String>
}

@Singleton
class RoomLogRepository @Inject constructor(
    private val db: ForkcastDatabase,
    private val dao: LogEntryDao,
    private val foodDao: FoodDao,
    private val clock: Clock,
) : LogRepository {

    override fun observeDay(date: LocalDate): Flow<List<LogEntry>> =
        dao.observeForDate(date).map { list -> list.map { it.toModel() } }

    override fun observeRange(from: LocalDate, to: LocalDate): Flow<List<LogEntry>> =
        dao.observeRange(from, to).map { list -> list.map { it.toModel() } }

    override suspend fun range(from: LocalDate, to: LocalDate): List<LogEntry> = dao.getRange(from, to).map { it.toModel() }

    override suspend fun entriesForMeal(date: LocalDate, mealSlotId: String): List<LogEntry> =
        dao.getForMeal(date, mealSlotId).map { it.toModel() }

    override suspend fun get(id: String): LogEntry? = dao.get(id)?.takeIf { it.deletedAt == null }?.toModel()

    override suspend fun add(entries: List<LogEntry>): List<String> {
        val now = clock.millis()
        val rows = entries.mapIndexed { i, e ->
            // Keep the order the user added them in, even within the same millisecond.
            e.toEntity(id = UUID.randomUUID().toString(), now = now, loggedAt = maxOf(e.loggedAt.toEpochMilli(), now) + i)
        }
        db.withTransaction {
            dao.upsert(rows)
            rows.forEach { r -> r.foodId?.let { foodDao.markUsed(it, r.quantity, r.servingLabel, now) } }
        }
        return rows.map { it.id }
    }

    override suspend fun update(entry: LogEntry) {
        val existing = dao.get(entry.id) ?: return
        dao.upsert(listOf(entry.toEntity(entry.id, clock.millis(), existing.loggedAt).copy(
            createdAt = existing.createdAt,
            zoneId = existing.zoneId,
            aiEstimateId = existing.aiEstimateId,
            healthConnectRecordId = existing.healthConnectRecordId,
        )))
    }

    override suspend fun delete(ids: List<String>) = dao.softDelete(ids, clock.millis())

    override suspend fun restore(ids: List<String>) = dao.restore(ids, clock.millis())

    override suspend fun copy(ids: List<String>, toDate: LocalDate, toMealSlotId: String?): List<String> {
        val source = dao.getAll(ids).filter { it.deletedAt == null }.sortedBy { it.loggedAt }
        // A copy is a plain entry: the review screen belongs to the original.
        return add(source.map { it.toModel().copy(date = toDate, mealSlotId = toMealSlotId ?: it.mealSlotId, loggedAt = clock.instant(), aiEstimateId = null) })
    }

    private fun LogEntry.toEntity(id: String, now: Long, loggedAt: Long) = LogEntryEntity(
        id = id,
        date = date,
        zoneId = clock.zone.id,
        mealSlotId = mealSlotId,
        foodId = foodId,
        displayName = displayName,
        brand = brand,
        quantity = quantity,
        servingLabel = servingLabel,
        grams = grams,
        nutrients = nutrients,
        source = source,
        aiEstimateId = aiEstimateId,
        healthConnectRecordId = null,
        note = note,
        loggedAt = loggedAt,
        createdAt = now,
        updatedAt = now,
    )
}

fun LogEntryEntity.toModel() = LogEntry(
    id = id,
    date = date,
    mealSlotId = mealSlotId,
    foodId = foodId,
    displayName = displayName,
    brand = brand,
    quantity = quantity,
    servingLabel = servingLabel,
    grams = grams,
    nutrients = nutrients,
    source = source,
    note = note,
    loggedAt = Instant.ofEpochMilli(loggedAt),
    aiEstimateId = aiEstimateId,
)
