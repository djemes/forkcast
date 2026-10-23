@file:UseSerializers(LocalDateSerializer::class)

package app.forkcast.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.ExerciseMode
import app.forkcast.core.model.ExerciseSession
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.ServingSize
import app.forkcast.core.model.Sex
import app.forkcast.core.model.TdeeConfidence
import app.forkcast.core.model.TdeeMethod
import app.forkcast.core.model.WeightSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import java.time.LocalDate

// Every entity is @Serializable: the JSON export mirrors these rows (SPEC §6.3).
// Changing a field here changes the export format: bump schemaVersion and add an import migration.
// All tables (SPEC §6): UUID string id, createdAt/updatedAt epoch ms UTC, nullable deletedAt (soft delete).
// Log dates are the user's local date plus the zone in effect when logged.
// hcSyncedAt is this phone's Health Connect write state (the updatedAt last written); it is
// @Transient so exports never carry it and imported rows are written to Health Connect again.

@Serializable
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: String = SINGLE_ID,
    val sex: Sex,
    val birthDate: LocalDate,
    val heightCm: Double,
    val activityLevel: ActivityLevel,
    val exerciseMode: ExerciseMode,
    val exerciseAddBackPct: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
) {
    companion object {
        const val SINGLE_ID = "profile"
    }
}

@Serializable
@Entity(tableName = "goal", indices = [Index("isActive")])
data class GoalEntity(
    @PrimaryKey val id: String,
    val goalType: GoalType,
    val startDate: LocalDate,
    val startWeightKg: Double,
    val targetWeightKg: Double,
    val targetDate: LocalDate?,
    val weeklyRateKg: Double?,
    /** History is kept; exactly one goal is active. */
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
@Entity(tableName = "target_override", indices = [Index("nutrientKey")])
data class TargetOverrideEntity(
    @PrimaryKey val id: String,
    val nutrientKey: String,
    val amount: Double,
    val effectiveFrom: LocalDate,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
@Entity(
    tableName = "food",
    indices = [Index("barcode"), Index("source", "sourceId"), Index("lastUsedAt"), Index("isFavorite")],
)
data class FoodEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String?,
    val source: FoodSource,
    val sourceId: String?,
    val barcode: String?,
    val servingSizes: List<ServingSize>,
    @Serializable(with = NutrientsSerializer::class) val nutrientsPer100g: Nutrients,
    val isFavorite: Boolean,
    val lastUsedAt: Long?,
    val lastQuantity: Double?,
    val lastServingLabel: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /** v5: true/false = the user's choice, null = the drink rule decides (DrinkWater). */
    val countsAsWater: Boolean? = null,
    /** v7: what the user typed when this food came from "Describe" (AI · text), so a similar search finds it. */
    val describedAs: String? = null,
)

@Serializable
@Entity(tableName = "log_entry", indices = [Index("date"), Index("foodId"), Index("mealSlotId")])
data class LogEntryEntity(
    @PrimaryKey val id: String,
    val date: LocalDate,
    val zoneId: String,
    val mealSlotId: String,
    val foodId: String?,
    val displayName: String,
    val brand: String?,
    val quantity: Double,
    val servingLabel: String,
    val grams: Double?,
    /** Snapshot for the logged amount; never recomputed from the food. */
    @Serializable(with = NutrientsSerializer::class) val nutrients: Nutrients,
    val source: FoodSource,
    val aiEstimateId: String?,
    val healthConnectRecordId: String?,
    val note: String?,
    val loggedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    @Transient val hcSyncedAt: Long? = null,
    /** v5: water this drink adds to the day (ml), snapshotted when logged. */
    val waterMl: Double? = null,
    /** v5: the Hydration record written for that water (per phone, never exported). */
    @Transient val hcWaterRecordId: String? = null,
    /** v7: when it was eaten, minutes after local midnight on [date] (owner, 2026-10-23). */
    val eatenMinute: Int? = null,
    /** v7: [eatenMinute] is a typical time for its meal, filled in for entries from before meal times. */
    @ColumnInfo(defaultValue = "0") val timeEstimated: Boolean = false,
)

@Serializable
@Entity(tableName = "saved_meal")
data class SavedMealEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
@Entity(tableName = "saved_meal_item", indices = [Index("savedMealId")])
data class SavedMealItemEntity(
    @PrimaryKey val id: String,
    val savedMealId: String,
    val sortOrder: Int,
    val foodId: String?,
    val displayName: String,
    val brand: String?,
    val quantity: Double,
    val servingLabel: String,
    val grams: Double?,
    @Serializable(with = NutrientsSerializer::class) val nutrients: Nutrients,
    val source: FoodSource,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /** v5: see LogEntryEntity.waterMl. */
    val waterMl: Double? = null,
)

/** v6 (Phase 4): a recipe; [foodId] is its loggable food (source RECIPE). */
@Serializable
@Entity(tableName = "recipe", indices = [Index("foodId")])
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val servings: Double,
    val cookedWeightG: Double?,
    val foodId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** v6: one ingredient of a recipe, with its nutrients snapshotted for [grams]. */
@Serializable
@Entity(tableName = "recipe_ingredient", indices = [Index("recipeId")])
data class RecipeIngredientEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val sortOrder: Int,
    val foodId: String?,
    val displayName: String,
    val brand: String?,
    val quantity: Double,
    val servingLabel: String,
    val grams: Double,
    @Serializable(with = NutrientsSerializer::class) val nutrients: Nutrients,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
@Entity(tableName = "weight_entry", indices = [Index("timestamp")])
data class WeightEntryEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val kg: Double,
    val bodyFatPct: Double?,
    val source: WeightSource,
    val hcRecordId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
@Entity(tableName = "water_entry", indices = [Index("date")])
data class WaterEntryEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val date: LocalDate,
    val zoneId: String,
    val ml: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /** The Hydration record written for this entry (v4). */
    val hcRecordId: String? = null,
    @Transient val hcSyncedAt: Long? = null,
)

@Serializable
@Entity(tableName = "day_status", indices = [Index("date", unique = true)])
data class DayStatusEntity(
    @PrimaryKey val id: String,
    val date: LocalDate,
    val markedComplete: Boolean,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** Daily cache of Health Connect activity (v4). One row per date. */
@Serializable
@Entity(tableName = "activity_day", indices = [Index("date", unique = true)])
data class ActivityDayEntity(
    @PrimaryKey val id: String,
    val date: LocalDate,
    val steps: Long?,
    val activeKcal: Double?,
    val totalKcal: Double?,
    val exerciseMinutes: Int?,
    val sessions: List<ExerciseSession>,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/**
 * Maintenance estimates (SPEC §5.6, v4). [kcal] is all-in maintenance, exercise included;
 * Added-back mode subtracts the average exercise bonus when it builds the budget.
 */
@Serializable
@Entity(tableName = "tdee_estimate", indices = [Index("date")])
data class TdeeEstimateEntity(
    @PrimaryKey val id: String,
    /** The day the estimate was made; the trailing 28 days end the day before. */
    val date: LocalDate,
    val method: TdeeMethod,
    val kcal: Double,
    /** The raw observed value the weekly step moved toward (adaptive only). */
    val observedKcal: Double?,
    val confidence: TdeeConfidence?,
    val completeDays: Int,
    val weighIns: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
