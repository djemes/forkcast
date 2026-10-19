package app.forkcast.core.data.backup

import app.forkcast.core.data.db.ActivityDayEntity
import app.forkcast.core.data.db.AiComponentEntity
import app.forkcast.core.data.db.AiEstimateEntity
import app.forkcast.core.data.db.AiMatchCacheEntity
import app.forkcast.core.data.db.AiProfileEntity
import app.forkcast.core.data.db.DayStatusEntity
import app.forkcast.core.data.db.NotificationLogEntity
import app.forkcast.core.data.db.UsageRecordEntity
import app.forkcast.core.data.db.FoodEntity
import app.forkcast.core.data.db.GoalEntity
import app.forkcast.core.data.db.LogEntryEntity
import app.forkcast.core.data.db.MealSlotEntity
import app.forkcast.core.data.db.ProfileEntity
import app.forkcast.core.data.db.RecipeEntity
import app.forkcast.core.data.db.RecipeIngredientEntity
import app.forkcast.core.data.db.SavedMealEntity
import app.forkcast.core.data.db.SavedMealItemEntity
import app.forkcast.core.data.db.TargetOverrideEntity
import app.forkcast.core.data.db.TdeeEstimateEntity
import app.forkcast.core.data.db.WaterEntryEntity
import app.forkcast.core.data.db.WeightEntryEntity
import app.forkcast.core.model.AppSettings
import kotlinx.serialization.Serializable

/**
 * Export file (SPEC §6.3; JSON Schema in docs/export-schema-v5.json). Field names mirror the
 * Room entities. Secrets are never included: API keys live in an encrypted file outside the
 * database and [AppSettings] holds no secrets (keys travel only in the separate,
 * passphrase-encrypted settings export).
 *
 * v1 (Phase 1): core tracking tables. v2 (Phase 2): AI estimates and components, AI profiles
 * (no keys), usage, notification log, match cache. v3 (Phase 3): maintenance estimates,
 * Health Connect activity days, water's Health Connect record id. Older files read as v3 with
 * the newer arrays empty. v4: drinks count toward water (food.countsAsWater, waterMl on log
 * entries and saved-meal items); older files read with those null. v5 (Phase 4): recipes and
 * their ingredients (older files wrote `recipes` as an always-empty array).
 */
@Serializable
data class ForkcastExport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val app: String = "Forkcast",
    val appVersion: String,
    val exportedAt: String,
    val timeZone: String,
    /** Null before onboarding. */
    val profile: ProfileEntity? = null,
    val goals: List<GoalEntity> = emptyList(),
    val targetOverrides: List<TargetOverrideEntity> = emptyList(),
    val tdeeEstimates: List<TdeeEstimateEntity> = emptyList(),
    val mealSlots: List<MealSlotEntity> = emptyList(),
    val foods: List<FoodEntity> = emptyList(),
    /** v5 (Phase 4). */
    val recipes: List<RecipeEntity> = emptyList(),
    val recipeIngredients: List<RecipeIngredientEntity> = emptyList(),
    val savedMeals: List<SavedMealEntity> = emptyList(),
    val savedMealItems: List<SavedMealItemEntity> = emptyList(),
    val logEntries: List<LogEntryEntity> = emptyList(),
    val aiEstimates: List<AiEstimateEntity> = emptyList(),
    val aiComponents: List<AiComponentEntity> = emptyList(),
    val aiProfiles: List<AiProfileEntity> = emptyList(),
    val usageRecords: List<UsageRecordEntity> = emptyList(),
    val notificationLog: List<NotificationLogEntity> = emptyList(),
    val aiMatchCache: List<AiMatchCacheEntity> = emptyList(),
    val weights: List<WeightEntryEntity> = emptyList(),
    val water: List<WaterEntryEntity> = emptyList(),
    val activityDays: List<ActivityDayEntity> = emptyList(),
    val dayStatus: List<DayStatusEntity> = emptyList(),
    val settings: AppSettings? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 5
    }
}
