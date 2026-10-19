package app.forkcast.fakes

import app.forkcast.core.data.health.HcAvailability
import app.forkcast.core.data.health.HealthRepository
import app.forkcast.core.data.health.HealthStatus
import app.forkcast.core.data.health.SyncReason
import app.forkcast.core.data.repository.MaintenanceEstimate
import app.forkcast.core.data.repository.MaintenanceProgress
import app.forkcast.core.data.repository.MaintenanceRepository
import app.forkcast.core.domain.ActivityBonus
import app.forkcast.core.model.ActivityDay
import app.forkcast.core.model.DayCompleteness
import app.forkcast.core.model.HealthSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate

class FakeHealthRepository : HealthRepository {
    val statusState = MutableStateFlow(
        HealthStatus(HcAvailability.AVAILABLE, HealthSettings(), emptySet(), true, true, null, null, null),
    )
    val activity = MutableStateFlow<Map<LocalDate, ActivityDay>>(emptyMap())
    var syncs = 0

    override val status: Flow<HealthStatus> = statusState
    override suspend fun refresh() = Unit
    override suspend fun permissionsToRequest(): Set<String> = emptySet()
    override suspend fun onPermissionsResult(granted: Set<String>) = Unit
    override suspend fun updateSettings(transform: (HealthSettings) -> HealthSettings) =
        statusState.update { it.copy(settings = transform(it.settings)) }
    override suspend fun disconnect() = statusState.update { it.copy(settings = it.settings.copy(connected = false)) }
    override suspend fun sync(reason: SyncReason) { syncs++ }
    override fun observeActivity(date: LocalDate): Flow<ActivityDay?> = activity.map { it[date] }
    override fun observeActivityRange(from: LocalDate, to: LocalDate): Flow<List<ActivityDay>> =
        activity.map { m -> m.values.filter { it.date in from..to }.sortedBy { it.date } }
    override suspend fun activityRange(from: LocalDate, to: LocalDate): List<ActivityDay> =
        activity.value.values.filter { it.date in from..to }.sortedBy { it.date }
}

class FakeMaintenanceRepository : MaintenanceRepository {
    val latestState = MutableStateFlow<MaintenanceEstimate?>(null)
    val progress = MutableStateFlow(MaintenanceProgress(0, 0))
    val completeness = MutableStateFlow<Map<LocalDate, DayCompleteness>>(emptyMap())
    val bonus = MutableStateFlow<Map<LocalDate, ActivityBonus>>(emptyMap())

    override val latest: Flow<MaintenanceEstimate?> = latestState
    override fun observeProgress(): Flow<MaintenanceProgress> = progress
    override suspend fun updateIfDue() = Unit
    override fun observeCompleteness(date: LocalDate): Flow<DayCompleteness> = completeness.map { it[date] ?: DayCompleteness.AUTO }
    override suspend fun setCompleteness(date: LocalDate, completeness: DayCompleteness) =
        this.completeness.update { it + (date to completeness) }
    override fun observeActivityBonus(date: LocalDate): Flow<ActivityBonus> = bonus.map { it[date] ?: ActivityBonus.NONE }
    override suspend fun bonusKcal(date: LocalDate): Double = bonus.value[date]?.bonusKcal ?: 0.0
    override fun observeActivityBonuses(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, ActivityBonus>> = bonus
    override fun observeAverageBonus(today: LocalDate): Flow<Double> = MutableStateFlow(0.0)
}
