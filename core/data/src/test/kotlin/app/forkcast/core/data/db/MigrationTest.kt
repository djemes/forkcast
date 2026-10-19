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

    @Test fun migrate2To3KeepsLogAndAddsAiTables() {
        helper.createDatabase(2).apply {
            insertV1MealSlot()
            execSQL(
                "INSERT INTO log_entry (id, date, zoneId, mealSlotId, foodId, displayName, brand, quantity, servingLabel, grams, " +
                    "nutrients, source, aiEstimateId, healthConnectRecordId, note, loggedAt, createdAt, updatedAt, deletedAt) " +
                    "VALUES ('e1', '2026-10-18', 'America/New_York', 'a', NULL, 'Toast', NULL, 1.0, 'slice', 30.0, " +
                    "'{\"ENERGY_KCAL\":80.0}', 'QUICK_ADD', NULL, NULL, NULL, 1, 1, 1, NULL)",
            )
            close()
        }
        helper.runMigrationsAndValidate(3, emptyList()).apply {
            prepare("SELECT displayName, nutrients FROM log_entry").use { s ->
                assertThat(s.step()).isTrue()
                assertThat(s.getText(0)).isEqualTo("Toast")
                assertThat(s.getText(1)).isEqualTo("{\"ENERGY_KCAL\":80.0}")
            }
            for (table in listOf("ai_estimate", "ai_component", "ai_profile", "usage_record", "notification_log", "ai_match_cache")) {
                prepare("SELECT count(*) FROM $table").use { s ->
                    assertThat(s.step()).isTrue()
                    assertThat(s.getLong(0)).isEqualTo(0)
                }
            }
            close()
        }
    }

    @Test fun migrate3To4KeepsLogAndWaterAndAddsHealthTables() {
        helper.createDatabase(3).apply {
            insertV1MealSlot()
            execSQL(
                "INSERT INTO log_entry (id, date, zoneId, mealSlotId, foodId, displayName, brand, quantity, servingLabel, grams, " +
                    "nutrients, source, aiEstimateId, healthConnectRecordId, note, loggedAt, createdAt, updatedAt, deletedAt) " +
                    "VALUES ('e1', '2026-10-18', 'America/New_York', 'a', NULL, 'Toast', NULL, 1.0, 'slice', 30.0, " +
                    "'{\"ENERGY_KCAL\":80.0}', 'QUICK_ADD', NULL, NULL, NULL, 1, 1, 5, NULL)",
            )
            execSQL(
                "INSERT INTO water_entry (id, timestamp, date, zoneId, ml, createdAt, updatedAt, deletedAt) " +
                    "VALUES ('w1', 1, '2026-10-18', 'America/New_York', 250.0, 1, 1, NULL)",
            )
            close()
        }
        helper.runMigrationsAndValidate(4, emptyList()).apply {
            // Existing rows are pending for Health Connect (never written).
            prepare("SELECT displayName, hcSyncedAt FROM log_entry").use { s ->
                assertThat(s.step()).isTrue()
                assertThat(s.getText(0)).isEqualTo("Toast")
                assertThat(s.isNull(1)).isTrue()
            }
            prepare("SELECT ml, hcRecordId, hcSyncedAt FROM water_entry").use { s ->
                assertThat(s.step()).isTrue()
                assertThat(s.getDouble(0)).isEqualTo(250.0)
                assertThat(s.isNull(1)).isTrue()
                assertThat(s.isNull(2)).isTrue()
            }
            for (table in listOf("activity_day", "tdee_estimate")) {
                prepare("SELECT count(*) FROM $table").use { s ->
                    assertThat(s.step()).isTrue()
                    assertThat(s.getLong(0)).isEqualTo(0)
                }
            }
            close()
        }
    }

    @Test fun migrate4To5AddsDrinkWaterColumns() {
        helper.createDatabase(4).apply {
            insertV1MealSlot()
            execSQL(
                "INSERT INTO log_entry (id, date, zoneId, mealSlotId, foodId, displayName, brand, quantity, servingLabel, grams, " +
                    "nutrients, source, aiEstimateId, healthConnectRecordId, note, loggedAt, createdAt, updatedAt, deletedAt, hcSyncedAt) " +
                    "VALUES ('e1', '2026-10-18', 'America/New_York', 'a', NULL, 'Tea', NULL, 1.0, 'cup', 237.0, " +
                    "'{\"ENERGY_KCAL\":2.0}', 'USDA', NULL, NULL, NULL, 1, 1, 5, NULL, 5)",
            )
            close()
        }
        helper.runMigrationsAndValidate(5, emptyList()).apply {
            // Old entries don't count toward water; nothing is rewritten to Health Connect.
            prepare("SELECT displayName, waterMl, hcWaterRecordId, hcSyncedAt FROM log_entry").use { s ->
                assertThat(s.step()).isTrue()
                assertThat(s.getText(0)).isEqualTo("Tea")
                assertThat(s.isNull(1)).isTrue()
                assertThat(s.isNull(2)).isTrue()
                assertThat(s.getLong(3)).isEqualTo(5)
            }
            prepare("SELECT count(*) FROM food WHERE countsAsWater IS NULL").use { s -> assertThat(s.step()).isTrue() }
            close()
        }
    }

    @Test fun migrate5To6KeepsLogAndAddsRecipeTables() {
        helper.createDatabase(5).apply {
            insertV1MealSlot()
            execSQL(
                "INSERT INTO log_entry (id, date, zoneId, mealSlotId, foodId, displayName, brand, quantity, servingLabel, grams, " +
                    "nutrients, source, aiEstimateId, healthConnectRecordId, note, loggedAt, createdAt, updatedAt, deletedAt, hcSyncedAt, waterMl, hcWaterRecordId) " +
                    "VALUES ('e1', '2026-10-18', 'America/New_York', 'a', NULL, 'Tea', NULL, 1.0, 'cup', 237.0, " +
                    "'{\"ENERGY_KCAL\":2.0}', 'USDA', NULL, NULL, NULL, 1, 1, 5, NULL, 5, 237.0, NULL)",
            )
            close()
        }
        helper.runMigrationsAndValidate(6, emptyList()).apply {
            prepare("SELECT displayName, waterMl FROM log_entry").use { s ->
                assertThat(s.step()).isTrue()
                assertThat(s.getText(0)).isEqualTo("Tea")
                assertThat(s.getDouble(1)).isEqualTo(237.0)
            }
            prepare("SELECT count(*) FROM recipe").use { s -> assertThat(s.step()).isTrue(); assertThat(s.getLong(0)).isEqualTo(0) }
            prepare("SELECT count(*) FROM recipe_ingredient").use { s -> assertThat(s.step()).isTrue(); assertThat(s.getLong(0)).isEqualTo(0) }
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
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(6)
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
