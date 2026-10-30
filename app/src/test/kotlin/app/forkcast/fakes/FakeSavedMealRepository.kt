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
    private val removed = mutableMapOf<String, SavedMeal>()
    override suspend fun delete(id: String) = state.update { l -> l.firstOrNull { it.id == id }?.let { removed[id] = it }; l.filterNot { it.id == id } }
    override suspend fun restore(id: String) { removed.remove(id)?.let { m -> state.update { it + m } } }
    override suspend fun update(id: String, name: String, quantities: Map<String, Double>, removed: Set<String>) = state.update { l ->
        l.map { m ->
            if (m.id != id) return@map m
            m.copy(
                name = name.ifBlank { m.name },
                items = m.items.filterNot { it.id in removed }.map { i ->
                    quantities[i.id]?.let { q -> i.copy(quantity = q, nutrients = i.nutrients.mapValues { (_, v) -> v * q / i.quantity }) } ?: i
                },
            )
        }
    }
    override suspend fun toEntries(id: String, date: LocalDate, mealSlotId: String): List<LogEntry> =
        state.value.firstOrNull { it.id == id }?.items.orEmpty().map { i ->
            LogEntry("", date, mealSlotId, i.foodId, i.displayName, i.brand, i.quantity, i.servingLabel, i.grams, i.nutrients, i.source, null, Instant.EPOCH)
        }
}
