package app.forkcast.core.data.repository

import app.forkcast.core.data.db.DayStatusDao
import app.forkcast.core.data.db.DayStatusEntity
import app.forkcast.core.data.db.GoalDao
import app.forkcast.core.data.db.ProfileDao
import app.forkcast.core.data.db.HealthDao
import app.forkcast.core.data.db.TdeeEstimateEntity
import app.forkcast.core.domain.AdaptiveTdee
import app.forkcast.core.domain.EnergyMath
import app.forkcast.core.domain.ExerciseMath
import app.forkcast.core.domain.WeighIn
import app.forkcast.core.model.DayCompleteness
import app.forkcast.core.model.TdeeConfidence
import app.forkcast.core.model.TdeeMethod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The latest adaptive estimate, for display (SPEC §5.6). */
data class MaintenanceEstimate(
    val date: LocalDate,
    val kcal: Double,
    val confidence: TdeeConfidence?,
    val completeDays: Int,
    val weighIns: Int,
)

/** Progress toward the first estimate, when there is none yet. */
data class MaintenanceProgress(val completeDays: Int, val weighIns: Int)

interface MaintenanceRepository {
    val latest: Flow<MaintenanceEstimate?>
    /** Complete days and weigh-ins in the current window (for "8 of 14 days"). */
    fun observeProgress(): Flow<MaintenanceProgress>
    /** Adds this week's estimate when a week has passed and the data is there. */
    suspend fun updateIfDue()
    fun observeCompleteness(date: LocalDate): Flow<DayCompleteness>
    suspend fun setCompleteness(date: LocalDate, completeness: DayCompleteness)
    /** What synced exercise adds to [date]'s budget (0 unless Added back). */
    fun observeExerciseBonus(date: LocalDate): Flow<Double>
    suspend fun exerciseBonus(date: LocalDate): Double
    /** Average daily bonus over the 28 days before [today] (Added back takes it out of adaptive maintenance). */
    fun observeAverageBonus(today: LocalDate): Flow<Double>
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RoomMaintenanceRepository @Inject constructor(
    private val healthDao: HealthDao,
    private val dayStatusDao: DayStatusDao,
    private val log: LogRepository,
    private val weights: WeightRepository,
    private val profileDao: ProfileDao,
    private val goalDao: GoalDao,
    private val clock: Clock,
) : MaintenanceRepository {

    private val mutex = Mutex()
    // Daos, not ProfileRepository: the daily targets read this repository.
    private val profile = profileDao.observe().map { it?.toModel() }.distinctUntilChanged()

    override val latest: Flow<MaintenanceEstimate?> = healthDao.observeLatestTdee().map { row ->
        row?.takeIf { it.method == TdeeMethod.ADAPTIVE }?.let { MaintenanceEstimate(it.date, it.kcal, it.confidence, it.completeDays, it.weighIns) }
    }.distinctUntilChanged()

    override fun observeProgress(): Flow<MaintenanceProgress> {
        val today = LocalDate.now(clock)
        val window = AdaptiveTdee.window(today)
        return combine(
            log.observeRange(window.start, window.endInclusive),
            weights.entries,
            dayStatusDao.observeRange(window.start, window.endInclusive),
        ) { entries, w, status ->
            val days = dayLogs(entries, status.associateBy { it.date })
            MaintenanceProgress(
                completeDays = days.count { AdaptiveTdee.isComplete(it) },
                weighIns = w.map { it.timestamp.atZone(clock.zone).toLocalDate() }.filter { it in window }.distinct().size,
            )
        }
    }

    override suspend fun updateIfDue() = mutex.withLock {
        val today = LocalDate.now(clock)
        val last = healthDao.latestTdee()?.takeIf { it.method == TdeeMethod.ADAPTIVE }
        if (!AdaptiveTdee.isDue(last?.date, today)) return@withLock
        val profile = profileDao.get()?.toModel() ?: return@withLock
        val goal = goalDao.getActive()?.toModel() ?: return@withLock
        val window = AdaptiveTdee.window(today)
        val statuses = dayStatusDao.range(window.start, window.endInclusive).associateBy { it.date }
        val days = dayLogs(log.range(window.start, window.endInclusive), statuses)
        val weighIns = weights.entries.first().map { WeighIn(it.timestamp, it.kg) }
        val previous = last?.kcal ?: run {
            // First estimate starts from the formula's all-in maintenance at the chosen activity level.
            val kg = weighIns.maxByOrNull { it.instant }?.kg ?: goal.startWeightKg
            val bmr = EnergyMath.bmrMifflinStJeor(profile.sex, kg, profile.heightCm, EnergyMath.ageYears(profile.birthDate, today))
            EnergyMath.tdee(bmr, profile.activityLevel)
        }
        val estimate = AdaptiveTdee.estimate(today, days, weighIns, clock.zone, previous)
        val kcal = estimate.kcal ?: return@withLock
        val now = clock.millis()
        healthDao.upsertTdee(
            TdeeEstimateEntity(
                UUID.randomUUID().toString(), today, TdeeMethod.ADAPTIVE, kcal, estimate.observedKcal, estimate.confidence,
                estimate.completeDays, estimate.weighIns, now, now,
            ),
        )
    }

    override fun observeCompleteness(date: LocalDate): Flow<DayCompleteness> =
        dayStatusDao.observe(date).map { it.toCompleteness() }.distinctUntilChanged()

    override suspend fun setCompleteness(date: LocalDate, completeness: DayCompleteness) {
        val now = clock.millis()
        val existing = dayStatusDao.get(date)
        val row = when (completeness) {
            DayCompleteness.AUTO -> existing?.copy(deletedAt = now, updatedAt = now) ?: return
            else -> (existing ?: DayStatusEntity(UUID.randomUUID().toString(), date, false, null, now, now))
                .copy(markedComplete = completeness == DayCompleteness.COMPLETE, deletedAt = null, updatedAt = now)
        }
        dayStatusDao.upsert(row)
    }

    override fun observeExerciseBonus(date: LocalDate): Flow<Double> =
        combine(profile, healthDao.observeActivity(date)) { p, a ->
            if (p == null) 0.0 else ExerciseMath.bonusKcal(p, a?.activeKcal)
        }.distinctUntilChanged()

    override suspend fun exerciseBonus(date: LocalDate): Double = observeExerciseBonus(date).first()

    override fun observeAverageBonus(today: LocalDate): Flow<Double> {
        val window = AdaptiveTdee.window(today)
        return profile.flatMapLatest { p ->
            if (p == null) flowOf(0.0)
            else healthDao.observeActivityRange(window.start, window.endInclusive).map { rows ->
                rows.sumOf { ExerciseMath.bonusKcal(p, it.activeKcal) } / AdaptiveTdee.WINDOW_DAYS
            }
        }.distinctUntilChanged()
    }

    private fun dayLogs(entries: List<app.forkcast.core.model.LogEntry>, statuses: Map<LocalDate, DayStatusEntity>): List<AdaptiveTdee.DayLog> {
        val byDay = entries.groupBy { it.date }
        return (byDay.keys + statuses.keys).distinct().map { d ->
            val list = byDay[d].orEmpty()
            AdaptiveTdee.DayLog(d, list.sumOf { it.nutrients[app.forkcast.core.model.NutrientKey.ENERGY_KCAL] ?: 0.0 }, list.map { it.mealSlotId }.distinct().size, statuses[d].toCompleteness())
        }
    }
}

private fun DayStatusEntity?.toCompleteness(): DayCompleteness = when {
    this == null || deletedAt != null -> DayCompleteness.AUTO
    markedComplete -> DayCompleteness.COMPLETE
    else -> DayCompleteness.INCOMPLETE
}
