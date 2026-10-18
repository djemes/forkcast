package app.forkcast.core.domain

import app.forkcast.core.model.MealSlot
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MealSlotPickerTest {

    private val breakfast = MealSlot("b", "Breakfast", 0, 4 * 60, 11 * 60)
    private val lunch = MealSlot("l", "Lunch", 1, 11 * 60, 15 * 60)
    private val dinner = MealSlot("d", "Dinner", 2, 17 * 60, 22 * 60)
    private val snacks = MealSlot("s", "Snacks", 3, null, null)
    private val defaults = listOf(snacks, dinner, lunch, breakfast)

    @Test fun `picks the window that contains the time`() {
        assertThat(MealSlotPicker.defaultFor(defaults, 7 * 60)).isEqualTo(breakfast)
        assertThat(MealSlotPicker.defaultFor(defaults, 12 * 60 + 40)).isEqualTo(lunch)
        assertThat(MealSlotPicker.defaultFor(defaults, 19 * 60)).isEqualTo(dinner)
    }

    @Test fun `window end is exclusive`() {
        assertThat(MealSlotPicker.defaultFor(defaults, 11 * 60)).isEqualTo(lunch)
    }

    @Test fun `falls back to the slot without a window`() {
        assertThat(MealSlotPicker.defaultFor(defaults, 16 * 60)).isEqualTo(snacks)
        assertThat(MealSlotPicker.defaultFor(defaults, 23 * 60)).isEqualTo(snacks)
    }

    @Test fun `handles windows that wrap past midnight`() {
        val lateNight = MealSlot("n", "Late night", 4, 22 * 60, 2 * 60)
        val slots = defaults + lateNight
        assertThat(MealSlotPicker.defaultFor(slots, 23 * 60 + 30)).isEqualTo(lateNight)
        assertThat(MealSlotPicker.defaultFor(slots, 60)).isEqualTo(lateNight)
        assertThat(MealSlotPicker.defaultFor(slots, 3 * 60)).isEqualTo(snacks)
    }

    @Test fun `falls back to first slot when every slot has a window`() {
        assertThat(MealSlotPicker.defaultFor(listOf(lunch, breakfast), 16 * 60)).isEqualTo(breakfast)
    }

    @Test fun `empty list gives null`() {
        assertThat(MealSlotPicker.defaultFor(emptyList(), 600)).isNull()
    }
}
