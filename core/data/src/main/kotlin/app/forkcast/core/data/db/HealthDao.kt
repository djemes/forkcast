package app.forkcast.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Health Connect cache and write state (v4), and the adaptive maintenance estimates. */
@Dao
interface HealthDao {
    @Query("SELECT * FROM activity_day WHERE date = :date AND deletedAt IS NULL LIMIT 1")
    fun observeActivity(date: LocalDate): Flow<ActivityDayEntity?>

    @Query("SELECT * FROM activity_day WHERE date >= :from AND date <= :to AND deletedAt IS NULL ORDER BY date")
    suspend fun activityRange(from: LocalDate, to: LocalDate): List<ActivityDayEntity>

    @Query("SELECT * FROM activity_day WHERE date >= :from AND date <= :to AND deletedAt IS NULL ORDER BY date")
    fun observeActivityRange(from: LocalDate, to: LocalDate): Flow<List<ActivityDayEntity>>

    /** Including deleted rows: one row per date (unique), so a returning day reuses its row. */
    @Query("SELECT * FROM activity_day WHERE date >= :from AND date <= :to")
    suspend fun activityRangeAll(from: LocalDate, to: LocalDate): List<ActivityDayEntity>

    @Upsert suspend fun upsertActivity(rows: List<ActivityDayEntity>)

    @Query("SELECT * FROM tdee_estimate WHERE deletedAt IS NULL ORDER BY date DESC, createdAt DESC LIMIT 1")
    fun observeLatestTdee(): Flow<TdeeEstimateEntity?>

    @Query("SELECT * FROM tdee_estimate WHERE deletedAt IS NULL ORDER BY date DESC, createdAt DESC LIMIT 1")
    suspend fun latestTdee(): TdeeEstimateEntity?

    @Upsert suspend fun upsertTdee(row: TdeeEstimateEntity)

    /** The latest estimate made on or before [date]. */
    @Query("SELECT * FROM tdee_estimate WHERE deletedAt IS NULL AND date <= :date ORDER BY date DESC, createdAt DESC LIMIT 1")
    suspend fun tdeeAsOf(date: LocalDate): TdeeEstimateEntity?

    /** Entries whose current version isn't in Health Connect yet (new, edited, or deleted since). */
    @Query("SELECT * FROM log_entry WHERE hcSyncedAt IS NULL OR hcSyncedAt < updatedAt ORDER BY updatedAt LIMIT :limit")
    suspend fun pendingLogEntries(limit: Int): List<LogEntryEntity>

    /** Only if the row hasn't changed since it was written, so a later edit stays pending. */
    @Query("UPDATE log_entry SET healthConnectRecordId = :recordId, hcWaterRecordId = :waterRecordId, hcSyncedAt = :version WHERE id = :id AND updatedAt = :version")
    suspend fun markLogEntrySynced(id: String, recordId: String?, waterRecordId: String?, version: Long)

    @Query("SELECT * FROM water_entry WHERE hcSyncedAt IS NULL OR hcSyncedAt < updatedAt ORDER BY updatedAt LIMIT :limit")
    suspend fun pendingWater(limit: Int): List<WaterEntryEntity>

    @Query("UPDATE water_entry SET hcRecordId = :recordId, hcSyncedAt = :version WHERE id = :id AND updatedAt = :version")
    suspend fun markWaterSynced(id: String, recordId: String?, version: Long)

    /** After a disconnect, everything is written again on the next connect (writes are idempotent). */
    @Query("UPDATE log_entry SET hcSyncedAt = NULL")
    suspend fun resetLogSync()

    @Query("UPDATE water_entry SET hcSyncedAt = NULL")
    suspend fun resetWaterSync()

    @Query("SELECT * FROM weight_entry WHERE hcRecordId = :hcId LIMIT 1")
    suspend fun weightByHcId(hcId: String): WeightEntryEntity?

    @Query("SELECT * FROM weight_entry WHERE hcRecordId IN (:hcIds)")
    suspend fun weightsByHcIds(hcIds: List<String>): List<WeightEntryEntity>

    /** Manual weigh-ins in a time window (dedupe: SPEC §4.9, ±10 minutes). */
    @Query("SELECT * FROM weight_entry WHERE source = 'MANUAL' AND deletedAt IS NULL AND timestamp >= :from AND timestamp <= :to")
    suspend fun manualWeightsBetween(from: Long, to: Long): List<WeightEntryEntity>

    /** Any source, for attaching a body-fat reading to the weigh-in it belongs to. */
    @Query("SELECT * FROM weight_entry WHERE deletedAt IS NULL AND timestamp >= :from AND timestamp <= :to")
    suspend fun weightsBetween(from: Long, to: Long): List<WeightEntryEntity>

    @Query("SELECT * FROM weight_entry WHERE source = 'HEALTH_CONNECT' AND deletedAt IS NULL AND timestamp >= :from")
    suspend fun healthConnectWeightsSince(from: Long): List<WeightEntryEntity>

    @Upsert suspend fun upsertWeights(rows: List<WeightEntryEntity>)

    @Query("SELECT count(*) FROM log_entry WHERE hcSyncedAt IS NULL OR hcSyncedAt < updatedAt")
    fun observePendingLogCount(): Flow<Int>

    @Query("SELECT count(*) FROM water_entry WHERE hcSyncedAt IS NULL OR hcSyncedAt < updatedAt")
    fun observePendingWaterCount(): Flow<Int>
}
