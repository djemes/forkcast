package app.forkcast.core.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Settings as 1.x wrote them before 1.9.1, in 1.9.1's shape (the conversion release, roadmap 2.0
 * task 6.0; removed in 2.0). The backup schedule was a frequency and a weekday (1.5 to 1.8.1)
 * until its days were first chosen; it's now only the days.
 */
internal object LegacySettings {

    fun upgrade(settings: JsonObject): JsonObject {
        val backup = settings[BACKUP] as? JsonObject ?: return settings
        val days = (backup[DAYS] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: JsonArray(oldDays(backup).map(::JsonPrimitive))
        return JsonObject(settings + (BACKUP to JsonObject(backup - FREQUENCY - WEEKDAY + (DAYS to days))))
    }

    /** What the daily or weekly setting meant: every day, or the one weekday (Monday when unset). */
    private fun oldDays(backup: JsonObject): List<Int> {
        if ((backup[FREQUENCY] as? JsonPrimitive)?.contentOrNull == "DAILY") return (1..7).toList()
        return listOf(((backup[WEEKDAY] as? JsonPrimitive)?.intOrNull ?: 1).coerceIn(1, 7))
    }

    private const val BACKUP = "backup"
    private const val DAYS = "days"
    private const val FREQUENCY = "frequency"
    private const val WEEKDAY = "weekday"
}
