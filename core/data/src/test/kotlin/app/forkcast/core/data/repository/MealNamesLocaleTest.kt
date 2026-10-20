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
import org.robolectric.annotation.Config
import java.time.Clock

/** Meals seeded in English show in Portuguese when the app runs in pt-BR; typed names stay. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "pt-rBR")
class MealNamesLocaleTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java).allowMainThreadQueries().build()
    private val repo = RoomMealSlotRepository(db, db.mealSlotDao(), Clock.systemUTC(), context)

    @After fun tearDown() = db.close()

    @Test fun defaultNamesFollowTheAppLanguage() = runTest {
        db.mealSlotDao().upsert(listOf(
            MealSlotEntity("b", "Breakfast", 0, 240, 660, 1, 1, null),
            MealSlotEntity("l", "Lunch", 1, 660, 900, 1, 1, null),
            MealSlotEntity("d", "Dinner", 2, 1020, 1320, 1, 1, null),
            MealSlotEntity("s", "Snacks", 3, null, null, 1, 1, null),
            MealSlotEntity("p", "Pre-workout", 4, null, null, 1, 1, null),
        ))
        assertThat(repo.observeMealSlots().first().map { it.name })
            .containsExactly("Café da manhã", "Almoço", "Jantar", "Lanches", "Pre-workout").inOrder()
        // Stored as they were: exports and backups keep the original names.
        assertThat(db.mealSlotDao().all().map { it.name }).contains("Lunch")
    }
}
