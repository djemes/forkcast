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

    /** Guards against changing an entity without bumping the version and exporting a schema. */
    @Test fun version1SchemaMatchesCurrentCode() {
        helper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO meal_slot (id, name, sortOrder, startMinute, endMinute, createdAt, updatedAt, deletedAt) " +
                    "VALUES ('a', 'Lunch', 1, 660, 900, 1, 1, NULL)",
            )
            close()
        }
        val db = Room.databaseBuilder(context, ForkcastDatabase::class.java, TEST_DB)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase.query("SELECT name FROM meal_slot").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Lunch")
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
