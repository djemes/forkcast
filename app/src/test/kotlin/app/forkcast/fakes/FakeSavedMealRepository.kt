package app.forkcast.fakes

import app.forkcast.core.data.repository.SavedMealRepository
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.SavedMeal
import app.forkcast.core.model.SavedMealItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate

class FakeSavedMealRepository : SavedMealRepository {
    val state = MutableStateFlow<List<SavedMeal>>(emptyList())
    override val meals: Flow<List<SavedMeal>> = state
    private var next = 0

    override suspend fun saveFrom(name: String, entries: List<LogEntry>): String {
        val id = "m${next++}"
        state.update {
            it + SavedMeal(id, name.trim(), entries.map { e ->
                SavedMealItem(e.id, e.foodId, e.displayName, e.brand, e.quantity, e.servingLabel, e.grams, e.nutrients, e.source)
            })
        }
        return id
    }
    override suspend fun rename(id: String, name: String) = state.update { l -> l.map { if (it.id == id) it.copy(name = name) else it } }
    override suspend fun delete(id: String) = state.update { l -> l.filterNot { it.id == id } }
    override suspend fun toEntries(id: String, date: LocalDate, mealSlotId: String): List<LogEntry> =
        state.value.firstOrNull { it.id == id }?.items.orEmpty().map { i ->
            LogEntry("", date, mealSlotId, i.foodId, i.displayName, i.brand, i.quantity, i.servingLabel, i.grams, i.nutrients, i.source, null, Instant.EPOCH)
        }
}
