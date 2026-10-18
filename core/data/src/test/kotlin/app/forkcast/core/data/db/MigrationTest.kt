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
 * Migration tests. Each new schema version adds a test that creates the old version
 * from its exported JSON (core/data/schemas), migrates, and validates.
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

    private fun SQLiteConnection.insertV1MealSlot() = execSQL(
        "INSERT INTO meal_slot (id, name, sortOrder, startMinute, endMinute, createdAt, updatedAt, deletedAt) " +
            "VALUES ('a', 'Lunch', 1, 660, 900, 1, 1, NULL)",
    )

    @Test fun migrate1To2KeepsMealSlotsAndAddsTables() {
        helper.createDatabase(1).apply {
            insertV1MealSlot()
            close()
        }
        helper.runMigrationsAndValidate(2, emptyList()).apply {
            prepare("SELECT name FROM meal_slot").use { s ->
                assertThat(s.step()).isTrue()
                assertThat(s.getText(0)).isEqualTo("Lunch")
            }
            prepare("SELECT count(*) FROM log_entry").use { s ->
                assertThat(s.step()).isTrue()
                assertThat(s.getLong(0)).isEqualTo(0)
            }
            close()
        }
    }

    /** A v1 file from Phase 0 opens with today's code, upgraded in place with its data. */
    @Test fun version1FileOpensWithCurrentCode() {
        helper.createDatabase(1).apply {
            insertV1MealSlot()
            close()
        }
        val db = Room.databaseBuilder(context, ForkcastDatabase::class.java, TEST_DB)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase.query("SELECT name FROM meal_slot").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Lunch")
        }
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(2)
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
