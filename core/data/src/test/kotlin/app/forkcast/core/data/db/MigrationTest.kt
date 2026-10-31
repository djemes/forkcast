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
 * version from its exported JSON (core/data/schemas), migrates, and validates. v9 is 2.0's
 * baseline: there is nothing older to migrate from.
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

    /** A v9 file, as 1.9.1 leaves it, opens with the current code as it is: same version, rows kept. */
    @Test fun baselineFileOpensWithoutMigration() {
        helper.createDatabase(9).apply {
            execSQL(
                "INSERT INTO meal_slot (id, name, sortOrder, startMinute, endMinute, createdAt, updatedAt, deletedAt) " +
                    "VALUES ('a', 'Lunch', 1, 660, 900, 1, 1, NULL)",
            )
            close()
        }
        val db = Room.databaseBuilder(context, ForkcastDatabase::class.java, TEST_DB).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.query("SELECT name FROM meal_slot").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Lunch")
        }
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(9)
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
