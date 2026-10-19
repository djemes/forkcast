package app.forkcast.core.domain

import app.forkcast.core.domain.MealSlotRules.NameProblem
import app.forkcast.core.model.MealSlot
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MealSlotRulesTest {

    private val slots = listOf(
        MealSlot("b", "Breakfast", 0, 240, 660),
        MealSlot("l", "Lunch", 1, 660, 900),
        MealSlot("d", "Dinner", 2, 1020, 1320),
        MealSlot("s", "Snacks", 3, null, null),
    )

    @Test fun names() {
        assertThat(MealSlotRules.nameProblem("  ", null, slots)).isEqualTo(NameProblem.BLANK)
        assertThat(MealSlotRules.nameProblem("lunch", null, slots)).isEqualTo(NameProblem.DUPLICATE)
        // Renaming a meal to its own name (or its own name in a new case) is fine.
        assertThat(MealSlotRules.nameProblem("LUNCH", "l", slots)).isNull()
        assertThat(MealSlotRules.nameProblem("x".repeat(31), null, slots)).isEqualTo(NameProblem.TOO_LONG)
        assertThat(MealSlotRules.nameProblem("Second breakfast", null, slots)).isNull()
    }

    @Test fun windowsAndOverlaps() {
        assertThat(MealSlotRules.windowValid(600, 600)).isFalse()
        assertThat(MealSlotRules.windowValid(1320, 120)).isTrue()
        // 10:00–11:30 overlaps Breakfast (to 11:00) and Lunch (from 11:00).
        assertThat(MealSlotRules.overlaps(600, 690, null, slots).map { it.id }).containsExactly("b", "l")
        // A late snack window wrapping midnight overlaps Dinner only.
        assertThat(MealSlotRules.overlaps(1260, 60, "s", slots).map { it.id }).containsExactly("d")
        // A meal never overlaps itself.
        assertThat(MealSlotRules.overlaps(660, 900, "l", slots)).isEmpty()
    }

    @Test fun countLimitsAndMoves() {
        assertThat(MealSlotRules.canDelete(slots.take(1))).isFalse()
        assertThat(MealSlotRules.canAdd(slots)).isTrue()
        assertThat(MealSlotRules.moved(slots, "d", -1)).containsExactly("b", "d", "l", "s").inOrder()
        assertThat(MealSlotRules.moved(slots, "b", -1)).containsExactly("b", "l", "d", "s").inOrder()
    }
}
