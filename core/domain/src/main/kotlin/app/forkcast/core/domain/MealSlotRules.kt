package app.forkcast.core.domain

import app.forkcast.core.model.MealSlot

/** Rules for Settings › Meals (SPEC §4.12: names, count, time windows). */
object MealSlotRules {
    const val MAX_NAME = 30
    const val MIN_MEALS = 1
    const val MAX_MEALS = 8

    enum class NameProblem { BLANK, TOO_LONG, DUPLICATE }

    /** Null when [name] is fine for the slot [selfId] (null for a new slot). */
    fun nameProblem(name: String, selfId: String?, slots: List<MealSlot>): NameProblem? {
        val n = name.trim()
        return when {
            n.isEmpty() -> NameProblem.BLANK
            n.length > MAX_NAME -> NameProblem.TOO_LONG
            slots.any { it.id != selfId && it.name.trim().equals(n, ignoreCase = true) } -> NameProblem.DUPLICATE
            else -> null
        }
    }

    /** A window is minutes after midnight; start == end would be empty. It may wrap past midnight. */
    fun windowValid(start: Int, end: Int): Boolean = start in 0 until 1440 && end in 0 until 1440 && start != end

    /** The other slots whose window overlaps [start]–[end] (a hint: the earliest in the list wins). */
    fun overlaps(start: Int, end: Int, selfId: String?, slots: List<MealSlot>): List<MealSlot> {
        val mine = minutes(start, end)
        return slots.filter { s ->
            val a = s.startMinute
            val b = s.endMinute
            s.id != selfId && a != null && b != null && minutes(a, b).any { it in mine }
        }
    }

    fun canDelete(slots: List<MealSlot>): Boolean = slots.size > MIN_MEALS
    fun canAdd(slots: List<MealSlot>): Boolean = slots.size < MAX_MEALS

    /** Moves [id] by [delta] places; returns the new id order. */
    fun moved(slots: List<MealSlot>, id: String, delta: Int): List<String> {
        val ids = slots.sortedBy { it.sortOrder }.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        if (from < 0) return ids
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        ids.add(to, ids.removeAt(from))
        return ids
    }

    private fun minutes(start: Int, end: Int): Set<Int> =
        if (start < end) (start until end).toSet() else ((start until 1440) + (0 until end)).toSet()
}
