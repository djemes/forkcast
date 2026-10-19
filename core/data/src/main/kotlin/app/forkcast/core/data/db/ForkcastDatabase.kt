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
 * v3 (Phase 2): ai_estimate, ai_component, ai_profile, usage_record, notification_log, ai_match_cache
 * v4 (Phase 3): activity_day, tdee_estimate; Health Connect write state on log_entry and water_entry
 * v5: drinks count toward water (food.countsAsWater, log_entry/saved_meal_item.waterMl, log_entry.hcWaterRecordId)
 * v6 (Phase 4): recipe, recipe_ingredient
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
    version = 6,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
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
