package app.forkcast.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.FoodEntity
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.ServingSize
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class LogRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForkcastDatabase::class.java)
        .allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-18T16:00:00Z"), ZoneId.of("America/New_York"))
    private val repo = RoomLogRepository(db, db.logEntryDao(), db.foodDao(), clock)
    private val day = LocalDate.of(2026, 10, 18)

    @After fun tearDown() = db.close()

    private fun entry(name: String, kcal: Double, foodId: String? = null) = LogEntry(
        "", day, "lunch", foodId, name, null, 1.0, "1 cup", 158.0,
        mapOf(NutrientKey.ENERGY_KCAL to kcal), FoodSource.USDA, null, Instant.EPOCH,
    )

    @Test fun addDeleteRestoreCopy() = runTest {
        val ids = repo.add(listOf(entry("Rice", 205.0), entry("Beans", 120.0)))
        assertThat(repo.observeDay(day).first().map { it.displayName }).containsExactly("Rice", "Beans").inOrder()

        repo.delete(listOf(ids[0]))
        assertThat(repo.observeDay(day).first().map { it.displayName }).containsExactly("Beans")
        repo.restore(listOf(ids[0]))
        assertThat(repo.observeDay(day).first()).hasSize(2)

        repo.copy(ids, day.plusDays(1), "dinner")
        val copied = repo.observeDay(day.plusDays(1)).first()
        assertThat(copied.map { it.mealSlotId }.toSet()).containsExactly("dinner")
        assertThat(copied.map { it.nutrients[NutrientKey.ENERGY_KCAL] }).containsExactly(205.0, 120.0).inOrder()
    }

    @Test fun snapshotDoesNotFollowFoodEdits() = runTest {
        val dao = db.foodDao()
        val food = FoodEntity(
            "f1", "Rice", null, FoodSource.USDA, "123", null, listOf(ServingSize("1 cup", 158.0)),
            mapOf(NutrientKey.ENERGY_KCAL to 130.0), false, null, null, null, 1, 1,
        )
        dao.upsert(food)
        repo.add(listOf(entry("Rice", 205.0, foodId = "f1")))
        // Logging marks the food as recently used with the last quantity.
        assertThat(dao.get("f1")!!.lastUsedAt).isNotNull()
        assertThat(dao.get("f1")!!.lastServingLabel).isEqualTo("1 cup")

        dao.upsert(dao.get("f1")!!.copy(nutrientsPer100g = mapOf(NutrientKey.ENERGY_KCAL to 999.0)))
        assertThat(repo.observeDay(day).first().single().nutrients[NutrientKey.ENERGY_KCAL]).isEqualTo(205.0)
    }
}
