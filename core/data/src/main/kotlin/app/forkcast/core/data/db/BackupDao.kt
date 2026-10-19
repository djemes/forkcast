package app.forkcast.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** Whole-table access for export/import. Includes soft-deleted rows so merges respect deletions. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM profile") suspend fun profiles(): List<ProfileEntity>
    @Query("SELECT * FROM goal") suspend fun goals(): List<GoalEntity>
    @Query("SELECT * FROM target_override") suspend fun targetOverrides(): List<TargetOverrideEntity>
    @Query("SELECT * FROM meal_slot") suspend fun mealSlots(): List<MealSlotEntity>
    @Query("SELECT * FROM food") suspend fun foods(): List<FoodEntity>
    @Query("SELECT * FROM saved_meal") suspend fun savedMeals(): List<SavedMealEntity>
    @Query("SELECT * FROM saved_meal_item") suspend fun savedMealItems(): List<SavedMealItemEntity>
    @Query("SELECT * FROM log_entry") suspend fun logEntries(): List<LogEntryEntity>
    @Query("SELECT * FROM weight_entry") suspend fun weights(): List<WeightEntryEntity>
    @Query("SELECT * FROM water_entry") suspend fun water(): List<WaterEntryEntity>
    @Query("SELECT * FROM day_status") suspend fun dayStatus(): List<DayStatusEntity>
    @Query("SELECT * FROM ai_estimate") suspend fun aiEstimates(): List<AiEstimateEntity>
    @Query("SELECT * FROM ai_component") suspend fun aiComponents(): List<AiComponentEntity>
    @Query("SELECT * FROM ai_profile") suspend fun aiProfiles(): List<AiProfileEntity>
    @Query("SELECT * FROM usage_record") suspend fun usageRecords(): List<UsageRecordEntity>
    @Query("SELECT * FROM notification_log") suspend fun notificationLog(): List<NotificationLogEntity>
    @Query("SELECT * FROM ai_match_cache") suspend fun aiMatchCache(): List<AiMatchCacheEntity>
    @Query("SELECT * FROM activity_day") suspend fun activityDays(): List<ActivityDayEntity>
    @Query("SELECT * FROM tdee_estimate") suspend fun tdeeEstimates(): List<TdeeEstimateEntity>
    @Query("SELECT * FROM recipe") suspend fun recipes(): List<RecipeEntity>
    @Query("SELECT * FROM recipe_ingredient") suspend fun recipeIngredients(): List<RecipeIngredientEntity>

    @Upsert suspend fun upsertProfiles(rows: List<ProfileEntity>)
    @Upsert suspend fun upsertGoals(rows: List<GoalEntity>)
    @Upsert suspend fun upsertTargetOverrides(rows: List<TargetOverrideEntity>)
    @Upsert suspend fun upsertMealSlots(rows: List<MealSlotEntity>)
    @Upsert suspend fun upsertFoods(rows: List<FoodEntity>)
    @Upsert suspend fun upsertSavedMeals(rows: List<SavedMealEntity>)
    @Upsert suspend fun upsertSavedMealItems(rows: List<SavedMealItemEntity>)
    @Upsert suspend fun upsertLogEntries(rows: List<LogEntryEntity>)
    @Upsert suspend fun upsertWeights(rows: List<WeightEntryEntity>)
    @Upsert suspend fun upsertWater(rows: List<WaterEntryEntity>)
    @Upsert suspend fun upsertDayStatus(rows: List<DayStatusEntity>)
    @Upsert suspend fun upsertAiEstimates(rows: List<AiEstimateEntity>)
    @Upsert suspend fun upsertAiComponents(rows: List<AiComponentEntity>)
    @Upsert suspend fun upsertAiProfiles(rows: List<AiProfileEntity>)
    @Upsert suspend fun upsertUsageRecords(rows: List<UsageRecordEntity>)
    @Upsert suspend fun upsertNotificationLog(rows: List<NotificationLogEntity>)
    @Upsert suspend fun upsertAiMatchCache(rows: List<AiMatchCacheEntity>)
    @Upsert suspend fun upsertActivityDays(rows: List<ActivityDayEntity>)
    @Upsert suspend fun upsertTdeeEstimates(rows: List<TdeeEstimateEntity>)
    @Upsert suspend fun upsertRecipes(rows: List<RecipeEntity>)
    @Upsert suspend fun upsertRecipeIngredients(rows: List<RecipeIngredientEntity>)

    @Query("DELETE FROM profile") suspend fun clearProfiles()
    @Query("DELETE FROM goal") suspend fun clearGoals()
    @Query("DELETE FROM target_override") suspend fun clearTargetOverrides()
    @Query("DELETE FROM meal_slot") suspend fun clearMealSlots()
    @Query("DELETE FROM food") suspend fun clearFoods()
    @Query("DELETE FROM saved_meal") suspend fun clearSavedMeals()
    @Query("DELETE FROM saved_meal_item") suspend fun clearSavedMealItems()
    @Query("DELETE FROM log_entry") suspend fun clearLogEntries()
    @Query("DELETE FROM weight_entry") suspend fun clearWeights()
    @Query("DELETE FROM water_entry") suspend fun clearWater()
    @Query("DELETE FROM day_status") suspend fun clearDayStatus()
    @Query("DELETE FROM ai_estimate") suspend fun clearAiEstimates()
    @Query("DELETE FROM ai_component") suspend fun clearAiComponents()
    @Query("DELETE FROM ai_profile") suspend fun clearAiProfiles()
    @Query("DELETE FROM usage_record") suspend fun clearUsageRecords()
    @Query("DELETE FROM notification_log") suspend fun clearNotificationLog()
    @Query("DELETE FROM ai_match_cache") suspend fun clearAiMatchCache()
    @Query("DELETE FROM activity_day") suspend fun clearActivityDays()
    @Query("DELETE FROM tdee_estimate") suspend fun clearTdeeEstimates()
    @Query("DELETE FROM recipe") suspend fun clearRecipes()
    @Query("DELETE FROM recipe_ingredient") suspend fun clearRecipeIngredients()
    @Query("DELETE FROM day_status WHERE id = :id") suspend fun deleteDayStatus(id: String)
    @Query("DELETE FROM activity_day WHERE id = :id") suspend fun deleteActivityDay(id: String)

    @Transaction
    suspend fun clearAll() {
        clearProfiles(); clearGoals(); clearTargetOverrides(); clearMealSlots(); clearFoods()
        clearSavedMeals(); clearSavedMealItems(); clearLogEntries(); clearWeights(); clearWater(); clearDayStatus()
        clearAiEstimates(); clearAiComponents(); clearAiProfiles(); clearUsageRecords(); clearNotificationLog(); clearAiMatchCache()
        clearActivityDays(); clearTdeeEstimates(); clearRecipes(); clearRecipeIngredients()
    }
}
