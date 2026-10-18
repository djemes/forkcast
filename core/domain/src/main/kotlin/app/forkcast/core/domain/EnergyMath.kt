package app.forkcast.core.domain

import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.Sex
import java.time.LocalDate
import java.time.Period
import kotlin.math.roundToInt

/** BMR and TDEE (SPEC §5.1–5.2). */
object EnergyMath {

    /** Mifflin-St Jeor resting energy, kcal/day. */
    fun bmrMifflinStJeor(sex: Sex, weightKg: Double, heightCm: Double, ageYears: Int): Double {
        val base = 10 * weightKg + 6.25 * heightCm - 5 * ageYears
        return when (sex) {
            Sex.MALE -> base + 5
            Sex.FEMALE -> base - 161
        }
    }

    /** Completed years between [birthDate] and [today]. */
    fun ageYears(birthDate: LocalDate, today: LocalDate): Int = Period.between(birthDate, today).years

    fun tdee(bmr: Double, activity: ActivityLevel): Double = bmr * activity.factor

    /** Rounds kcal to the nearest [NutritionConstants.BUDGET_ROUNDING_KCAL] (half up). */
    fun roundBudget(kcal: Double): Int {
        val step = NutritionConstants.BUDGET_ROUNDING_KCAL
        return (kcal / step).roundToInt() * step
    }
}
