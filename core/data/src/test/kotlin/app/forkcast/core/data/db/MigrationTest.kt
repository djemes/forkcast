package app.forkcast.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
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

    /** Two v7 entries from 1.3: one with a time the user set, one with a filled-in ("estimated") time. */
    private fun SQLiteConnection.insertV7Entries() {
        execSQL(
            "INSERT INTO meal_slot (id, name, sortOrder, startMinute, endMinute, createdAt, updatedAt, deletedAt) " +
                "VALUES ('a', 'Lunch', 1, 660, 900, 1, 1, NULL)",
        )
        val cols = "id, date, zoneId, mealSlotId, foodId, displayName, brand, quantity, servingLabel, grams, nutrients, source, aiEstimateId, " +
            "healthConnectRecordId, note, loggedAt, createdAt, updatedAt, deletedAt, hcSyncedAt, waterMl, hcWaterRecordId, eatenMinute, timeEstimated"
        execSQL(
            "INSERT INTO log_entry ($cols) VALUES ('e1', '2026-10-18', 'America/New_York', 'a', NULL, 'Soup', NULL, 1.0, 'cup', 240.0, " +
                "'{\"ENERGY_KCAL\":90.0}', 'USDA', NULL, NULL, NULL, 1, 1, 5, NULL, NULL, NULL, NULL, 780, 1)",
        )
        execSQL(
            "INSERT INTO log_entry ($cols) VALUES ('e2', '2026-10-19', 'America/New_York', 'a', NULL, 'Tea', NULL, 1.0, 'cup', 237.0, " +
                "'{\"ENERGY_KCAL\":2.0}', 'USDA', NULL, NULL, NULL, 1, 1, 5, NULL, 9, 237.0, 'hc-w', 745, 0)",
        )
    }

    /** v8 (1.4): every entry keeps its time and the rest of its row; the "estimated" flag goes. */
    @Test fun migrate7To8KeepsTimesAndDropsTheFlag() {
        helper.createDatabase(7).apply {
            insertV7Entries()
            close()
        }
        helper.runMigrationsAndValidate(8, emptyList()).apply {
            prepare("SELECT id, displayName, eatenMinute, waterMl, hcSyncedAt, hcWaterRecordId FROM log_entry ORDER BY id").use { s ->
                assertThat(s.step()).isTrue()
                assertThat(s.getText(1)).isEqualTo("Soup")
                assertThat(s.getInt(2)).isEqualTo(780)
                assertThat(s.step()).isTrue()
                assertThat(s.getText(1)).isEqualTo("Tea")
                assertThat(s.getInt(2)).isEqualTo(745)
                assertThat(s.getDouble(3)).isEqualTo(237.0)
                assertThat(s.getLong(4)).isEqualTo(9)
                assertThat(s.getText(5)).isEqualTo("hc-w")
                assertThat(s.step()).isFalse()
            }
            prepare("SELECT count(*) FROM pragma_table_info('log_entry') WHERE name = 'timeEstimated'").use { s ->
                assertThat(s.step()).isTrue()
                assertThat(s.getLong(0)).isEqualTo(0)
            }
            close()
        }
    }

    /** The app opens the database through SupportSQLite: a 1.3 file opens there too, migrated. */
    @Test fun version7FileFrom13OpensWithCurrentCode() {
        helper.createDatabase(7).apply {
            insertV7Entries()
            close()
        }
        val db = Room.databaseBuilder(context, ForkcastDatabase::class.java, TEST_DB).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.query("SELECT eatenMinute FROM log_entry ORDER BY id").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(780)
            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(745)
        }
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(8)
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
