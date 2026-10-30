package app.forkcast.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * One logged item. [nutrients] is a snapshot for the logged amount: editing the food later
 * never changes past days (CLAUDE.md non-negotiable).
 */
data class LogEntry(
    val id: String,
    val date: LocalDate,
    val mealSlotId: String,
    val foodId: String?,
    val displayName: String,
    val brand: String?,
    val quantity: Double,
    val servingLabel: String,
    val grams: Double?,
    val nutrients: Nutrients,
    val source: FoodSource,
    val note: String?,
    val loggedAt: Instant,
    /** The photo estimate this entry came from; opens the review screen. */
    val aiEstimateId: String? = null,
    /** Water this drink adds to the day (ml), snapshotted when logged; null = not counted. */
    val waterMl: Double? = null,
    /**
     * When it was eaten, minutes after local midnight on [date]; null = not set yet (the log
     * repository fills it in: the logging time today, else the meal's typical time).
     */
    val eatenMinute: Int? = null,
    /** The portion slider's value (roadmap 3.9): a multiplier of the 1× amount, already included in [quantity]. */
    val portion: Double = 1.0,
)

data class WeightEntry(
    val id: String,
    val timestamp: Instant,
    val kg: Double,
    val bodyFatPct: Double?,
    val source: WeightSource,
)

enum class WeightSource { MANUAL, HEALTH_CONNECT }

data class WaterEntry(val id: String, val timestamp: Instant, val date: LocalDate, val ml: Double)

data class SavedMeal(val id: String, val name: String, val items: List<SavedMealItem>)

data class SavedMealItem(
    val id: String,
    val foodId: String?,
    val displayName: String,
    val brand: String?,
    val quantity: Double,
    val servingLabel: String,
    val grams: Double?,
    val nutrients: Nutrients,
    val source: FoodSource,
    val waterMl: Double? = null,
)

data class UserProfile(
    val sex: Sex,
    val birthDate: LocalDate,
    val heightCm: Double,
    val activityLevel: ActivityLevel,
    val exerciseMode: ExerciseMode = ExerciseMode.INCLUDED,
    val exerciseAddBackPct: Int = 50,
)

data class Goal(
    val id: String,
    val goalType: GoalType,
    val startDate: LocalDate,
    val startWeightKg: Double,
    val targetWeightKg: Double,
    /** Exactly one of [targetDate] / [weeklyRateKg] is what the user chose; the other is derived. */
    val targetDate: LocalDate?,
    val weeklyRateKg: Double?,
)
