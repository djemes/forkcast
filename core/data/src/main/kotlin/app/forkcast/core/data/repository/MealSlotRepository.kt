package app.forkcast.core.data.repository

import androidx.room.withTransaction
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.MealSlotDao
import app.forkcast.core.data.db.MealSlotEntity
import app.forkcast.core.data.db.toModel
import app.forkcast.core.model.MealSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meal sections (SPEC §4.2, §4.12). Deleting a meal is a soft delete: its past entries stay and
 * show under "Other" on their days, and Undo brings the meal back with them.
 */
interface MealSlotRepository {
    fun observeMealSlots(): Flow<List<MealSlot>>
    suspend fun add(name: String, startMinute: Int?, endMinute: Int?): String
    suspend fun update(slot: MealSlot)
    suspend fun delete(id: String)
    suspend fun restore(id: String)
    /** Saves this order (ids first to last). */
    suspend fun reorder(idsInOrder: List<String>)
}

@Singleton
class RoomMealSlotRepository @Inject constructor(
    private val db: ForkcastDatabase,
    private val dao: MealSlotDao,
    private val clock: Clock,
) : MealSlotRepository {
    override fun observeMealSlots(): Flow<List<MealSlot>> =
        dao.observeActive().map { list -> list.map { it.toModel() } }

    override suspend fun add(name: String, startMinute: Int?, endMinute: Int?): String {
        val now = clock.millis()
        val id = UUID.randomUUID().toString()
        val order = (dao.all().filter { it.deletedAt == null }.maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsert(listOf(MealSlotEntity(id, name.trim(), order, startMinute, endMinute, now, now, null)))
        return id
    }

    override suspend fun update(slot: MealSlot) {
        val old = dao.get(slot.id) ?: return
        dao.upsert(listOf(old.copy(name = slot.name.trim(), startMinute = slot.startMinute, endMinute = slot.endMinute, updatedAt = clock.millis())))
    }

    override suspend fun delete(id: String) {
        val old = dao.get(id) ?: return
        val now = clock.millis()
        dao.upsert(listOf(old.copy(deletedAt = now, updatedAt = now)))
    }

    override suspend fun restore(id: String) {
        val old = dao.get(id) ?: return
        dao.upsert(listOf(old.copy(deletedAt = null, updatedAt = clock.millis())))
    }

    override suspend fun reorder(idsInOrder: List<String>) {
        val now = clock.millis()
        db.withTransaction {
            val byId = dao.all().associateBy { it.id }
            dao.upsert(idsInOrder.mapIndexedNotNull { i, id -> byId[id]?.copy(sortOrder = i, updatedAt = now) })
        }
    }
}
