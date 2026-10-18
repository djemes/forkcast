package app.forkcast.fakes

import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.Goal
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Sex
import app.forkcast.core.model.UserProfile
import java.time.Instant
import java.time.LocalDate

object TestData {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 18)
    val PROFILE = UserProfile(Sex.MALE, LocalDate.of(1986, 10, 18), 180.0, ActivityLevel.MODERATE)
    val GOAL = Goal("g", GoalType.LOSE, TODAY, 90.0, 80.0, null, 0.5)

    fun entry(name: String, meal: String, kcal: Double, protein: Double = 0.0, date: LocalDate = TODAY, at: Long = 0) = LogEntry(
        "", date, meal, null, name, null, 1.0, "serving", 100.0,
        mapOf(NutrientKey.ENERGY_KCAL to kcal, NutrientKey.PROTEIN_G to protein), FoodSource.USDA, null, Instant.ofEpochMilli(at),
    )
}
