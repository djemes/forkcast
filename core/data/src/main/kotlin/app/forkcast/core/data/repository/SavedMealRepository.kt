package app.forkcast.core.data.repository

import app.forkcast.core.data.db.SavedMealDao
import app.forkcast.core.data.db.SavedMealEntity
import app.forkcast.core.data.db.SavedMealItemEntity
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.SavedMeal
import app.forkcast.core.model.SavedMealItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface SavedMealRepository {
    val meals: Flow<List<SavedMeal>>
    /** Saves [entries] (their snapshots) as a named meal; returns its id. */
    suspend fun saveFrom(name: String, entries: List<LogEntry>): String
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    /** Turns a saved meal into log entries for [date]/[mealSlotId] (not yet stored). */
    suspend fun toEntries(id: String, date: LocalDate, mealSlotId: String): List<LogEntry>
}

@Singleton
class RoomSavedMealRepository @Inject constructor(
    private val dao: SavedMealDao,
    private val clock: Clock,
) : SavedMealRepository {

    override val meals: Flow<List<SavedMeal>> = combine(dao.observeMeals(), dao.observeItems()) { meals, items ->
        val byMeal = items.groupBy { it.savedMealId }
        meals.map { m -> SavedMeal(m.id, m.name, byMeal[m.id].orEmpty().sortedBy { it.sortOrder }.map { it.toModel() }) }
    }

    override suspend fun saveFrom(name: String, entries: List<LogEntry>): String {
        val now = clock.millis()
        val id = UUID.randomUUID().toString()
        dao.save(
            SavedMealEntity(id, name.trim(), now, now),
            entries.sortedBy { it.loggedAt }.mapIndexed { i, e ->
                SavedMealItemEntity(
                    id = UUID.randomUUID().toString(),
                    savedMealId = id,
                    sortOrder = i,
                    foodId = e.foodId,
                    displayName = e.displayName,
                    brand = e.brand,
                    quantity = e.quantity,
                    servingLabel = e.servingLabel,
                    grams = e.grams,
                    nutrients = e.nutrients,
                    source = e.source,
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )
        return id
    }

    override suspend fun rename(id: String, name: String) = dao.rename(id, name.trim(), clock.millis())

    override suspend fun delete(id: String) = dao.softDeleteMeal(id, clock.millis())

    override suspend fun toEntries(id: String, date: LocalDate, mealSlotId: String): List<LogEntry> {
        val now = clock.instant()
        return dao.itemsFor(id).map { i ->
            LogEntry(
                id = "",
                date = date,
                mealSlotId = mealSlotId,
                foodId = i.foodId,
                displayName = i.displayName,
                brand = i.brand,
                quantity = i.quantity,
                servingLabel = i.servingLabel,
                grams = i.grams,
                nutrients = i.nutrients,
                source = i.source,
                note = null,
                loggedAt = now,
            )
        }
    }
}

private fun SavedMealItemEntity.toModel() =
    SavedMealItem(id, foodId, displayName, brand, quantity, servingLabel, grams, nutrients, source)
