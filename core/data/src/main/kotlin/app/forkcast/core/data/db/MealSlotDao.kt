package app.forkcast.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MealSlotDao {

    @Query("SELECT * FROM meal_slot WHERE deletedAt IS NULL ORDER BY sortOrder")
    fun observeActive(): Flow<List<MealSlotEntity>>

    @Upsert
    suspend fun upsert(slots: List<MealSlotEntity>)

    /** Including deleted rows (for restore and reorder). */
    @Query("SELECT * FROM meal_slot")
    suspend fun all(): List<MealSlotEntity>

    @Query("SELECT * FROM meal_slot WHERE id = :id")
    suspend fun get(id: String): MealSlotEntity?
}
