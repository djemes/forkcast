package app.forkcast.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Every schema change bumps [version], ships a migration (auto or manual) and a
 * migration test. Never use destructive fallback: updates install over real data.
 *
 * v1 (Phase 0): meal_slot
 * v2 (Phase 1): profile, goal, target_override, food, log_entry, saved_meal(+item), weight, water, day_status
 */
@Database(
    entities = [
        MealSlotEntity::class,
        ProfileEntity::class,
        GoalEntity::class,
        TargetOverrideEntity::class,
        FoodEntity::class,
        LogEntryEntity::class,
        SavedMealEntity::class,
        SavedMealItemEntity::class,
        WeightEntryEntity::class,
        WaterEntryEntity::class,
        DayStatusEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
abstract class ForkcastDatabase : RoomDatabase() {
    abstract fun mealSlotDao(): MealSlotDao
    abstract fun profileDao(): ProfileDao
    abstract fun goalDao(): GoalDao
    abstract fun targetOverrideDao(): TargetOverrideDao
    abstract fun foodDao(): FoodDao
    abstract fun logEntryDao(): LogEntryDao
    abstract fun savedMealDao(): SavedMealDao
    abstract fun weightDao(): WeightDao
    abstract fun waterDao(): WaterDao
    abstract fun dayStatusDao(): DayStatusDao

    companion object {
        const val NAME = "forkcast.db"
    }
}
