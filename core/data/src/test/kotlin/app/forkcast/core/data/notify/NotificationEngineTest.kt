package app.forkcast.core.data.notify

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.MealSlotEntity
import app.forkcast.core.data.repository.DataStoreSettingsRepository
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.RoomLogRepository
import app.forkcast.core.data.repository.RoomMealSlotRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.core.data.settings.AppSettingsSerializer
import app.forkcast.core.domain.BudgetPlan
import app.forkcast.core.domain.DailyTargets
import app.forkcast.core.domain.NutrientTarget
import app.forkcast.core.domain.TargetKind
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.Goal
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.UserProfile
import app.forkcast.core.model.WeightEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class NotificationEngineTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java).allowMainThreadQueries().build()
    private val ny: ZoneId = ZoneId.of("America/New_York")
    private var now = ZonedDateTime.of(2026, 10, 18, 13, 40, 0, 0, ny)
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ny
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now.toInstant()
    }
    private val day = LocalDate.of(2026, 10, 18)
    private val settingsFile = File(context.filesDir, "notif-settings-${System.nanoTime()}.json")
    private val settings = DataStoreSettingsRepository(DataStoreFactory.create(AppSettingsSerializer) { settingsFile })
    private val log = RoomLogRepository(db, db.logEntryDao(), db.foodDao(), clock)
    private val weights = MutableStateFlow<List<WeightEntry>>(emptyList())

    private val targets = DailyTargets(
        ageYears = 40, currentKg = 90.0, bmrKcal = 1800.0, tdeeKcal = 2600.0,
        plan = BudgetPlan(2600.0, 600.0, 2000, null, emptyList()), budgetKcal = 2000, isCustomBudget = false,
        targets = mapOf(NutrientKey.PROTEIN_G to NutrientTarget(NutrientKey.PROTEIN_G, 140.0, TargetKind.GOAL)),
        guardrails = emptyList(),
    )
    private val profiles = object : ProfileRepository {
        override val profile: Flow<UserProfile?> = flowOf(null)
        override val activeGoal: Flow<Goal?> = flowOf(null)
        override val overrides: Flow<Map<NutrientKey, Double>> = flowOf(emptyMap())
        override suspend fun saveProfile(profile: UserProfile) = Unit
        override suspend fun saveGoal(goal: Goal) = Unit
        override suspend fun setOverride(key: NutrientKey, amount: Double?) = Unit
        override fun observeDailyTargets(): Flow<DailyTargets?> = flowOf(targets)
    }
    private val weightRepo = object : WeightRepository {
        override val entries: Flow<List<WeightEntry>> = weights
        override val latestKg: Flow<Double?> = flowOf(null)
        override suspend fun add(kg: Double, bodyFatPct: Double?, at: Instant) = "w"
        override suspend fun update(entry: WeightEntry) = Unit
        override suspend fun delete(id: String) = Unit
    }
    private val engine = NotificationEngine(context, Notifier(context), settings, profiles, log, weightRepo, RoomMealSlotRepository(db.mealSlotDao()), db.aiDao(), clock)
    private val nm = shadowOf(context.getSystemService(NotificationManager::class.java))

    init {
        Channel.createAll(context)
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun tearDown() {
        db.close()
        settingsFile.delete()
    }

    private suspend fun eat(kcal: Double, meal: String = "lunch", protein: Double = 0.0) = log.add(
        listOf(LogEntry("", day, meal, null, "Food", null, 1.0, "meal", null, mapOf(NutrientKey.ENERGY_KCAL to kcal, NutrientKey.PROTEIN_G to protein), FoodSource.QUICK_ADD, null, clock.instant())),
    )

    private fun texts() = nm.allNotifications.map { it.extras.getCharSequence("android.text").toString() }

    @Test fun thresholdPostsOnceWithFactualCopy() = runTest {
        eat(1700.0)
        engine.checkBudget()
        assertThat(texts()).containsExactly("1,700 of 2,000 kcal so far. About 300 left for today.")
        // Down and back up: no repeat.
        engine.checkBudget()
        assertThat(nm.allNotifications).hasSize(1)
        eat(500.0)
        engine.checkBudget()
        assertThat(texts().last()).isEqualTo("You're 200 kcal over today's budget.")
    }

    @Test fun quietHoursSuppressButStillRecord() = runTest {
        now = ZonedDateTime.of(2026, 10, 18, 23, 0, 0, 0, ny)
        eat(1700.0)
        engine.checkBudget()
        assertThat(nm.allNotifications).isEmpty()
        now = ZonedDateTime.of(2026, 10, 18, 23, 30, 0, 0, ny)
        engine.checkBudget()
        assertThat(nm.allNotifications).isEmpty()
    }

    @Test fun emptyLunchGetsAReminderFilledDinnerDoesNot() = runTest {
        db.mealSlotDao().upsert(listOf(
            MealSlotEntity("lunch", "Lunch", 1, 660, 900, 1, 1, null),
            MealSlotEntity("dinner", "Dinner", 2, 1020, 1320, 1, 1, null),
        ))
        now = ZonedDateTime.of(2026, 10, 18, 13, 31, 0, 0, ny)
        engine.runReminders()
        assertThat(texts()).containsExactly("Nothing logged for Lunch yet.")
        engine.runReminders()
        assertThat(nm.allNotifications).hasSize(1)

        eat(600.0, meal = "dinner")
        now = ZonedDateTime.of(2026, 10, 18, 20, 1, 0, 0, ny)
        engine.runReminders()
        assertThat(nm.allNotifications).hasSize(1)
    }

    @Test fun proteinCheckAndWeighIn() = runTest {
        settings.updateNotifications { it.copy(proteinEnabled = true, proteinMinute = 17 * 60, mealRemindersEnabled = false) }
        eat(1200.0, protein = 60.0)
        now = ZonedDateTime.of(2026, 10, 18, 17, 5, 0, 0, ny)
        engine.runReminders()
        assertThat(texts()).containsExactly("80 g protein to go today.")

        now = ZonedDateTime.of(2026, 10, 19, 7, 31, 0, 0, ny)
        engine.runReminders()
        assertThat(texts().last()).isEqualTo("No weigh-in yet today.")
    }

    @Test fun staleRemindersAreSkipped() = runTest {
        // Weigh-in at 7:30; a run at 10:00 is too late to be useful.
        settings.updateNotifications { it.copy(mealRemindersEnabled = false) }
        now = ZonedDateTime.of(2026, 10, 18, 10, 0, 0, 0, ny)
        engine.runReminders()
        assertThat(nm.allNotifications).isEmpty()
    }
}
