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
 * Export file (SPEC §6.3; JSON Schema in core/data/schemas/export/export-schema-v9.json). Field names mirror the
 * Room entities. Secrets are never included: API keys live in an encrypted file outside the
 * database and [AppSettings] holds no secrets (a backup carries the keys in its own `secrets.json`).
 * Readers ignore unknown fields.
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
        /** v9 is 2.0's baseline (roadmap 2.0, "2.0 is a clean start"). */
        const val SCHEMA_VERSION = 9
        /** The oldest export this version imports: its own and the one before it, once there is one. */
        const val OLDEST_IMPORTED = 9
    }
}
