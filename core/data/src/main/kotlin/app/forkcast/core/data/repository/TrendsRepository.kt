package app.forkcast.core.data.repository

import app.forkcast.core.data.db.DayStatusDao
import app.forkcast.core.data.db.LogEntryDao
import app.forkcast.core.domain.AdaptiveTdee
import app.forkcast.core.domain.DailyTargets
import app.forkcast.core.domain.HealthRules
import app.forkcast.core.domain.MealKind
import app.forkcast.core.domain.MealTiming
import app.forkcast.core.domain.Trends
import app.forkcast.core.model.DayCompleteness
import app.forkcast.core.model.Goal
import app.forkcast.core.model.WeightEntry
import app.forkcast.core.model.kcal
import app.forkcast.core.model.sumNutrients
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the Trends screen shows for one period (SPEC §4.10). */
data class TrendsData(
    val today: LocalDate,
    val period: Trends.Period,
    /** Every day of the period, oldest first; days without entries have null nutrients. */
    val days: List<Trends.Day>,
    /** Per nutrient, the share of averaged energy from entries that report it. */
    val coverage: Map<app.forkcast.core.model.NutrientKey, Double>,
    /** Every day with entries, ever (for streaks). */
    val loggedDates: Set<LocalDate>,
    val targets: DailyTargets?,
    val weights: List<WeightEntry>,
    val goal: Goal?,
    /** Meal timing over the averaged days, or null until there are enough days with real times. */
    val timing: MealTiming.Insights? = null,
    /** Meal slots, for naming the meals in [timing]. */
    val mealSlots: List<app.forkcast.core.model.MealSlot> = emptyList(),
)

interface TrendsRepository {
    fun observe(period: Trends.Period): Flow<TrendsData>
}

@Singleton
class RoomTrendsRepository @Inject constructor(
    private val log: LogRepository,
    private val logDao: LogEntryDao,
    private val dayStatus: DayStatusDao,
    private val profiles: ProfileRepository,
    private val maintenance: MaintenanceRepository,
    private val weights: WeightRepository,
    private val clock: Clock,
    private val mealSlots: MealSlotRepository,
) : TrendsRepository {

    override fun observe(period: Trends.Period): Flow<TrendsData> {
        val today = LocalDate.now(clock)
        val range = Trends.range(period, today)
        val from = range.start
        return combine(
            combine(log.observeRange(from, today), dayStatus.observeRange(from, today), maintenance.observeActivityBonuses(from, today)) { e, s, b -> Triple(e, s, b) },
            logDao.observeLoggedDates(),
            profiles.observeDailyTargets(),
            combine(weights.entries, mealSlots.observeMealSlots()) { w, m -> w to m },
            profiles.activeGoal,
        ) { (entries, statuses, bonuses), dates, targets, (weightList, slots), goal ->
            val byDay = entries.groupBy { it.date }
            val marks = statuses.associateBy { it.date }
            val budget = targets?.budgetKcal ?: 0
            val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { d ->
                val list = byDay[d]
                val mark = marks[d]?.let { if (it.markedComplete) DayCompleteness.COMPLETE else DayCompleteness.INCOMPLETE } ?: DayCompleteness.AUTO
                Trends.Day(
                    date = d,
                    nutrients = list?.map { it.nutrients }?.sumNutrients(),
                    // The current budget: past budgets aren't stored (same as Today and the weekly check-in).
                    allowanceKcal = budget + (bonuses[d]?.bonusKcal ?: 0.0),
                    complete = list != null && AdaptiveTdee.isComplete(
                        AdaptiveTdee.DayLog(d, list.sumOf { it.nutrients.kcal }, list.map { it.mealSlotId }.distinct().size, mark),
                    ),
                )
            }.toList()
            val averagedDays = Trends.averagedDays(days, today).days
            val averaged = averagedDays.map { it.date }.toSet()
            // Breakfast, Lunch and Dinner: meals with a window that aren't snacks.
            val timedMeals = slots.filter {
                it.startMinute != null && it.endMinute != null && HealthRules.mealKind(it.name, it.startMinute) != MealKind.SNACK
            }.map { it.id }
            val timing = MealTiming.insights(
                entries.map { MealTiming.Item(it.date, it.mealSlotId, it.eatenMinute, it.nutrients.kcal) },
                averagedDays.associate { it.date to it.allowanceKcal },
                timedMeals,
            )
            TrendsData(
                today = today,
                period = period,
                days = days,
                coverage = Trends.coverage(entries.filter { it.date in averaged }.map { it.nutrients }),
                loggedDates = dates.toSet(),
                targets = targets,
                weights = weightList,
                goal = goal,
                timing = timing,
                mealSlots = slots,
            )
        }
    }
}
