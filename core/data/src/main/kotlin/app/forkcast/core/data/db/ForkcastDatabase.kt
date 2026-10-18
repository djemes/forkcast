package app.forkcast.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Every schema change bumps [version], ships a migration (auto or manual) and a
 * migration test. Never use destructive fallback: updates install over real data.
 */
@Database(
    entities = [MealSlotEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ForkcastDatabase : RoomDatabase() {
    abstract fun mealSlotDao(): MealSlotDao

    companion object {
        const val NAME = "forkcast.db"
    }
}
