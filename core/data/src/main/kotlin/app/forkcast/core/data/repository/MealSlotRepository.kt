package app.forkcast.core.data.repository

import app.forkcast.core.data.db.MealSlotDao
import app.forkcast.core.data.db.toModel
import app.forkcast.core.model.MealSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface MealSlotRepository {
    fun observeMealSlots(): Flow<List<MealSlot>>
}

@Singleton
class RoomMealSlotRepository @Inject constructor(
    private val dao: MealSlotDao,
) : MealSlotRepository {
    override fun observeMealSlots(): Flow<List<MealSlot>> =
        dao.observeActive().map { list -> list.map { it.toModel() } }
}
