package app.forkcast.core.data.notify

import android.content.Context
import app.forkcast.core.data.R
import app.forkcast.core.data.db.AiDao
import app.forkcast.core.data.db.NotificationLogEntity
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.MaintenanceRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.core.domain.DayMath
import app.forkcast.core.domain.Forecast
import app.forkcast.core.domain.NotificationRules
import app.forkcast.core.model.NotificationSettings
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.kcal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.text.NumberFormat
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Notification types as recorded in the once-per-day log. */
object NotificationTypes {
    const val BUDGET = "budget"
    const val EVENING = "evening"
    const val PROTEIN = "protein"
    const val MEAL = "meal"
    const val WEIGH_IN = "weigh_in"
}

/**
 * Decides and posts Forkcast's own notifications (SPEC §4.8): budget thresholds after a log
 * change, and the timed reminders run by [ReminderWorker]. Each fires at most once per day,
 * nothing posts during quiet hours, and copy follows DESIGN.md §9.
 */
@Singleton
class NotificationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: Notifier,
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
    private val log: LogRepository,
    private val weights: WeightRepository,
    private val slots: MealSlotRepository,
    private val dao: AiDao,
    private val maintenance: MaintenanceRepository,
    private val clock: Clock,
) {
    private val n get() = NumberFormat.getIntegerInstance()

    /** Call after the day's log changed. */
    suspend fun checkBudget() {
        val s = settings.settings.first().notifications
        if (!s.budgetEnabled) return
        val today = LocalDate.now(clock)
        val targets = profiles.observeDailyTargets().first() ?: return
        // Activity above usual raises the day's allowance (SPEC §5.2).
        val totals = DayMath.totals(log.range(today, today), targets.budgetKcal, maintenance.bonusKcal(today))
        val eaten = totals.eatenKcal
        val budget = totals.allowanceKcal
        val fired = dao.notificationsOn(today).filter { it.type == NotificationTypes.BUDGET }.map { it.threshold }.toSet()
        val decision = NotificationRules.budgetThresholds(eaten, budget, s.budgetThresholds, fired)
        decision.record.forEach { record(today, NotificationTypes.BUDGET, it) }
        val pct = decision.announce ?: return
        if (quiet(s)) return
        val text = when {
            eaten > budget -> context.getString(R.string.notif_budget_over, n.format((eaten - budget).roundToInt()))
            pct >= 100 -> context.getString(R.string.notif_budget_reached, n.format(eaten.roundToInt()), n.format(budget.roundToInt()))
            else -> context.getString(R.string.notif_budget_threshold, n.format(eaten.roundToInt()), n.format(budget.roundToInt()), n.format((budget - eaten).roundToInt()))
        }
        notifier.post(Channel.BUDGET, ID_BUDGET, context.getString(R.string.notif_budget_title), text, open = "today")
    }

    /** All reminder times in minutes after midnight, for scheduling. */
    suspend fun reminderMinutes(): List<Int> {
        val s = settings.settings.first().notifications
        return buildList {
            if (s.eveningEnabled) add(s.eveningMinute)
            if (s.proteinEnabled) add(s.proteinMinute)
            if (s.weighInEnabled) add(s.weighInMinute)
            if (s.mealRemindersEnabled) addAll(mealReminders(s).values)
        }
    }

    /**
     * Posts every reminder whose time has come in the last [windowMinutes] and that hasn't
     * fired today. A late run (phone dozing) still delivers within the window; an older one is
     * skipped rather than arriving hours late.
     */
    suspend fun runReminders(windowMinutes: Int = 45) {
        val s = settings.settings.first().notifications
        val today = LocalDate.now(clock)
        val now = LocalTime.now(clock).let { it.hour * 60 + it.minute }
        fun due(minute: Int) = now >= minute && now - minute <= windowMinutes
        val fired = dao.notificationsOn(today).map { it.type to it.threshold }.toSet()
        val entries = log.range(today, today)
        val targets = profiles.observeDailyTargets().first()

        if (s.eveningEnabled && due(s.eveningMinute) && (NotificationTypes.EVENING to 0) !in fired && targets != null) {
            record(today, NotificationTypes.EVENING, 0)
            val left = targets.budgetKcal + maintenance.bonusKcal(today) - entries.sumOf { it.nutrients.kcal }
            val history = log.range(today.minusDays(60), today.minusDays(1)).map {
                val t = it.loggedAt.atZone(clock.zone).toLocalTime()
                Forecast.Logged(it.date, t.hour * 60 + t.minute, it.nutrients.kcal)
            }
            val usual = Forecast.expectedAfter(history, today, s.eveningMinute)
            val text = if (usual != null && usual > 0) {
                val dinner = slots.observeMealSlots().first().firstOrNull { it.name.equals("Dinner", ignoreCase = true) }?.name?.lowercase()
                    ?: context.getString(R.string.notif_evening_rest)
                context.getString(R.string.notif_evening, n.format(left.roundToInt()), dinner, n.format((usual / 10).roundToInt() * 10))
            } else {
                context.getString(R.string.notif_evening_simple, n.format(left.roundToInt()))
            }
            if (left > 0 && !quiet(s)) notifier.post(Channel.EVENING, ID_EVENING, context.getString(R.string.notif_evening_title), text, open = "today")
        }

        if (s.proteinEnabled && due(s.proteinMinute) && (NotificationTypes.PROTEIN to 0) !in fired && targets != null) {
            record(today, NotificationTypes.PROTEIN, 0)
            val target = targets.targets[NutrientKey.PROTEIN_G]?.amount ?: 0.0
            val eaten = entries.sumOf { it.nutrients[NutrientKey.PROTEIN_G] ?: 0.0 }
            if (NotificationRules.proteinCheckDue(eaten, target) && !quiet(s)) {
                notifier.post(
                    Channel.PROTEIN, ID_PROTEIN, context.getString(R.string.notif_protein_title),
                    context.getString(R.string.notif_protein, n.format((target - eaten).roundToInt())), open = "today",
                )
            }
        }

        if (s.weighInEnabled && due(s.weighInMinute) && (NotificationTypes.WEIGH_IN to 0) !in fired) {
            record(today, NotificationTypes.WEIGH_IN, 0)
            // Phase 3: also skipped when Health Connect delivered a weight today.
            val weighedToday = weights.entries.first().any { it.timestamp.atZone(clock.zone).toLocalDate() == today }
            if (!weighedToday && !quiet(s)) {
                notifier.post(Channel.WEIGH_IN, ID_WEIGH_IN, context.getString(R.string.notif_weigh_in_title), context.getString(R.string.notif_weigh_in), open = "today")
            }
        }

        if (s.mealRemindersEnabled) {
            val mealSlots = slots.observeMealSlots().first()
            for ((slotId, minute) in mealReminders(s)) {
                val key = NotificationTypes.MEAL to slotId.hashCode()
                if (!due(minute) || key in fired) continue
                record(today, NotificationTypes.MEAL, slotId.hashCode())
                val slot = mealSlots.firstOrNull { it.id == slotId } ?: continue
                if (entries.none { it.mealSlotId == slotId } && !quiet(s)) {
                    notifier.post(Channel.MEAL_REMINDER, ID_MEAL + slotId.hashCode() % 1000, slot.name, context.getString(R.string.notif_meal, slot.name), open = "today")
                }
            }
        }
    }

    private suspend fun mealReminders(s: NotificationSettings): Map<String, Int> =
        s.mealReminderMinutes.ifEmpty { NotificationRules.defaultMealReminders(slots.observeMealSlots().first().map { it.id to it.name }) }

    private fun quiet(s: NotificationSettings): Boolean {
        val t = LocalTime.now(clock)
        return NotificationRules.inQuietHours(t.hour * 60 + t.minute, s.quietStartMinute, s.quietEndMinute)
    }

    private suspend fun record(date: LocalDate, type: String, threshold: Int) {
        val now = clock.millis()
        dao.insertNotification(NotificationLogEntity(UUID.randomUUID().toString(), date, type, threshold, now, now, now))
    }

    private companion object {
        const val ID_BUDGET = 2001
        const val ID_EVENING = 2002
        const val ID_PROTEIN = 2003
        const val ID_WEIGH_IN = 2004
        const val ID_MEAL = 3000
    }
}
