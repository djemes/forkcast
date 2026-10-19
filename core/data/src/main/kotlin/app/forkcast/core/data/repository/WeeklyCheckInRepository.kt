package app.forkcast.core.data.repository

import app.forkcast.core.data.db.DayStatusDao
import app.forkcast.core.data.db.HealthDao
import app.forkcast.core.domain.AdaptiveTdee
import app.forkcast.core.domain.WeighIn
import app.forkcast.core.domain.WeeklyCheckIn
import app.forkcast.core.domain.WeightTrend
import app.forkcast.core.model.DayCompleteness
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.TdeeMethod
import app.forkcast.core.model.kcal
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Gathers one week for the weekly check-in (owner, 2026-10-19). */
interface WeeklyCheckInRepository {
    /** The week [start]..[start]+6. */
    suspend fun summary(start: LocalDate): WeeklyCheckIn.Summary
}

@Singleton
class RoomWeeklyCheckInRepository @Inject constructor(
    private val log: LogRepository,
    private val dayStatus: DayStatusDao,
    private val profiles: ProfileRepository,
    private val maintenance: MaintenanceRepository,
    private val weights: WeightRepository,
    private val healthDao: HealthDao,
    private val clock: Clock,
) : WeeklyCheckInRepository {

    override suspend fun summary(start: LocalDate): WeeklyCheckIn.Summary {
        val end = start.plusDays(6)
        val entries = log.range(start, end)
        val statuses = dayStatus.range(start, end).associateBy { it.date }
        val byDay = entries.groupBy { it.date }
        val complete = byDay.filter { (d, list) ->
            val mark = statuses[d]?.let { if (it.markedComplete) DayCompleteness.COMPLETE else DayCompleteness.INCOMPLETE } ?: DayCompleteness.AUTO
            AdaptiveTdee.isComplete(AdaptiveTdee.DayLog(d, list.sumOf { it.nutrients.kcal }, list.map { it.mealSlotId }.distinct().size, mark))
        }.keys
        val targets = profiles.observeDailyTargets().first()
        val goal = profiles.activeGoal.first()
        val planned = targets?.plan?.pace?.kgPerWeek?.let { rate ->
            when (goal?.goalType) {
                GoalType.LOSE -> -rate
                GoalType.GAIN -> rate
                else -> null
            }
        }
        val weighIns = weights.entries.first().map { WeighIn(it.timestamp, it.kg) }
        val trend = WeightTrend.daily(weighIns.filter { !it.instant.atZone(clock.zone).toLocalDate().isAfter(end) }, clock.zone, end)
        val before = trend.lastOrNull { it.date.isBefore(start) }?.trendKg
        val atEnd = trend.lastOrNull()?.trendKg
        val inWeek = weighIns.map { it.instant.atZone(clock.zone).toLocalDate() }.filter { it in start..end }.distinct().size
        val activity = healthDao.activityRange(start, end)
        val now = healthDao.tdeeAsOf(end.plusDays(1))?.takeIf { it.method == TdeeMethod.ADAPTIVE }
        val prev = healthDao.tdeeAsOf(start)?.takeIf { it.method == TdeeMethod.ADAPTIVE && it.id != now?.id }
        return WeeklyCheckIn.summarize(
            WeeklyCheckIn.Input(
                start = start,
                end = end,
                kcalByDay = byDay.mapValues { (_, list) -> list.sumOf { it.nutrients.kcal } },
                completeDays = complete,
                budgetKcal = targets?.budgetKcal ?: 0,
                bonusByDay = maintenance.observeActivityBonuses(start, end).first().mapValues { it.value.bonusKcal },
                trendBeforeKg = before ?: trend.firstOrNull { it.date in start..end }?.trendKg,
                trendEndKg = atEnd?.takeIf { inWeek > 0 || before != null },
                weighIns = inWeek,
                plannedKgPerWeek = planned,
                maintenanceKcal = now?.kcal,
                maintenanceBeforeKcal = prev?.kcal,
                stepsByDay = activity.mapNotNull { a -> a.steps?.let { a.date to it } }.toMap(),
                activeKcalByDay = activity.mapNotNull { a -> a.activeKcal?.let { a.date to it } }.toMap(),
            ),
        )
    }
}
