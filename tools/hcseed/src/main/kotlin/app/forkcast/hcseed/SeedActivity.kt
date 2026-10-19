package app.forkcast.hcseed

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.sin

/**
 * Development stand-in for Samsung Health (not shipped). Grant its permissions with
 * `adb shell pm grant app.forkcast.hcseed android.permission.health.WRITE_WEIGHT` etc., then
 * `adb shell am start -n app.forkcast.hcseed/.SeedActivity --es scenario history|today|clear`.
 */
class SeedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario") ?: "history"
        val client = HealthConnectClient.getOrCreate(this)
        MainScope().launch {
            try {
                when (scenario) {
                    "history" -> client.insertRecords(history())
                    "today" -> client.insertRecords(today())
                    // Activity bonus check: 28 usual days (~400 active), then a rest day, a normal day and a big workout today.
                    "bonus" -> {
                        listOf(StepsRecord::class, ActiveCaloriesBurnedRecord::class, ExerciseSessionRecord::class)
                            .forEach { client.deleteRecords(it, TimeRangeFilter.after(Instant.EPOCH)) }
                        client.insertRecords(bonus())
                    }
                    "clear" -> listOf(
                        WeightRecord::class, BodyFatRecord::class, HeightRecord::class, StepsRecord::class,
                        ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class, ExerciseSessionRecord::class,
                    ).forEach { client.deleteRecords(it, TimeRangeFilter.after(Instant.EPOCH)) }
                }
                Log.i(TAG, "seeded $scenario")
            } catch (e: Exception) {
                Log.e(TAG, "seed $scenario failed", e)
            }
            finish()
        }
    }

    private val zone = ZoneId.systemDefault()
    private fun at(day: LocalDate, h: Int, m: Int = 0): Instant = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant()
    private fun off(t: Instant) = zone.rules.getOffset(t)

    private fun history(): List<Record> {
        val today = LocalDate.now(zone)
        val out = ArrayList<Record>()
        // 60 days of morning weigh-ins: 82 → 80 kg with a small wobble, body fat alongside.
        for (d in 60 downTo 1) {
            val day = today.minusDays(d.toLong())
            val t = at(day, 7)
            val kg = 80.0 + 2.0 * d / 60 + 0.3 * sin(d.toDouble())
            out += WeightRecord(t, off(t), Mass.kilograms(kg), Metadata.manualEntry())
            out += BodyFatRecord(t.plusSeconds(5), off(t), Percentage(20.0 + d / 30.0), Metadata.manualEntry())
        }
        out += HeightRecord(at(today.minusDays(30), 8), off(at(today.minusDays(30), 8)), Length.meters(1.80), Metadata.manualEntry())
        // Two weeks of activity.
        for (d in 13 downTo 0) {
            val day = today.minusDays(d.toLong())
            val start = at(day, 8)
            val end = at(day, 20)
            out += StepsRecord(start, off(start), end, off(end), 6000L + (d * 431L) % 5000, Metadata.manualEntry())
            out += ActiveCaloriesBurnedRecord(start, off(start), end, off(end), Energy.kilocalories(300.0 + (d * 37) % 200), Metadata.manualEntry())
            out += TotalCaloriesBurnedRecord(at(day, 0), off(start), at(day.plusDays(1), 0), off(end), Energy.kilocalories(2500.0 + (d * 37) % 200), Metadata.manualEntry())
        }
        val runStart = at(today.minusDays(1), 18)
        val runEnd = at(today.minusDays(1), 18, 40)
        out += ExerciseSessionRecord(
            runStart, off(runStart), runEnd, off(runEnd), Metadata.manualEntry(),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, title = "Evening run",
        )
        return out
    }

    private fun bonus(): List<Record> {
        val today = LocalDate.now(zone)
        val out = ArrayList<Record>()
        fun day(d: Long, steps: Long, active: Double) {
            val date = today.minusDays(d)
            val start = at(date, 6)
            val end = if (d == 0L) java.time.Instant.now().minusSeconds(60) else at(date, 22)
            out += StepsRecord(start, off(start), end, off(end), steps, Metadata.manualEntry())
            out += ActiveCaloriesBurnedRecord(start, off(start), end, off(end), Energy.kilocalories(active), Metadata.manualEntry())
        }
        for (d in 30L downTo 3L) day(d, 8000 + (d * 97) % 1500, 380.0 + (d * 13) % 40)
        day(2, 2500, 150.0)   // no exercise
        day(1, 8200, 405.0)   // a normal day
        day(0, 16000, 950.0)  // big workout day
        val runStart = at(today, 6, 30)
        val runEnd = at(today, 7, 45)
        out += ExerciseSessionRecord(
            runStart, off(runStart), runEnd, off(runEnd), Metadata.manualEntry(),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, title = "Long run",
        )
        return out
    }

    private fun today(): List<Record> {
        val t = at(LocalDate.now(zone), 7, 5)
        return listOf(
            WeightRecord(t, off(t), Mass.kilograms(79.8), Metadata.manualEntry()),
            BodyFatRecord(t, off(t), Percentage(19.5), Metadata.manualEntry()),
        )
    }

    private companion object {
        const val TAG = "HcSeed"
    }
}
