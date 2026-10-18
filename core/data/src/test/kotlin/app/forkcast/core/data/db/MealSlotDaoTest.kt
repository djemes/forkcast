package app.forkcast.core.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealSlotDaoTest {

    private lateinit var db: ForkcastDatabase
    private lateinit var dao: MealSlotDao
    private val names = DefaultMealSlots.Names("Breakfast", "Lunch", "Dinner", "Snacks")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForkcastDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mealSlotDao()
    }

    @After fun tearDown() = db.close()

    @Test fun activeSlotsComeBackInOrder() = runTest {
        dao.upsert(DefaultMealSlots.entities(names, now = 1L).reversed())
        val slots = dao.observeActive().first()
        assertThat(slots.map { it.name }).containsExactly("Breakfast", "Lunch", "Dinner", "Snacks").inOrder()
    }

    @Test fun softDeletedSlotsAreHidden() = runTest {
        val all = DefaultMealSlots.entities(names, now = 1L)
        dao.upsert(all)
        dao.upsert(listOf(all[3].copy(deletedAt = 2L, updatedAt = 2L)))
        assertThat(dao.observeActive().first().map { it.name }).doesNotContain("Snacks")
    }

    @Test fun seedingInsertsFourDefaultSlots() = runTest {
        DefaultMealSlots.insertInto(db.openHelper.writableDatabase, names, now = 5L)
        val slots = dao.observeActive().first()
        assertThat(slots).hasSize(4)
        assertThat(slots.last().startMinute).isNull()
        assertThat(slots.map { it.id }.toSet()).hasSize(4)
    }
}
