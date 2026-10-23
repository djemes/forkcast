package app.forkcast.core.data.db

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import app.forkcast.core.domain.MealTimes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * v6 → v7: entries logged before meal times existed get a typical time for their meal (the middle
 * of its window; snacks: when they were logged that day, else mid-afternoon), marked estimated
 * (owner, 2026-10-23). Runs after Room added the columns. Both overloads, because the app opens
 * the database through SupportSQLite and the migration tests through a driver.
 */
class MealTimeBackfill : AutoMigrationSpec {

    private class Row(val id: String, val date: String, val zone: String, val slot: String, val loggedAt: Long)
    private class Slot(val start: Int?, val end: Int?)

    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        val slots = mutableMapOf<String, Slot>()
        db.query("SELECT id, startMinute, endMinute FROM meal_slot").use { c ->
            while (c.moveToNext()) slots[c.getString(0)] = Slot(if (c.isNull(1)) null else c.getInt(1), if (c.isNull(2)) null else c.getInt(2))
        }
        val rows = mutableListOf<Row>()
        db.query("SELECT id, date, zoneId, mealSlotId, loggedAt FROM log_entry WHERE eatenMinute IS NULL").use { c ->
            while (c.moveToNext()) rows += Row(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))
        }
        for (r in rows) db.execSQL("UPDATE log_entry SET eatenMinute = ?, timeEstimated = 1 WHERE id = ?", arrayOf<Any>(minuteFor(r, slots), r.id))
    }

    override fun onPostMigrate(connection: SQLiteConnection) {
        val slots = mutableMapOf<String, Slot>()
        connection.prepare("SELECT id, startMinute, endMinute FROM meal_slot").use { s ->
            while (s.step()) slots[s.getText(0)] = Slot(if (s.isNull(1)) null else s.getInt(1), if (s.isNull(2)) null else s.getInt(2))
        }
        val rows = mutableListOf<Row>()
        connection.prepare("SELECT id, date, zoneId, mealSlotId, loggedAt FROM log_entry WHERE eatenMinute IS NULL").use { s ->
            while (s.step()) rows += Row(s.getText(0), s.getText(1), s.getText(2), s.getText(3), s.getLong(4))
        }
        for (r in rows) {
            connection.prepare("UPDATE log_entry SET eatenMinute = ?, timeEstimated = 1 WHERE id = ?").use { s ->
                s.bindLong(1, minuteFor(r, slots).toLong())
                s.bindText(2, r.id)
                s.step()
            }
        }
    }

    private fun minuteFor(r: Row, slots: Map<String, Slot>): Int {
        val slot = slots[r.slot]
        val zone = runCatching { ZoneId.of(r.zone) }.getOrDefault(ZoneId.systemDefault())
        val date = runCatching { LocalDate.parse(r.date) }.getOrNull() ?: return MealTimes.FALLBACK_MINUTE
        return MealTimes.typicalMinute(slot?.start, slot?.end, date, Instant.ofEpochMilli(r.loggedAt), zone)
    }
}
