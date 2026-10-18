package app.forkcast.core.domain

import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.NutrientKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DayMathTest {

    private fun entry(id: String, meal: String, kcal: Double, protein: Double = 0.0, grams: Double? = 100.0, qty: Double = 1.0) = LogEntry(
        id, LocalDate.of(2026, 10, 18), meal, null, "food $id", null, qty, "serving", grams,
        mapOf(NutrientKey.ENERGY_KCAL to kcal, NutrientKey.PROTEIN_G to protein), FoodSource.CUSTOM, null, Instant.EPOCH,
    )

    @Test fun `totals and states`() {
        val t = DayMath.totals(listOf(entry("a", "b", 860.0, 30.0), entry("b", "l", 400.0, 20.0)), budgetKcal = 2100)
        assertThat(t.eatenKcal).isEqualTo(1260.0)
        assertThat(t.remainingKcal).isEqualTo(840.0)
        assertThat(t[NutrientKey.PROTEIN_G]).isEqualTo(50.0)
        assertThat(t.state).isEqualTo(BudgetState.ON_TRACK)
        assertThat(DayMath.totals(listOf(entry("a", "b", 1680.0)), 2100).state).isEqualTo(BudgetState.APPROACHING)
        val over = DayMath.totals(listOf(entry("a", "b", 2220.0)), 2100)
        assertThat(over.state).isEqualTo(BudgetState.OVER)
        assertThat(over.overKcal).isEqualTo(120.0)
    }

    @Test fun `exactly at budget is not over`() {
        assertThat(DayMath.totals(listOf(entry("a", "b", 2100.0)), 2100).state).isEqualTo(BudgetState.APPROACHING)
    }

    @Test fun `exercise adds to the allowance`() {
        val t = DayMath.totals(listOf(entry("a", "b", 2200.0)), 2100, exerciseKcal = 200.0)
        assertThat(t.remainingKcal).isEqualTo(100.0)
        assertThat(t.state).isEqualTo(BudgetState.APPROACHING)
    }

    @Test fun `meal subtotal`() {
        val list = listOf(entry("a", "b", 100.0), entry("b", "b", 50.0), entry("c", "l", 400.0))
        assertThat(DayMath.mealKcal(list, "b")).isEqualTo(150.0)
    }

    @Test fun `rescale uses grams when known, else quantity`() {
        val e = entry("a", "b", 200.0, 10.0, grams = 100.0, qty = 1.0)
        assertThat(DayMath.rescale(e, 1.5, 150.0)[NutrientKey.ENERGY_KCAL]).isWithin(1e-9).of(300.0)
        val quick = entry("q", "b", 300.0, grams = null, qty = 1.0)
        assertThat(DayMath.rescale(quick, 2.0, null)[NutrientKey.ENERGY_KCAL]).isWithin(1e-9).of(600.0)
    }
}
