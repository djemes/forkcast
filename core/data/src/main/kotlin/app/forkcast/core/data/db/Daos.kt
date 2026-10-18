package app.forkcast.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.forkcast.core.model.FoodSource
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE deletedAt IS NULL LIMIT 1")
    fun observe(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE deletedAt IS NULL LIMIT 1")
    suspend fun get(): ProfileEntity?

    @Upsert suspend fun upsert(profile: ProfileEntity)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goal WHERE isActive = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    fun observeActive(): Flow<GoalEntity?>

    @Query("SELECT * FROM goal WHERE isActive = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActive(): GoalEntity?

    @Query("UPDATE goal SET isActive = 0, updatedAt = :now WHERE isActive = 1")
    suspend fun deactivateAll(now: Long)

    @Upsert suspend fun upsert(goal: GoalEntity)

    /** Makes [goal] the single active goal; earlier goals stay as history. */
    @Transaction
    suspend fun setActive(goal: GoalEntity) {
        deactivateAll(goal.updatedAt)
        upsert(goal.copy(isActive = true))
    }
}

@Dao
interface TargetOverrideDao {
    @Query("SELECT * FROM target_override WHERE deletedAt IS NULL ORDER BY effectiveFrom")
    fun observeAll(): Flow<List<TargetOverrideEntity>>

    @Upsert suspend fun upsert(item: TargetOverrideEntity)

    @Query("UPDATE target_override SET deletedAt = :now, updatedAt = :now WHERE nutrientKey = :key AND deletedAt IS NULL")
    suspend fun clear(key: String, now: Long)
}

@Dao
interface FoodDao {
    @Query("SELECT * FROM food WHERE id = :id")
    suspend fun get(id: String): FoodEntity?

    @Query("SELECT * FROM food WHERE id = :id")
    fun observe(id: String): Flow<FoodEntity?>

    @Query("SELECT * FROM food WHERE barcode = :gtin14 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun findByBarcode(gtin14: String): FoodEntity?

    @Query("SELECT * FROM food WHERE source = :source AND sourceId = :sourceId AND deletedAt IS NULL LIMIT 1")
    suspend fun findBySource(source: FoodSource, sourceId: String): FoodEntity?

    /** Local search: every word must match name or brand; favorites and recent foods first. */
    @Query(
        """
        SELECT * FROM food WHERE deletedAt IS NULL AND source != 'QUICK_ADD'
          AND (name LIKE '%' || :q || '%' OR brand LIKE '%' || :q || '%')
        ORDER BY isFavorite DESC, (lastUsedAt IS NULL), lastUsedAt DESC, name
        LIMIT :limit
        """,
    )
    suspend fun search(q: String, limit: Int = 50): List<FoodEntity>

    @Query("SELECT * FROM food WHERE deletedAt IS NULL AND lastUsedAt IS NOT NULL AND source != 'QUICK_ADD' ORDER BY lastUsedAt DESC LIMIT :limit")
    fun observeRecents(limit: Int = 50): Flow<List<FoodEntity>>

    @Query("SELECT * FROM food WHERE deletedAt IS NULL AND isFavorite = 1 ORDER BY name")
    fun observeFavorites(): Flow<List<FoodEntity>>

    @Query("SELECT * FROM food WHERE deletedAt IS NULL AND source IN ('CUSTOM', 'LABEL') ORDER BY name")
    fun observeCustom(): Flow<List<FoodEntity>>

    @Upsert suspend fun upsert(food: FoodEntity)

    @Query("UPDATE food SET isFavorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, now: Long)

    @Query("UPDATE food SET lastUsedAt = :now, lastQuantity = :quantity, lastServingLabel = :servingLabel, updatedAt = :now WHERE id = :id")
    suspend fun markUsed(id: String, quantity: Double, servingLabel: String, now: Long)

    @Query("UPDATE food SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}

@Dao
interface LogEntryDao {
    @Query("SELECT * FROM log_entry WHERE date = :date AND deletedAt IS NULL ORDER BY loggedAt")
    fun observeForDate(date: LocalDate): Flow<List<LogEntryEntity>>

    @Query("SELECT * FROM log_entry WHERE date = :date AND deletedAt IS NULL ORDER BY loggedAt")
    suspend fun getForDate(date: LocalDate): List<LogEntryEntity>

    @Query("SELECT * FROM log_entry WHERE date = :date AND mealSlotId = :mealSlotId AND deletedAt IS NULL ORDER BY loggedAt")
    suspend fun getForMeal(date: LocalDate, mealSlotId: String): List<LogEntryEntity>

    @Query("SELECT * FROM log_entry WHERE id = :id")
    suspend fun get(id: String): LogEntryEntity?

    @Query("SELECT * FROM log_entry WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<LogEntryEntity>

    @Upsert suspend fun upsert(entries: List<LogEntryEntity>)

    @Query("UPDATE log_entry SET deletedAt = :now, updatedAt = :now WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<String>, now: Long)

    @Query("UPDATE log_entry SET deletedAt = NULL, updatedAt = :now WHERE id IN (:ids)")
    suspend fun restore(ids: List<String>, now: Long)
}

@Dao
interface SavedMealDao {
    @Query("SELECT * FROM saved_meal WHERE deletedAt IS NULL ORDER BY name")
    fun observeMeals(): Flow<List<SavedMealEntity>>

    @Query("SELECT * FROM saved_meal_item WHERE deletedAt IS NULL ORDER BY savedMealId, sortOrder")
    fun observeItems(): Flow<List<SavedMealItemEntity>>

    @Query("SELECT * FROM saved_meal_item WHERE savedMealId = :mealId AND deletedAt IS NULL ORDER BY sortOrder")
    suspend fun itemsFor(mealId: String): List<SavedMealItemEntity>

    @Upsert suspend fun upsertMeal(meal: SavedMealEntity)
    @Upsert suspend fun upsertItems(items: List<SavedMealItemEntity>)

    @Transaction
    suspend fun save(meal: SavedMealEntity, items: List<SavedMealItemEntity>) {
        upsertMeal(meal)
        upsertItems(items)
    }

    @Query("UPDATE saved_meal SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteMeal(id: String, now: Long)
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_entry WHERE deletedAt IS NULL ORDER BY timestamp")
    fun observeAll(): Flow<List<WeightEntryEntity>>

    @Query("SELECT * FROM weight_entry WHERE deletedAt IS NULL ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): WeightEntryEntity?

    @Query("SELECT * FROM weight_entry WHERE id = :id")
    suspend fun get(id: String): WeightEntryEntity?

    @Upsert suspend fun upsert(entry: WeightEntryEntity)

    @Query("UPDATE weight_entry SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}

@Dao
interface WaterDao {
    @Query("SELECT * FROM water_entry WHERE date = :date AND deletedAt IS NULL ORDER BY timestamp")
    fun observeForDate(date: LocalDate): Flow<List<WaterEntryEntity>>

    @Upsert suspend fun upsert(entry: WaterEntryEntity)

    @Query("UPDATE water_entry SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}

@Dao
interface DayStatusDao {
    @Query("SELECT * FROM day_status WHERE date = :date AND deletedAt IS NULL LIMIT 1")
    fun observe(date: LocalDate): Flow<DayStatusEntity?>

    @Upsert suspend fun upsert(status: DayStatusEntity)
}
