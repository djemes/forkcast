package app.forkcast.core.data.repository

import app.forkcast.core.data.db.WaterDao
import app.forkcast.core.data.db.WaterEntryEntity
import app.forkcast.core.model.WaterEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface WaterRepository {
    fun observeDay(date: LocalDate): Flow<List<WaterEntry>>
    suspend fun add(date: LocalDate, ml: Double): String
    suspend fun delete(id: String)
}

@Singleton
class RoomWaterRepository @Inject constructor(
    private val dao: WaterDao,
    private val clock: Clock,
) : WaterRepository {

    override fun observeDay(date: LocalDate): Flow<List<WaterEntry>> =
        dao.observeForDate(date).map { list -> list.map { WaterEntry(it.id, Instant.ofEpochMilli(it.timestamp), it.date, it.ml) } }

    override suspend fun add(date: LocalDate, ml: Double): String {
        val now = clock.millis()
        val id = UUID.randomUUID().toString()
        dao.upsert(WaterEntryEntity(id, now, date, clock.zone.id, ml, now, now))
        return id
    }

    override suspend fun delete(id: String) = dao.softDelete(id, clock.millis())
}
