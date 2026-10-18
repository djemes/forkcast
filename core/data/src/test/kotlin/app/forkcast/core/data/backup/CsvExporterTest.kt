package app.forkcast.core.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.LogEntryEntity
import app.forkcast.core.data.db.MealSlotEntity
import app.forkcast.core.data.db.WeightEntryEntity
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.WeightSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class CsvExporterTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForkcastDatabase::class.java)
        .allowMainThreadQueries().build()
    private val zone = ZoneId.of("America/New_York")
    private val clock = Clock.fixed(Instant.parse("2026-10-18T16:00:00Z"), zone)
    private val csv = CsvExporter(db, clock)
    private val dao = db.backupDao()

    @After fun tearDown() = db.close()

    @Test fun foodLogRowsWithMealNamesAndQuoting() = runTest {
        dao.upsertMealSlots(listOf(MealSlotEntity("lunch", "Lunch", 1, null, null, 1, 1, null)))
        val loggedAt = Instant.parse("2026-10-18T16:30:00Z").toEpochMilli()
        dao.upsertLogEntries(listOf(
            LogEntryEntity("e1", LocalDate.of(2026, 10, 18), zone.id, "lunch", null, "Rice, \"white\"", null, 1.5,
                "1 cup", 237.0, mapOf(NutrientKey.ENERGY_KCAL to 307.5, NutrientKey.PROTEIN_G to 6.333), FoodSource.USDA,
                null, null, null, loggedAt, 1, 1),
            LogEntryEntity("gone", LocalDate.of(2026, 10, 18), zone.id, "lunch", null, "Deleted", null, 1.0,
                "1", null, emptyMap(), FoodSource.QUICK_ADD, null, null, null, loggedAt, 1, 1, deletedAt = 2),
        ))

        val lines = csv.foodLog().split("\r\n").filter { it.isNotEmpty() }

        assertThat(lines).hasSize(2)
        assertThat(lines[0]).startsWith("date,time,meal,food,brand,quantity,serving,grams,source,energy_kcal,protein_g")
        assertThat(lines[1]).startsWith("2026-10-18,12:30,Lunch,\"Rice, \"\"white\"\"\",,1.5,1 cup,237,USDA,307.5,6.33")
    }

    @Test fun weightsInKgAndLb() = runTest {
        val at = Instant.parse("2026-10-18T11:00:00Z").toEpochMilli()
        dao.upsertWeights(listOf(WeightEntryEntity("w1", at, 80.0, 22.5, WeightSource.MANUAL, null, 1, 1)))
        val lines = csv.weights().split("\r\n").filter { it.isNotEmpty() }
        assertThat(lines).containsExactly(
            "date,time,weight_kg,weight_lb,body_fat_pct,source",
            "2026-10-18,07:00,80,176.37,22.5,MANUAL",
        ).inOrder()
    }

    @Test fun formulaLikeTextIsDefused() {
        assertThat(escapeCsv("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)")
        assertThat(escapeCsv("-5")).isEqualTo("-5")
        assertThat(escapeCsv("a\nb")).isEqualTo("\"a\nb\"")
    }
}
