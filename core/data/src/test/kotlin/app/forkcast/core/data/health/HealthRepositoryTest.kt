package app.forkcast.core.data.health

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.backup.MemoryDataStore
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.MealSlotEntity
import app.forkcast.core.data.repository.RoomLogRepository
import app.forkcast.core.data.repository.RoomWaterRepository
import app.forkcast.core.data.repository.RoomWeightRepository
import app.forkcast.core.domain.MealKind
import app.forkcast.core.model.ActivityDay
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.HealthSettings
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.WeightSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class HealthRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java).allowMainThreadQueries().build()
    private val zone = ZoneId.of("America/New_York")
    private val clock = MutableClock(Instant.parse("2026-10-18T16:00:00Z"), zone)
    private val today = LocalDate.of(2026, 10, 18)
    private val source = FakeHealthConnectSource()
    private val settings = MemoryDataStore(AppSettings(health = HealthSettings(connected = true)))
    private val state = MemoryDataStore(HealthSyncState())
    private val repo = HealthConnectRepository(context, source, db, db.healthDao(), db.mealSlotDao(), settings, state, clock)
    private val log = RoomLogRepository(db, db.logEntryDao(), db.foodDao(), clock)
    private val water = RoomWaterRepository(db.waterDao(), clock)
    private val weights = RoomWeightRepository(db.weightDao(), clock)

    @After fun tearDown() = db.close()

    private suspend fun seedSlots() = db.mealSlotDao().upsert(
        listOf(
            MealSlotEntity("breakfast", "Breakfast", 0, 4 * 60, 11 * 60, 1, 1, null),
            MealSlotEntity("brunchy", "Second meal", 1, 11 * 60, 15 * 60, 1, 1, null),
        ),
    )

    private fun entry(name: String, slot: String, kcal: Double, date: LocalDate = today) = LogEntry(
        "", date, slot, null, name, null, 1.0, "1 serving", null,
        mapOf(NutrientKey.ENERGY_KCAL to kcal, NutrientKey.SODIUM_MG to 500.0), FoodSource.QUICK_ADD, null, clock.instant(),
    )

    @Test fun writesEntriesWithMealTypeThenRewritesEditsAndDeletesRemoved() = runTest {
        seedSlots()
        val (toast, rice) = log.add(listOf(entry("Toast", "breakfast", 80.0), entry("Rice", "brunchy", 205.0)))

        repo.sync(SyncReason.WRITES)

        assertThat(source.nutrition.keys).containsExactly(toast, rice)
        assertThat(source.nutrition.getValue(toast).meal).isEqualTo(MealKind.BREAKFAST)
        // Renamed meal: falls back to its time window.
        assertThat(source.nutrition.getValue(rice).meal).isEqualTo(MealKind.LUNCH)
        assertThat(source.nutrition.getValue(rice).nutrients[NutrientKey.SODIUM_MG]).isEqualTo(500.0)
        assertThat(db.logEntryDao().get(toast)!!.healthConnectRecordId).isEqualTo("hc-$toast")
        assertThat(db.healthDao().pendingLogEntries(10)).isEmpty()

        clock.advance(Duration.ofMinutes(1))
        log.update(log.get(rice)!!.copy(nutrients = mapOf(NutrientKey.ENERGY_KCAL to 300.0)))
        log.delete(listOf(toast))
        repo.sync(SyncReason.WRITES)

        assertThat(source.nutrition.keys).containsExactly(rice)
        assertThat(source.nutrition.getValue(rice).nutrients[NutrientKey.ENERGY_KCAL]).isEqualTo(300.0)
        assertThat(db.healthDao().pendingLogEntries(10)).isEmpty()
    }

    @Test fun entryDeletedBeforeItWasWrittenNeedsNoDeleteCall() = runTest {
        val (id) = log.add(listOf(entry("Gum", "breakfast", 5.0)))
        log.delete(listOf(id))
        repo.sync(SyncReason.WRITES)
        assertThat(source.deleteCalls).isEmpty()
        assertThat(db.healthDao().pendingLogEntries(10)).isEmpty()
    }

    @Test fun pastDayEntryIsDatedOnItsDayAtTheMealStart() = runTest {
        seedSlots()
        val (id) = log.add(listOf(entry("Eggs", "breakfast", 150.0, date = today.minusDays(1))))
        repo.sync(SyncReason.WRITES)
        val write = source.nutrition.getValue(id)
        assertThat(write.start).isEqualTo(today.minusDays(1).atTime(4, 0).atZone(zone).toInstant())
        assertThat(write.offset).isEqualTo(ZoneOffset.ofHours(-4))
    }

    @Test fun waterBecomesHydrationAndDeletionRemovesIt() = runTest {
        val id = water.add(today, 250.0)
        repo.sync(SyncReason.WRITES)
        assertThat(source.hydration.getValue(id).ml).isEqualTo(250.0)
        clock.advance(Duration.ofMinutes(1))
        water.delete(id)
        repo.sync(SyncReason.WRITES)
        assertThat(source.hydration).isEmpty()
    }

    /** Health Connect unreachable while deleting: the row stays pending, and the next sync removes the record. */
    @Test fun aPassingDeleteFailureIsRetried() = runTest {
        val id = water.add(today, 250.0)
        repo.sync(SyncReason.WRITES)
        clock.advance(Duration.ofMinutes(1))
        water.delete(id)
        source.deleteFailure = java.io.IOException("Health Connect unavailable")
        repo.sync(SyncReason.WRITES)
        assertThat(source.hydration).containsKey(id)
        source.deleteFailure = null
        repo.sync(SyncReason.WRITES)
        assertThat(source.hydration).isEmpty()
    }

    /** A tea that counts toward water is Nutrition and Hydration; un-counting it removes the Hydration. */
    @Test fun countedDrinksAreAlsoHydration() = runTest {
        seedSlots()
        val (tea) = log.add(listOf(entry("Tea", "breakfast", 2.0).copy(grams = 237.0, waterMl = 237.0)))
        repo.sync(SyncReason.WRITES)
        assertThat(source.nutrition.keys).containsExactly(tea)
        assertThat(source.hydration.getValue("drink-$tea").ml).isEqualTo(237.0)

        clock.advance(Duration.ofMinutes(1))
        log.update(log.get(tea)!!.copy(waterMl = null))
        repo.sync(SyncReason.WRITES)
        assertThat(source.hydration).isEmpty()

        clock.advance(Duration.ofMinutes(1))
        log.update(log.get(tea)!!.copy(waterMl = 237.0))
        repo.sync(SyncReason.WRITES)
        clock.advance(Duration.ofMinutes(1))
        log.delete(listOf(tea))
        repo.sync(SyncReason.WRITES)
        assertThat(source.hydration).isEmpty()
        assertThat(source.nutrition).isEmpty()
    }

    @Test fun nothingHappensWhenNotConnectedOrWithoutPermission() = runTest {
        log.add(listOf(entry("Toast", "breakfast", 80.0)))
        settings.updateData { it.copy(health = HealthSettings(connected = false)) }
        repo.sync(SyncReason.MANUAL)
        assertThat(source.nutrition).isEmpty()

        settings.updateData { it.copy(health = HealthSettings(connected = true)) }
        source.granted = HealthPermissions.ALL - HealthPermissions.WRITE_NUTRITION
        repo.sync(SyncReason.MANUAL)
        assertThat(source.nutrition).isEmpty()
        assertThat(db.healthDao().pendingLogEntries(10)).hasSize(1)
    }

    @Test fun scaleWeightReplacesManualWithinTenMinutesAndTakesBodyFat() = runTest {
        val weighIn = clock.instant().minus(Duration.ofHours(8))
        weights.add(81.0, null, weighIn.plus(Duration.ofMinutes(4)))
        val farAway = weights.add(80.0, null, weighIn.minus(Duration.ofDays(2)))
        source.weights += HcWeight("w1", weighIn, 80.6, "com.sec.android.app.shealth")
        source.bodyFat += HcBodyFat("f1", weighIn.plusSeconds(20), 21.5, "com.sec.android.app.shealth")
        source.weights += HcWeight("mine", weighIn.minus(Duration.ofDays(1)), 70.0, context.packageName)

        repo.sync(SyncReason.MANUAL)

        val all = weights.entries.first()
        assertThat(all.map { it.kg }).containsExactly(80.0, 80.6).inOrder()
        val scale = all.single { it.source == WeightSource.HEALTH_CONNECT }
        assertThat(scale.bodyFatPct).isEqualTo(21.5)
        assertThat(all.map { it.id }).contains(farAway)
        assertThat(state.data.first().bodyToken).isEqualTo("token-1")
    }

    @Test fun changesTokenAddsNewReadingsAndFollowsDeletions() = runTest {
        source.weights += HcWeight("w1", clock.instant().minus(Duration.ofDays(1)), 80.6, "scale")
        repo.sync(SyncReason.MANUAL)
        assertThat(source.fullReads).isEqualTo(1)

        source.pendingWeightChanges += HcWeight("w2", clock.instant(), 80.2, "scale")
        source.pendingDeletions += "w1"
        clock.advance(Duration.ofHours(1))
        repo.sync(SyncReason.MANUAL)

        assertThat(source.fullReads).isEqualTo(1)
        assertThat(weights.entries.first().map { it.kg }).containsExactly(80.2)
        assertThat(state.data.first().bodyToken).isEqualTo("token-1+")
    }

    @Test fun expiredTokenReadsEverythingAgain() = runTest {
        repo.sync(SyncReason.MANUAL)
        source.tokenExpired = true
        source.weights += HcWeight("w9", clock.instant(), 79.0, "scale")
        clock.advance(Duration.ofHours(1))
        repo.sync(SyncReason.MANUAL)
        assertThat(source.fullReads).isEqualTo(2)
        assertThat(weights.entries.first().map { it.kg }).containsExactly(79.0)
    }

    @Test fun withoutHistoryOnlyTheLast29DaysAreRead() = runTest {
        source.granted = HealthPermissions.ALL - HealthPermissions.READ_HISTORY
        source.weights += HcWeight("old", clock.instant().minus(Duration.ofDays(200)), 90.0, "scale")
        source.weights += HcWeight("new", clock.instant().minus(Duration.ofDays(3)), 85.0, "scale")
        repo.sync(SyncReason.MANUAL)
        assertThat(weights.entries.first().map { it.kg }).containsExactly(85.0)
        assertThat(source.activityRequests.single()).isEqualTo(today.minusDays(28) to today)

        // History granted later: the next sync reads everything once more.
        source.granted = HealthPermissions.ALL
        clock.advance(Duration.ofHours(1))
        repo.sync(SyncReason.MANUAL)
        assertThat(weights.entries.first().map { it.kg }).containsExactly(90.0, 85.0).inOrder()
        assertThat(source.activityRequests.last()).isEqualTo(today.minusDays(89) to today)
    }

    @Test fun activityBackfillsThenRefreshesTheLastWeekAndDropsRemovedDays() = runTest {
        source.activityDays[today] = ActivityDay(today, 8200, 410.0, 2650.0, 35, emptyList())
        source.activityDays[today.minusDays(2)] = ActivityDay(today.minusDays(2), 5000, null, null, null, emptyList())
        repo.sync(SyncReason.MANUAL)
        assertThat(source.activityRequests.single()).isEqualTo(today.minusDays(89) to today)
        assertThat(repo.observeActivity(today).first()!!.steps).isEqualTo(8200)

        source.activityDays.remove(today.minusDays(2))
        source.activityDays[today] = ActivityDay(today, 9100, 450.0, 2700.0, 35, emptyList())
        clock.advance(Duration.ofHours(1))
        repo.sync(SyncReason.MANUAL)
        assertThat(source.activityRequests.last()).isEqualTo(today.minusDays(6) to today)
        assertThat(repo.observeActivity(today).first()!!.steps).isEqualTo(9100)
        assertThat(repo.observeActivity(today.minusDays(2)).first()).isNull()

        // The day comes back: same row, no unique-index clash.
        source.activityDays[today.minusDays(2)] = ActivityDay(today.minusDays(2), 5100, null, null, null, emptyList())
        clock.advance(Duration.ofHours(1))
        repo.sync(SyncReason.MANUAL)
        assertThat(repo.observeActivity(today.minusDays(2)).first()!!.steps).isEqualTo(5100)
    }

    @Test fun backgroundSyncWithoutBackgroundPermissionOnlyWrites() = runTest {
        source.granted = HealthPermissions.ALL - HealthPermissions.READ_IN_BACKGROUND
        source.weights += HcWeight("w1", clock.instant(), 80.0, "scale")
        val (id) = log.add(listOf(entry("Toast", "breakfast", 80.0)))
        repo.sync(SyncReason.BACKGROUND)
        assertThat(source.nutrition.keys).containsExactly(id)
        assertThat(weights.entries.first()).isEmpty()
    }

    @Test fun openSyncIsThrottledButWritesStillGoOut() = runTest {
        repo.sync(SyncReason.OPEN)
        assertThat(source.fullReads).isEqualTo(1)
        source.tokenExpired = true
        val (id) = log.add(listOf(entry("Toast", "breakfast", 80.0)))
        clock.advance(Duration.ofMinutes(2))
        repo.sync(SyncReason.OPEN)
        assertThat(source.fullReads).isEqualTo(1)
        assertThat(source.nutrition.keys).containsExactly(id)
    }

    @Test fun permissionsResultConnectsAndDisconnectResetsWriteState() = runTest {
        settings.updateData { it.copy(health = HealthSettings(connected = false)) }
        val (id) = log.add(listOf(entry("Toast", "breakfast", 80.0)))
        repo.onPermissionsResult(setOf(HealthPermissions.WRITE_NUTRITION))
        assertThat(settings.data.first().health.connected).isTrue()
        assertThat(source.nutrition.keys).containsExactly(id)

        repo.disconnect()
        assertThat(source.revoked).isTrue()
        assertThat(settings.data.first().health.connected).isFalse()
        assertThat(db.healthDao().pendingLogEntries(10).map { it.id }).containsExactly(id)
        assertThat(state.data.first()).isEqualTo(HealthSyncState())
    }

    @Test fun deniedEverythingStaysDisconnected() = runTest {
        settings.updateData { it.copy(health = HealthSettings(connected = false)) }
        repo.onPermissionsResult(emptySet())
        assertThat(settings.data.first().health.connected).isFalse()
    }

    @Test fun requestsOnlyWhatTheChoicesNeed() = runTest {
        settings.updateData { it.copy(health = HealthSettings(readWeight = false, readActivity = false)) }
        assertThat(repo.permissionsToRequest()).containsExactly(HealthPermissions.WRITE_NUTRITION, HealthPermissions.WRITE_HYDRATION)
        settings.updateData { it.copy(health = HealthSettings()) }
        source.background = false
        assertThat(repo.permissionsToRequest()).doesNotContain(HealthPermissions.READ_IN_BACKGROUND)
        assertThat(repo.permissionsToRequest()).contains(HealthPermissions.READ_HISTORY)
    }
}

class MutableClock(private var now: Instant, private val zone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}
