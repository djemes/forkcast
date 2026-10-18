package app.forkcast.core.domain

import app.forkcast.core.model.MealSlot

/** Picks the meal that should be pre-selected when the user logs at a given time of day. */
object MealSlotPicker {

    /**
     * Returns the slot whose time window contains [minuteOfDay], else the first slot
     * without a window (Snacks by default), else the first slot. Null only if [slots] is empty.
     */
    fun defaultFor(slots: List<MealSlot>, minuteOfDay: Int): MealSlot? {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "minuteOfDay out of range: $minuteOfDay" }
        val ordered = slots.sortedBy { it.sortOrder }
        return ordered.firstOrNull { it.contains(minuteOfDay) }
            ?: ordered.firstOrNull { it.startMinute == null || it.endMinute == null }
            ?: ordered.firstOrNull()
    }

    private fun MealSlot.contains(minute: Int): Boolean {
        val start = startMinute ?: return false
        val end = endMinute ?: return false
        return if (start <= end) minute in start until end else minute >= start || minute < end
    }

    const val MINUTES_PER_DAY = 24 * 60
}
