package app.forkcast.fakes

import app.forkcast.core.data.db.LoggedDay
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.MealSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate

class FakeLogRepository : LogRepository {
    /** id → (entry, deleted) */
    val rows = MutableStateFlow<Map<String, Pair<LogEntry, Boolean>>>(emptyMap())
    private var next = 0

    override fun observeDay(date: LocalDate): Flow<List<LogEntry>> =
        rows.map { m -> m.values.filter { !it.second && it.first.date == date }.map { it.first }.sortedBy { it.loggedAt } }
    override fun observeRange(from: LocalDate, to: LocalDate): Flow<List<LogEntry>> =
        rows.map { m -> m.values.filter { !it.second && it.first.date in from..to }.map { it.first }.sortedBy { it.loggedAt } }
    override suspend fun range(from: LocalDate, to: LocalDate) = observeRange(from, to).first()
    override fun observeLatestDay(excluding: LocalDate): Flow<LoggedDay?> = rows.map { m ->
        m.values.filter { !it.second && it.first.date != excluding }.groupBy { it.first.date }
            .maxByOrNull { it.key }?.let { (d, list) -> LoggedDay(d, list.size) }
    }
    override suspend fun entriesForMeal(date: LocalDate, mealSlotId: String) =
        rows.value.values.filter { !it.second && it.first.date == date && it.first.mealSlotId == mealSlotId }.map { it.first }
    override suspend fun get(id: String) = rows.value[id]?.takeIf { !it.second }?.first
    override suspend fun add(entries: List<LogEntry>): List<String> = entries.map { e ->
        val id = "e${next++}"
        rows.update { it + (id to (e.copy(id = id) to false)) }
        id
    }
    override suspend fun update(entry: LogEntry) = rows.update { it + (entry.id to (entry to false)) }
    override suspend fun delete(ids: List<String>) = rows.update { m -> m.mapValues { (k, v) -> if (k in ids) v.first to true else v } }
    override suspend fun restore(ids: List<String>) = rows.update { m -> m.mapValues { (k, v) -> if (k in ids) v.first to false else v } }
    override suspend fun copy(ids: List<String>, toDate: LocalDate, toMealSlotId: String?) =
        add(ids.mapNotNull { rows.value[it]?.first }.map { it.copy(date = toDate, mealSlotId = toMealSlotId ?: it.mealSlotId) })
    override suspend fun setEatenMinute(ids: List<String>, minute: Int) =
        rows.update { m -> m.mapValues { (k, v) -> if (k in ids) v.first.copy(eatenMinute = minute) to v.second else v } }
}

class FakeMealSlotRepository(slots: List<MealSlot> = DEFAULT) : MealSlotRepository {
    val state = MutableStateFlow(slots)
    private val deleted = mutableMapOf<String, MealSlot>()
    override fun observeMealSlots(): Flow<List<MealSlot>> = state
    override suspend fun add(name: String, startMinute: Int?, endMinute: Int?): String {
        val id = "m${state.value.size + deleted.size}"
        state.update { it + MealSlot(id, name.trim(), it.size, startMinute, endMinute) }
        return id
    }
    override suspend fun update(slot: MealSlot) = state.update { l -> l.map { if (it.id == slot.id) slot.copy(sortOrder = it.sortOrder) else it } }
    override suspend fun delete(id: String) = state.update { l -> l.firstOrNull { it.id == id }?.let { deleted[id] = it }; l.filterNot { it.id == id } }
    override suspend fun restore(id: String) = state.update { l -> deleted.remove(id)?.let { (l + it).sortedBy { s -> s.sortOrder } } ?: l }
    override suspend fun reorder(idsInOrder: List<String>) = state.update { l ->
        idsInOrder.mapIndexedNotNull { i, id -> l.firstOrNull { it.id == id }?.copy(sortOrder = i) }
    }

    companion object {
        val DEFAULT = listOf(
            MealSlot("b", "Breakfast", 0, 240, 660),
            MealSlot("l", "Lunch", 1, 660, 900),
            MealSlot("d", "Dinner", 2, 1020, 1320),
            MealSlot("s", "Snacks", 3, null, null),
        )
    }
}
