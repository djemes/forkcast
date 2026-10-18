package app.forkcast.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.NutrientKey
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
class SavedMealRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForkcastDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = RoomSavedMealRepository(db.savedMealDao(), Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneId.of("UTC")))
    private val day = LocalDate.of(2026, 10, 18)

    @After fun tearDown() = db.close()

    private fun entry(name: String, kcal: Double, at: Long) = LogEntry(
        "x", day, "b", "f-$name", name, null, 1.0, "1 cup", 245.0,
        mapOf(NutrientKey.ENERGY_KCAL to kcal), FoodSource.USDA, null, Instant.ofEpochMilli(at),
    )

    @Test fun saveRenameLogDelete() = runTest {
        val id = repo.saveFrom(" Usual breakfast ", listOf(entry("Banana", 105.0, 2), entry("Yogurt", 146.0, 1)))
        val meal = repo.meals.first().single()
        assertThat(meal.name).isEqualTo("Usual breakfast")
        assertThat(meal.items.map { it.displayName }).containsExactly("Yogurt", "Banana").inOrder()

        repo.rename(id, "Weekday breakfast")
        assertThat(repo.meals.first().single().name).isEqualTo("Weekday breakfast")

        val entries = repo.toEntries(id, day.plusDays(1), "l")
        assertThat(entries.map { it.date }.toSet()).containsExactly(day.plusDays(1))
        assertThat(entries.map { it.mealSlotId }.toSet()).containsExactly("l")
        assertThat(entries.sumOf { it.nutrients[NutrientKey.ENERGY_KCAL]!! }).isEqualTo(251.0)

        repo.delete(id)
        assertThat(repo.meals.first()).isEmpty()
    }
}
