package app.forkcast.core.domain

import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.NutrientKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MealReportTest {

    private val day1 = LocalDate.of(2026, 10, 27)
    private val slots = listOf(MealSlot("b", "Breakfast", 0, null, null), MealSlot("l", "Lunch", 1, null, null))

    private fun entry(id: String, date: LocalDate, slot: String, kcal: Double, minute: Int?, source: FoodSource = FoodSource.USDA) = LogEntry(
        id = id, date = date, mealSlotId = slot, foodId = null, displayName = id, brand = null, quantity = 1.0, servingLabel = "serving",
        grams = 100.0, nutrients = mapOf(NutrientKey.ENERGY_KCAL to kcal, NutrientKey.PROTEIN_G to 10.0, NutrientKey.IRON_MG to 1.0),
        source = source, note = null, loggedAt = Instant.EPOCH, eatenMinute = minute,
    )

    @Test fun `days in order, meals in slot order, items by time, totals and targets`() {
        val entries = listOf(
            entry("toast", day1, "b", 200.0, 8 * 60),
            entry("eggs", day1, "b", 150.0, 7 * 60),
            entry("plate", day1, "l", 600.0, 12 * 60, FoodSource.AI),
            entry("outside", day1.plusDays(5), "l", 999.0, null),
        )
        val r = MealReport.build(day1, day1.plusDays(1), entries, slots, { 2000.0 to mapOf(NutrientKey.PROTEIN_G to 100.0) }, water = mapOf(day1 to 1500.0))
        assertThat(r.days.map { it.date }).containsExactly(day1, day1.plusDays(1)).inOrder()
        val d = r.days.first()
        assertThat(d.meals.map { it.name }).containsExactly("Breakfast", "Lunch").inOrder()
        assertThat(d.meals.first().items.map { it.name }).containsExactly("eggs", "toast").inOrder()
        assertThat(d.kcal).isEqualTo(950.0)
        assertThat(d.targetKcal).isEqualTo(2000.0)
        assertThat(d.nutrients[NutrientKey.PROTEIN_G]).isEqualTo(30.0)
        // Only the key nutrients; an AI item is an estimate.
        assertThat(d.nutrients).doesNotContainKey(NutrientKey.IRON_MG)
        assertThat(d.meals[1].items.single().estimate).isTrue()
        assertThat(d.waterMl).isEqualTo(1500.0)
        assertThat(r.loggedDays).isEqualTo(1)
        assertThat(r.items).hasSize(3)
    }
}
