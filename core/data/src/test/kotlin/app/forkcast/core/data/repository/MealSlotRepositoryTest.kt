package app.forkcast.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.MealSlotEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class MealSlotRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java)
        .allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-19T14:00:00Z"), ZoneId.of("America/New_York"))
    private val repo = RoomMealSlotRepository(db, db.mealSlotDao(), clock, context)

    @After fun tearDown() = db.close()

    @Test fun addEditReorderDeleteRestore() = runTest {
        db.mealSlotDao().upsert(listOf(
            MealSlotEntity("b", "Breakfast", 0, 240, 660, 1, 1, null),
            MealSlotEntity("l", "Lunch", 1, 660, 900, 1, 1, null),
        ))
        val id = repo.add(" Late snack ", 1260, 60)
        var slots = repo.observeMealSlots().first()
        assertThat(slots.map { it.name }).containsExactly("Breakfast", "Lunch", "Late snack").inOrder()

        repo.update(slots.first { it.id == "l" }.copy(name = "Midday", startMinute = null, endMinute = null))
        repo.reorder(listOf(id, "b", "l"))
        slots = repo.observeMealSlots().first()
        assertThat(slots.map { it.name }).containsExactly("Late snack", "Breakfast", "Midday").inOrder()
        assertThat(slots.last().startMinute).isNull()

        repo.delete("b")
        assertThat(repo.observeMealSlots().first().map { it.id }).doesNotContain("b")
        // Soft delete: the row (and its entries' meal id) survives for Undo and exports.
        assertThat(db.mealSlotDao().get("b")!!.deletedAt).isNotNull()
        repo.restore("b")
        assertThat(repo.observeMealSlots().first().map { it.id }).containsExactly(id, "b", "l").inOrder()
    }
}
