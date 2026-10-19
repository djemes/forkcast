package app.forkcast.core.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * One day of activity read from Health Connect (SPEC §4.9, §6.1 ActivityDay). Null means
 * Health Connect had no data of that kind for the day (not zero).
 */
data class ActivityDay(
    val date: LocalDate,
    val steps: Long?,
    val activeKcal: Double?,
    val totalKcal: Double?,
    val exerciseMinutes: Int?,
    val sessions: List<ExerciseSession>,
)

/** A workout from Health Connect. [exerciseType] is Health Connect's EXERCISE_TYPE_* constant. */
@Serializable
data class ExerciseSession(
    /** Health Connect record id. */
    val id: String,
    val title: String? = null,
    val exerciseType: Int,
    /** Epoch ms UTC. */
    val start: Long,
    val end: Long,
    /** Active calories recorded during the session, if any source wrote them. */
    val activeKcal: Double? = null,
)

/** How a maintenance estimate was made (SPEC §5.6). */
@Serializable
enum class TdeeMethod { FORMULA, ADAPTIVE }

@Serializable
enum class TdeeConfidence { LOW, MEDIUM, HIGH }

/** The user's own call on whether a day's log is complete (SPEC §5.6); AUTO uses the rule. */
enum class DayCompleteness { AUTO, COMPLETE, INCOMPLETE }
