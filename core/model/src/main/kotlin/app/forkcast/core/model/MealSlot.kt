package app.forkcast.core.model

/**
 * A meal section on the Today screen (Breakfast, Lunch, ...). Names, count, and time
 * windows are user-configurable. A window is minutes after local midnight; a window
 * that wraps past midnight has [startMinute] > [endMinute]. Slots without a window
 * (Snacks by default) are the fallback when no window matches.
 */
data class MealSlot(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val startMinute: Int?,
    val endMinute: Int?,
)
