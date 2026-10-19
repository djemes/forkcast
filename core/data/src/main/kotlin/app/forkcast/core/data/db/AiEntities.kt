@file:UseSerializers(LocalDateSerializer::class)

package app.forkcast.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.forkcast.core.model.MealEstimateJson
import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate

// Phase 2 tables (DB v3, export schemaVersion 2). Same conventions as Entities.kt.

enum class EstimateStatus {
    /** Waiting for its background job (or for network / quota reset). */
    PENDING,
    RUNNING,
    /** Estimated; logged (auto-accept) or waiting for the user to review. */
    DONE,
    /** The model asked a clarifying question. */
    NEEDS_INPUT,
    /** Stopped with an error the user has to act on (refusal, unusable output, no usable profile). */
    FAILED,
}

enum class ComponentSource { USDA, AI }

/** One photo-meal estimate (SPEC §4.6, §6.1). Never contains body data. */
@Serializable
@Entity(tableName = "ai_estimate", indices = [Index("date"), Index("status")])
data class AiEstimateEntity(
    @PrimaryKey val id: String,
    val date: LocalDate,
    val zoneId: String,
    val mealSlotId: String,
    /** The log entry this estimate produced (auto-accept or Save), once logged. */
    val logEntryId: String?,
    val status: EstimateStatus,
    /** Profile that produced the result, shown on the review screen ("Gemini 3.8 Flash · free"). */
    val profileId: String?,
    val profileName: String?,
    val provider: ProviderId?,
    val model: String?,
    val tierLabel: TierLabel?,
    val description: String?,
    val personalContext: String?,
    /** Paths relative to the app's photos folder. */
    val photoPaths: List<String>,
    val localTime: String,
    val unitsHint: String,
    val correction: String?,
    val promptVersion: String,
    val rawJson: String?,
    val result: MealEstimateJson?,
    val warnings: List<String>,
    val mealName: String?,
    val kcalLow: Double?,
    val kcalExpected: Double?,
    val kcalHigh: Double?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long?,
    /** AiErrorCode name of the last failure, for a specific message. */
    val errorCode: String?,
    val errorDetail: String?,
    val attempts: Int,
    /** Don't retry before this time (quota window), epoch ms. */
    val retryAfter: Long?,
    /** The "Estimate ready" notification was posted (or not needed). */
    val notified: Boolean,
    /** When the user saved the review screen; until then the entry shows "AI·review". */
    val reviewedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** One food in an estimate. Keeps both the model's numbers and the USDA match (SPEC §4.6.1). */
@Serializable
@Entity(tableName = "ai_component", indices = [Index("estimateId")])
data class AiComponentEntity(
    @PrimaryKey val id: String,
    val estimateId: String,
    val sortOrder: Int,
    val name: String,
    val preparation: String,
    val grams: Double,
    val gramsLow: Double,
    val gramsHigh: Double,
    val confidence: Double,
    val isHiddenIngredient: Boolean,
    val usdaQuery: String,
    /** Local food id of the matched USDA record (cached in `food`). */
    val matchedFoodId: String?,
    val matchedFdcId: String?,
    val matchedName: String?,
    val matchScore: Double?,
    /** The model's estimate, scaled to 100 g so grams can be edited. */
    @Serializable(with = NutrientsSerializer::class) val aiPer100g: Nutrients,
    @Serializable(with = NutrientsSerializer::class) val usdaPer100g: Nutrients?,
    val useSource: ComponentSource,
    /** Snapshot at [grams] from the chosen source. */
    @Serializable(with = NutrientsSerializer::class) val nutrientsUsed: Nutrients,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** An entry in Settings › AI's "Use in this order" list. The key is stored encrypted elsewhere. */
@Serializable
@Entity(tableName = "ai_profile", indices = [Index("sortOrder")])
data class AiProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val provider: ProviderId,
    val model: String,
    val baseUrl: String?,
    val tierLabel: TierLabel,
    val sortOrder: Int,
    val enabled: Boolean,
    val needsAttention: Boolean,
    val cooldownUntil: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** Requests and tokens per profile per day (Pacific day for Gemini). Monthly totals are sums. */
@Serializable
@Entity(tableName = "usage_record", indices = [Index("day"), Index(value = ["day", "profileId", "model"], unique = true)])
data class UsageRecordEntity(
    @PrimaryKey val id: String,
    val day: LocalDate,
    val profileId: String,
    val provider: ProviderId,
    val model: String,
    val requests: Int,
    /** Of which estimates (Test presses are requests too). */
    val estimates: Int,
    val rateLimited: Int,
    val failures: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** Enforces once-per-day notifications (SPEC §4.8). */
@Serializable
@Entity(tableName = "notification_log", indices = [Index(value = ["date", "type", "threshold"], unique = true)])
data class NotificationLogEntity(
    @PrimaryKey val id: String,
    val date: LocalDate,
    val type: String,
    /** Percent for budget thresholds, 0 otherwise. */
    val threshold: Int,
    val firedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** Model `usda_query` → chosen USDA food, to cut latency and API calls (SPEC §4.6.1). */
@Serializable
@Entity(tableName = "ai_match_cache")
data class AiMatchCacheEntity(
    /** Normalized query (lowercase, single spaces). */
    @PrimaryKey val id: String,
    val foodId: String?,
    val fdcId: String?,
    val score: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
