package app.forkcast.core.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Meal types Health Connect knows; the data layer maps these to its MEAL_TYPE_* constants. */
enum class MealKind { BREAKFAST, LUNCH, DINNER, SNACK, UNKNOWN }

/** Pure rules for the Health Connect sync (SPEC §4.9). */
object HealthRules {

    /** A Health Connect weight this close to a manual one is the same weigh-in; the scale's wins. */
    val WEIGHT_DEDUPE_WINDOW: Duration = Duration.ofMinutes(10)

    /** Nutrition records are intervals; ours last one minute from the meal's time. */
    val NUTRITION_RECORD_LENGTH: Duration = Duration.ofMinutes(1)

    private val names = listOf(
        MealKind.BREAKFAST to listOf("breakfast", "café da manhã", "cafe da manha", "desayuno"),
        MealKind.LUNCH to listOf("lunch", "almoço", "almoco", "almuerzo"),
        MealKind.DINNER to listOf("dinner", "supper", "jantar", "cena"),
        MealKind.SNACK to listOf("snack", "lanche", "merienda", "ceia"),
    )

    /**
     * The meal type for a user-named meal slot: by name first (meals can be renamed), else
     * by its time window, else unknown.
     */
    fun mealKind(slotName: String?, startMinute: Int?): MealKind {
        val name = slotName?.trim()?.lowercase().orEmpty()
        names.firstOrNull { (_, words) -> words.any { name.contains(it) } }?.let { return it.first }
        return when (startMinute) {
            null -> MealKind.UNKNOWN
            in 4 * 60 until 10 * 60 + 30 -> MealKind.BREAKFAST
            in 10 * 60 + 30 until 15 * 60 -> MealKind.LUNCH
            in 17 * 60 until 22 * 60 -> MealKind.DINNER
            else -> MealKind.SNACK
        }
    }

    /**
     * When a log entry happened, for its Nutrition record: the time it was logged if that was
     * on the entry's day, else the start of its meal's window on that day, else noon.
     */
    fun nutritionStart(date: LocalDate, loggedAt: Instant, zone: ZoneId, slotStartMinute: Int?): Instant {
        if (loggedAt.atZone(zone).toLocalDate() == date) return loggedAt
        val time = slotStartMinute?.let { LocalTime.of(it / 60 % 24, it % 60) } ?: LocalTime.NOON
        return date.atTime(time).atZone(zone).toInstant()
    }

    /**
     * When an entry was eaten, for its Nutrition and Hydration records: its meal time when it has
     * one (since 1.2), else [nutritionStart]'s rule.
     */
    fun entryStart(date: LocalDate, eatenMinute: Int?, loggedAt: Instant, zone: ZoneId, slotStartMinute: Int?): Instant =
        if (eatenMinute != null) date.atTime(LocalTime.of(eatenMinute / 60 % 24, eatenMinute % 60)).atZone(zone).toInstant()
        else nutritionStart(date, loggedAt, zone, slotStartMinute)

    /** The manual weigh-in a Health Connect reading at [time] replaces, if any (nearest wins). */
    fun <T> duplicateOf(time: Instant, manual: List<T>, timeOf: (T) -> Instant): T? =
        manual.filter { Duration.between(timeOf(it), time).abs() <= WEIGHT_DEDUPE_WINDOW }
            .minByOrNull { Duration.between(timeOf(it), time).abs() }
}
