package app.forkcast.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Phase 2 tables: AI profiles, estimates, usage, notification log, match cache. */
@Dao
interface AiDao {
    // ---- Profiles

    @Query("SELECT * FROM ai_profile WHERE deletedAt IS NULL ORDER BY sortOrder")
    fun observeProfiles(): Flow<List<AiProfileEntity>>

    @Query("SELECT * FROM ai_profile WHERE deletedAt IS NULL ORDER BY sortOrder")
    suspend fun profiles(): List<AiProfileEntity>

    @Query("SELECT * FROM ai_profile WHERE id = :id")
    suspend fun profile(id: String): AiProfileEntity?

    @Upsert suspend fun upsertProfile(p: AiProfileEntity)

    @Upsert suspend fun upsertProfiles(p: List<AiProfileEntity>)

    @Query("UPDATE ai_profile SET cooldownUntil = :until, updatedAt = :now WHERE id = :id")
    suspend fun setCooldown(id: String, until: Long?, now: Long)

    @Query("UPDATE ai_profile SET needsAttention = :value, updatedAt = :now WHERE id = :id")
    suspend fun setNeedsAttention(id: String, value: Boolean, now: Long)

    // ---- Estimates

    @Query("SELECT * FROM ai_estimate WHERE id = :id")
    suspend fun estimate(id: String): AiEstimateEntity?

    @Query("SELECT * FROM ai_estimate WHERE id = :id")
    fun observeEstimate(id: String): Flow<AiEstimateEntity?>

    /** Estimates shown in the day's meal sections: not yet logged, or logged and awaiting review. */
    @Query("SELECT * FROM ai_estimate WHERE date = :date AND deletedAt IS NULL ORDER BY createdAt")
    fun observeEstimatesForDay(date: LocalDate): Flow<List<AiEstimateEntity>>

    @Query("SELECT * FROM ai_estimate WHERE status IN ('PENDING', 'RUNNING') AND deletedAt IS NULL")
    suspend fun unfinishedEstimates(): List<AiEstimateEntity>

    @Upsert suspend fun upsertEstimate(e: AiEstimateEntity)

    @Query("SELECT * FROM ai_component WHERE estimateId = :estimateId AND deletedAt IS NULL ORDER BY sortOrder")
    fun observeComponents(estimateId: String): Flow<List<AiComponentEntity>>

    @Query("SELECT * FROM ai_component WHERE estimateId = :estimateId AND deletedAt IS NULL ORDER BY sortOrder")
    suspend fun components(estimateId: String): List<AiComponentEntity>

    @Upsert suspend fun upsertComponents(c: List<AiComponentEntity>)

    @Query("UPDATE ai_component SET deletedAt = :now, updatedAt = :now WHERE estimateId = :estimateId AND deletedAt IS NULL")
    suspend fun softDeleteComponents(estimateId: String, now: Long)

    /** Replaces an estimate's components (a re-run or "Correct it"). */
    @Transaction
    suspend fun replaceComponents(estimateId: String, components: List<AiComponentEntity>, now: Long) {
        softDeleteComponents(estimateId, now)
        upsertComponents(components)
    }

    /** Estimates created before [cutoff] that still have photos, for retention cleanup. */
    @Query("SELECT * FROM ai_estimate WHERE createdAt < :cutoff AND photoPaths != '[]'")
    suspend fun estimatesWithPhotosBefore(cutoff: Long): List<AiEstimateEntity>

    // ---- Usage

    @Query("SELECT * FROM usage_record WHERE day = :day AND profileId = :profileId AND model = :model")
    suspend fun usage(day: LocalDate, profileId: String, model: String): UsageRecordEntity?

    @Upsert suspend fun upsertUsage(u: UsageRecordEntity)

    @Query("SELECT * FROM usage_record WHERE day >= :from AND day <= :to AND deletedAt IS NULL")
    fun observeUsage(from: LocalDate, to: LocalDate): Flow<List<UsageRecordEntity>>

    // ---- Notification log

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotification(n: NotificationLogEntity): Long

    @Query("SELECT * FROM notification_log WHERE date = :date AND deletedAt IS NULL")
    suspend fun notificationsOn(date: LocalDate): List<NotificationLogEntity>

    // ---- Match cache

    @Query("SELECT * FROM ai_match_cache WHERE id = :query AND deletedAt IS NULL")
    suspend fun match(query: String): AiMatchCacheEntity?

    @Upsert suspend fun upsertMatch(m: AiMatchCacheEntity)
}
