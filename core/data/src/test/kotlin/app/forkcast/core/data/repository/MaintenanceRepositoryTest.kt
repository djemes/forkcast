package app.forkcast.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.ActivityDayEntity
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.GoalEntity
import app.forkcast.core.data.db.ProfileEntity
import app.forkcast.core.data.health.MutableClock
import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.DayCompleteness
import app.forkcast.core.model.ExerciseMode
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Sex
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class MaintenanceRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForkcastDatabase::class.java)
        .allowMainThreadQueries().build()
    private val zone = ZoneId.of("America/New_York")
    private val clock = MutableClock(Instant.parse("2026-11-20T16:00:00Z"), zone)
    private val today = LocalDate.of(2026, 11, 20)
    private val log = RoomLogRepository(db, db.logEntryDao(), db.foodDao(), clock)
    private val weights = RoomWeightRepository(db.weightDao(), clock)
    private val repo = RoomMaintenanceRepository(db.healthDao(), db.dayStatusDao(), log, weights, db.profileDao(), db.goalDao(), clock)

    @After fun tearDown() = db.close()

    private suspend fun seedProfile(mode: ExerciseMode = ExerciseMode.INCLUDED) {
        db.profileDao().upsert(ProfileEntity(sex = Sex.MALE, birthDate = LocalDate.of(1985, 1, 1), heightCm = 180.0,
            activityLevel = ActivityLevel.MODERATE, exerciseMode = mode, exerciseAddBackPct = 50, createdAt = 1, updatedAt = 1))
        db.goalDao().setActive(GoalEntity("g", GoalType.LOSE, today.minusDays(60), 90.0, 80.0, null, 0.5, true, 1, 1))
    }

    private suspend fun eatEveryDay(kcal: Double, days: Int = 28) {
        for (d in 1..days) {
            val date = today.minusDays(d.toLong())
            log.add(listOf("breakfast", "lunch").map { meal ->
                LogEntry("", date, meal, null, "Food", null, 1.0, "meal", null, mapOf(NutrientKey.ENERGY_KCAL to kcal / 2), FoodSource.QUICK_ADD, null, clock.instant())
            })
        }
    }

    private suspend fun weighDaily(kg: Double, days: Int = 28) {
        for (d in 1..days) weights.add(kg, null, today.minusDays(d.toLong()).atTime(7, 0).atZone(zone).toInstant())
    }

    @Test fun firstEstimateStartsFromTheFormulaThenWaitsAWeek() = runTest {
        seedProfile()
        eatEveryDay(2400.0)
        weighDaily(85.0)

        repo.updateIfDue()
        val first = repo.latest.first()!!
        // Stable weight: observed = 2,400. Formula: BMR 1,775 (85 kg, 180 cm, 41 y) × 1.55 = 2,751 → one step of −105.
        assertThat(first.kcal).isWithin(0.1).of(2751.25 - 0.3 * (2751.25 - 2400.0))
        assertThat(first.completeDays).isEqualTo(28)

        clock.advance(Duration.ofDays(3))
        repo.updateIfDue()
        assertThat(repo.latest.first()!!.date).isEqualTo(today)
        clock.advance(Duration.ofDays(4))
        repo.updateIfDue()
        assertThat(repo.latest.first()!!.date).isEqualTo(today.plusDays(7))
    }

    @Test fun notEnoughDataMeansNoEstimateButProgressCounts() = runTest {
        seedProfile()
        eatEveryDay(2400.0, days = 10)
        weighDaily(85.0, days = 3)
        repo.updateIfDue()
        assertThat(repo.latest.first()).isNull()
        assertThat(repo.observeProgress().first()).isEqualTo(MaintenanceProgress(10, 3))
    }

    @Test fun markingDaysChangesWhatCounts() = runTest {
        val d = today.minusDays(1)
        assertThat(repo.observeCompleteness(d).first()).isEqualTo(DayCompleteness.AUTO)
        repo.setCompleteness(d, DayCompleteness.INCOMPLETE)
        assertThat(repo.observeCompleteness(d).first()).isEqualTo(DayCompleteness.INCOMPLETE)
        repo.setCompleteness(d, DayCompleteness.COMPLETE)
        assertThat(repo.observeCompleteness(d).first()).isEqualTo(DayCompleteness.COMPLETE)
        repo.setCompleteness(d, DayCompleteness.AUTO)
        assertThat(repo.observeCompleteness(d).first()).isEqualTo(DayCompleteness.AUTO)
        // Marked again after Auto: the one row per date is reused.
        repo.setCompleteness(d, DayCompleteness.COMPLETE)
        assertThat(repo.observeCompleteness(d).first()).isEqualTo(DayCompleteness.COMPLETE)
    }

    @Test fun exerciseBonusAndAverageOnlyWhenAddedBack() = runTest {
        seedProfile(ExerciseMode.ADDED_BACK)
        db.healthDao().upsertActivity(listOf(
            ActivityDayEntity("a", today, 9000, 400.0, null, null, emptyList(), 1, 1),
            ActivityDayEntity("b", today.minusDays(1), 9000, 560.0, null, null, emptyList(), 1, 1),
        ))
        assertThat(repo.exerciseBonus(today)).isEqualTo(200.0)
        assertThat(repo.observeAverageBonus(today).first()).isWithin(0.01).of(280.0 / 28)

        db.profileDao().upsert(db.profileDao().get()!!.copy(exerciseMode = ExerciseMode.INCLUDED, updatedAt = 2))
        assertThat(repo.exerciseBonus(today)).isEqualTo(0.0)
    }
}
