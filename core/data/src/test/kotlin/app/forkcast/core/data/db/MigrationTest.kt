package app.forkcast.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests. Each new schema version adds a test that creates the previous release's
 * version from its exported JSON (core/data/schemas), migrates, and validates. Migrations from
 * before v7 (builds older than 1.2) were removed in 1.3 (owner, 2026-10-24).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbFile = context.getDatabasePath(TEST_DB)

    // File + driver variant: the name-based helper mis-compares Windows paths.
    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = dbFile,
        driver = AndroidSQLiteDriver(),
        databaseClass = ForkcastDatabase::class,
    )

    /** A database written by 1.2.x (v7) opens with today's code as it is, data and all. */
    @Test fun version7FileFrom12OpensWithCurrentCode() {
        helper.createDatabase(7).apply {
            execSQL(
                "INSERT INTO meal_slot (id, name, sortOrder, startMinute, endMinute, createdAt, updatedAt, deletedAt) " +
                    "VALUES ('a', 'Lunch', 1, 660, 900, 1, 1, NULL)",
            )
            execSQL(
                "INSERT INTO log_entry (id, date, zoneId, mealSlotId, foodId, displayName, brand, quantity, servingLabel, grams, " +
                    "nutrients, source, aiEstimateId, healthConnectRecordId, note, loggedAt, createdAt, updatedAt, deletedAt, hcSyncedAt, " +
                    "waterMl, hcWaterRecordId, eatenMinute, timeEstimated) " +
                    "VALUES ('e1', '2026-10-18', 'America/New_York', 'a', NULL, 'Soup', NULL, 1.0, 'cup', 240.0, '{\"ENERGY_KCAL\":90.0}', " +
                    "'USDA', NULL, NULL, NULL, 1, 1, 5, NULL, NULL, NULL, NULL, 780, 1)",
            )
            close()
        }
        val db = Room.databaseBuilder(context, ForkcastDatabase::class.java, TEST_DB).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.query("SELECT displayName, eatenMinute, timeEstimated FROM log_entry WHERE id = 'e1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Soup")
            assertThat(c.getInt(1)).isEqualTo(13 * 60)
            assertThat(c.getInt(2)).isEqualTo(1)
        }
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(7)
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
