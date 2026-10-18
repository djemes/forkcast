package app.forkcast.core.data.backup

import app.forkcast.core.data.db.DayStatusEntity
import app.forkcast.core.data.db.FoodEntity
import app.forkcast.core.data.db.GoalEntity
import app.forkcast.core.data.db.LogEntryEntity
import app.forkcast.core.data.db.MealSlotEntity
import app.forkcast.core.data.db.ProfileEntity
import app.forkcast.core.data.db.SavedMealEntity
import app.forkcast.core.data.db.SavedMealItemEntity
import app.forkcast.core.data.db.TargetOverrideEntity
import app.forkcast.core.data.db.WaterEntryEntity
import app.forkcast.core.data.db.WeightEntryEntity
import app.forkcast.core.model.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Export file, schema version 1 (SPEC §6.3; JSON Schema in docs/export-schema-v1.json).
 * Field names mirror the Room entities. Secrets are never included: API keys live in an
 * encrypted file outside the database and [AppSettings] holds no secrets.
 */
@Serializable
data class ExportV1(
    val schemaVersion: Int = SCHEMA_VERSION,
    val app: String = "Forkcast",
    val appVersion: String,
    val exportedAt: String,
    val timeZone: String,
    /** Null before onboarding. */
    val profile: ProfileEntity? = null,
    val goals: List<GoalEntity> = emptyList(),
    val targetOverrides: List<TargetOverrideEntity> = emptyList(),
    /** Phase 3 (adaptive TDEE); always empty in v1 files written by Phase 1. */
    val tdeeEstimates: List<JsonElement> = emptyList(),
    val mealSlots: List<MealSlotEntity> = emptyList(),
    val foods: List<FoodEntity> = emptyList(),
    /** Phase 4. */
    val recipes: List<JsonElement> = emptyList(),
    val savedMeals: List<SavedMealEntity> = emptyList(),
    val savedMealItems: List<SavedMealItemEntity> = emptyList(),
    val logEntries: List<LogEntryEntity> = emptyList(),
    /** Phase 2. */
    val aiEstimates: List<JsonElement> = emptyList(),
    val weights: List<WeightEntryEntity> = emptyList(),
    val water: List<WaterEntryEntity> = emptyList(),
    /** Phase 3. */
    val activityDays: List<JsonElement> = emptyList(),
    val dayStatus: List<DayStatusEntity> = emptyList(),
    val settings: AppSettings? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}
