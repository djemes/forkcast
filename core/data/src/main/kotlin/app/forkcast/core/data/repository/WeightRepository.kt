package app.forkcast.core.data.repository

import app.forkcast.core.data.db.WeightDao
import app.forkcast.core.data.db.WeightEntryEntity
import app.forkcast.core.model.WeightEntry
import app.forkcast.core.model.WeightSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface WeightRepository {
    val entries: Flow<List<WeightEntry>>
    /** Most recent weigh-in in kg, or null if none. */
    val latestKg: Flow<Double?>
    suspend fun add(kg: Double, bodyFatPct: Double?, at: Instant): String
    suspend fun update(entry: WeightEntry)
    suspend fun delete(id: String)
}

@Singleton
class RoomWeightRepository @Inject constructor(
    private val dao: WeightDao,
    private val clock: Clock,
) : WeightRepository {

    override val entries: Flow<List<WeightEntry>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    override val latestKg: Flow<Double?> = entries.map { it.lastOrNull()?.kg }.distinctUntilChanged()

    override suspend fun add(kg: Double, bodyFatPct: Double?, at: Instant): String {
        val now = clock.millis()
        val id = UUID.randomUUID().toString()
        dao.upsert(WeightEntryEntity(id, at.toEpochMilli(), kg, bodyFatPct, WeightSource.MANUAL, null, now, now))
        return id
    }

    override suspend fun update(entry: WeightEntry) {
        val existing = dao.get(entry.id) ?: return
        dao.upsert(
            existing.copy(
                timestamp = entry.timestamp.toEpochMilli(),
                kg = entry.kg,
                bodyFatPct = entry.bodyFatPct,
                updatedAt = clock.millis(),
            ),
        )
    }

    override suspend fun delete(id: String) = dao.softDelete(id, clock.millis())
}

private fun WeightEntryEntity.toModel() = WeightEntry(id, Instant.ofEpochMilli(timestamp), kg, bodyFatPct, source)
