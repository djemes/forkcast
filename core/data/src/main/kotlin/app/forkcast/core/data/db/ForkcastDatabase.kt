package app.forkcast.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Every schema change bumps [version], ships a migration (auto or manual) from the previous
 * release's version and a migration test. Never use destructive fallback: updates install over
 * real data.
 *
 * v7 (1.2): the schema 1.2.x and 1.3 ship. Migrations from v1–v6 (builds before 1.2) were removed
 * in 1.3 (owner, 2026-10-24): every install that matters is on v7. The next change adds 7 → 8.
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
        AiEstimateEntity::class,
        AiComponentEntity::class,
        AiProfileEntity::class,
        UsageRecordEntity::class,
        NotificationLogEntity::class,
        AiMatchCacheEntity::class,
        ActivityDayEntity::class,
        TdeeEstimateEntity::class,
        RecipeEntity::class,
        RecipeIngredientEntity::class,
    ],
    version = 7,
    exportSchema = true,
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
    abstract fun backupDao(): BackupDao
    abstract fun aiDao(): AiDao
    abstract fun healthDao(): HealthDao
    abstract fun recipeDao(): RecipeDao

    companion object {
        const val NAME = "forkcast.db"
    }
}
