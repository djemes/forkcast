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

    private fun SQLiteConnection.insertV6Entries() {
        // Lunch has a 11:00-15:00 window; the snack's meal has none.
        execSQL("INSERT INTO meal_slot (id, name, sortOrder, startMinute, endMinute, createdAt, updatedAt, deletedAt) VALUES ('s', 'Snacks', 3, NULL, NULL, 1, 1, NULL)")
        val cols = "id, date, zoneId, mealSlotId, foodId, displayName, brand, quantity, servingLabel, grams, nutrients, source, aiEstimateId, " +
            "healthConnectRecordId, note, loggedAt, createdAt, updatedAt, deletedAt, hcSyncedAt, waterMl, hcWaterRecordId"
        execSQL("INSERT INTO log_entry ($cols) VALUES ('e1', '2026-10-18', 'America/New_York', 'a', NULL, 'Soup', NULL, 1.0, 'cup', 240.0, '{\"ENERGY_KCAL\":90.0}', 'USDA', NULL, NULL, NULL, 1, 1, 5, NULL, NULL, NULL, NULL)")
        // 2026-10-18T20:15:00Z = 16:15 in New York, on the entry's own day.
        execSQL("INSERT INTO log_entry ($cols) VALUES ('e2', '2026-10-18', 'America/New_York', 's', NULL, 'Apple', NULL, 1.0, 'medium', 182.0, '{\"ENERGY_KCAL\":95.0}', 'USDA', NULL, NULL, NULL, 1792354500000, 1, 5, NULL, NULL, NULL, NULL)")
    }

    /** v7: meal times. Old entries get a typical time for their meal, marked estimated. */
    @Test fun migrate6To7GivesOldEntriesTypicalMealTimes() {
        helper.createDatabase(6).apply {
            insertV1MealSlot()
            insertV6Entries()
            close()
        }
        helper.runMigrationsAndValidate(7, emptyList()).apply {
            prepare("SELECT id, eatenMinute, timeEstimated FROM log_entry ORDER BY id").use { s ->
                assertThat(s.step()).isTrue()
                assertThat(s.getInt(1)).isEqualTo(13 * 60) // middle of 11:00-15:00
                assertThat(s.getInt(2)).isEqualTo(1)
                assertThat(s.step()).isTrue()
                assertThat(s.getInt(1)).isEqualTo(16 * 60 + 15) // when it was logged that day
                assertThat(s.getInt(2)).isEqualTo(1)
            }
            close()
        }
    }

    /** The app opens the database through SupportSQLite: the same backfill runs there. */
    @Test fun version6FileOpensWithCurrentCodeAndGetsMealTimes() {
        helper.createDatabase(6).apply {
            insertV1MealSlot()
            insertV6Entries()
            close()
        }
        val db = Room.databaseBuilder(context, ForkcastDatabase::class.java, TEST_DB).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.query("SELECT eatenMinute, timeEstimated FROM log_entry WHERE id = 'e1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(13 * 60)
            assertThat(c.getInt(1)).isEqualTo(1)
        }
        db.openHelper.writableDatabase.query("SELECT count(*) FROM ai_estimate").use { c -> assertThat(c.moveToFirst()).isTrue() }
        db.close()
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
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(7)
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
