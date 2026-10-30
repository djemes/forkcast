package app.forkcast.core.data.repository

import app.forkcast.core.data.db.WaterDao
import app.forkcast.core.data.db.WaterEntryEntity
import app.forkcast.core.model.WaterEntry
import kotlinx.coroutines.flow.Flow
import app.forkcast.core.domain.WaterMath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface WaterRepository {
    fun observeDay(date: LocalDate): Flow<List<WaterEntry>>
    /** Entries from [from] to [to], inclusive, in one query (the meal report's long ranges). */
    suspend fun range(from: LocalDate, to: LocalDate): List<WaterEntry>
    suspend fun add(date: LocalDate, ml: Double): String
    suspend fun delete(id: String)
    /**
     * Makes the day's water [totalMl] (drinks logged as food, [drinksMl], stay as they are): removes
     * the newest entries over it, or adds the difference. Health Connect follows each change.
     */
    suspend fun setDayTotal(date: LocalDate, totalMl: Double, drinksMl: Double)
}

@Singleton
class RoomWaterRepository @Inject constructor(
    private val dao: WaterDao,
    private val clock: Clock,
) : WaterRepository {

    override fun observeDay(date: LocalDate): Flow<List<WaterEntry>> =
        dao.observeForDate(date).map { list -> list.map { it.toModel() } }

    override suspend fun range(from: LocalDate, to: LocalDate): List<WaterEntry> = dao.getRange(from, to).map { it.toModel() }

    private fun WaterEntryEntity.toModel() = WaterEntry(id, Instant.ofEpochMilli(timestamp), date, ml)

    override suspend fun add(date: LocalDate, ml: Double): String {
        val now = clock.millis()
        val id = UUID.randomUUID().toString()
        dao.upsert(WaterEntryEntity(id, now, date, clock.zone.id, ml, now, now))
        return id
    }

    override suspend fun delete(id: String) = dao.softDelete(id, clock.millis())

    override suspend fun setDayTotal(date: LocalDate, totalMl: Double, drinksMl: Double) {
        val entries = observeDay(date).first().sortedBy { it.timestamp }.map { it.id to it.ml }
        val change = WaterMath.adjustTo(totalMl, entries, drinksMl)
        change.removeIds.forEach { delete(it) }
        if (change.addMl > 0.5) add(date, change.addMl)
    }
}
