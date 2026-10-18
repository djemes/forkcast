package app.forkcast.core.data.backup

import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.domain.UnitConversions
import app.forkcast.core.model.NutrientKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spreadsheet-friendly CSV (SPEC §4.11): one row per food log entry, one per weigh-in.
 * Deleted rows are left out. Numbers use a dot decimal separator regardless of locale.
 */
@Singleton
class CsvExporter @Inject constructor(
    db: ForkcastDatabase,
    private val clock: Clock,
) {
    private val dao = db.backupDao()

    suspend fun foodLog(): String {
        val slots = dao.mealSlots().associate { it.id to it.name }
        val rows = dao.logEntries().filter { it.deletedAt == null }.sortedWith(compareBy({ it.date }, { it.loggedAt }))
        val header = listOf("date", "time", "meal", "food", "brand", "quantity", "serving", "grams", "source") +
            NutrientKey.entries.map { it.name.lowercase() }
        return buildCsv(header, rows.map { e ->
            listOf(
                e.date.toString(),
                localTime(e.loggedAt, e.zoneId),
                slots[e.mealSlotId].orEmpty(),
                e.displayName,
                e.brand.orEmpty(),
                number(e.quantity),
                e.servingLabel,
                e.grams?.let(::number).orEmpty(),
                e.source.name,
            ) + NutrientKey.entries.map { key -> e.nutrients[key]?.let(::number).orEmpty() }
        })
    }

    suspend fun weights(): String {
        val rows = dao.weights().filter { it.deletedAt == null }.sortedBy { it.timestamp }
        val header = listOf("date", "time", "weight_kg", "weight_lb", "body_fat_pct", "source")
        return buildCsv(header, rows.map { w ->
            val at = Instant.ofEpochMilli(w.timestamp).atZone(clock.zone)
            listOf(
                at.toLocalDate().toString(),
                at.toLocalTime().withNano(0).toString(),
                number(w.kg),
                number(UnitConversions.kgToLb(w.kg)),
                w.bodyFatPct?.let(::number).orEmpty(),
                w.source.name,
            )
        })
    }

    private fun localTime(epochMs: Long, zoneId: String): String {
        val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(clock.zone)
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).toLocalTime().withNano(0).toString()
    }
}

internal fun buildCsv(header: List<String>, rows: List<List<String>>): String = buildString {
    (listOf(header) + rows).forEach { cells ->
        append(cells.joinToString(",") { escapeCsv(it) })
        append("\r\n")
    }
}

/** RFC 4180 quoting; a leading =, +, -, @ is prefixed with ' so spreadsheets don't run it as a formula. */
internal fun escapeCsv(value: String): String {
    val safe = if (value.isNotEmpty() && value[0] in "=+-@" && value.toDoubleOrNull() == null) "'$value" else value
    return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + safe.replace("\"", "\"\"") + "\""
    } else {
        safe
    }
}

/** Up to 2 decimals, no trailing zeros, dot separator. */
private fun number(v: Double): String =
    java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
