package app.forkcast.fakes

import app.forkcast.core.data.repository.WaterRepository
import app.forkcast.core.model.WaterEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate

class FakeWaterRepository : WaterRepository {
    val list = MutableStateFlow<List<WaterEntry>>(emptyList())
    private var next = 0
    override fun observeDay(date: LocalDate): Flow<List<WaterEntry>> = list.map { l -> l.filter { it.date == date } }
    override suspend fun add(date: LocalDate, ml: Double): String {
        val id = "water${next}"
        list.update { it + WaterEntry(id, Instant.ofEpochMilli(next++.toLong()), date, ml) }
        return id
    }
    override suspend fun delete(id: String) = list.update { l -> l.filterNot { it.id == id } }
}
