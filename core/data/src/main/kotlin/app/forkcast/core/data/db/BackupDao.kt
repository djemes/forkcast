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
    @Query("DELETE FROM day_status WHERE id = :id") suspend fun deleteDayStatus(id: String)

    @Transaction
    suspend fun clearAll() {
        clearProfiles(); clearGoals(); clearTargetOverrides(); clearMealSlots(); clearFoods()
        clearSavedMeals(); clearSavedMealItems(); clearLogEntries(); clearWeights(); clearWater(); clearDayStatus()
    }
}
