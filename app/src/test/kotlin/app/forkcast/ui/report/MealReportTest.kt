package app.forkcast.ui.report

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.report.Cell
import app.forkcast.core.domain.MealReport
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.MealSlot
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.format.UnitLabels
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class MealReportTest {

    @get:Rule val compose = createComposeRule()

    private val day = LocalDate.of(2026, 10, 28)
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val files = MealReportFiles(res, UnitFormatter(UnitPrefs.US, Locale.US, UnitLabels.from(res)), Locale.US)

    private fun report(): MealReport.Report {
        val e = LogEntry(
            id = "1", date = day, mealSlotId = "b", foodId = null, displayName = "Oatmeal", brand = null, quantity = 1.0, servingLabel = "1 cup",
            grams = 234.0, nutrients = mapOf(NutrientKey.ENERGY_KCAL to 158.0, NutrientKey.PROTEIN_G to 6.0), source = FoodSource.AI,
            note = null, loggedAt = Instant.EPOCH, eatenMinute = 7 * 60 + 5, waterMl = null,
        )
        return MealReport.build(day, day, listOf(e), listOf(MealSlot("b", "Breakfast", 0, null, null)), { 2000.0 to emptyMap() }, mapOf(day to 1000.0), mapOf(day to 80.0))
    }

    @Test fun spreadsheetHasAnItemRowAndADayRowInTheUsersUnits() {
        val sheets = files.sheets(report(), ReportOptions(day, day, ReportFormat.SPREADSHEET, weight = true, water = true))
        assertThat(sheets.map { it.name }).containsExactly("Items", "Daily totals").inOrder()
        val item = sheets[0].rows[1]
        assertThat(item).containsAtLeast(Cell.Text("2026-10-28"), Cell.Text("07:05"), Cell.Text("Breakfast"), Cell.Text("Oatmeal"), Cell.Text("1 cup"), Cell.Number(158.0))
        // An AI item says it's an estimate.
        assertThat(item.last()).isEqualTo(Cell.Text("Yes"))
        val header = sheets[1].rows[0].map { (it as Cell.Text).value }
        assertThat(header).containsAtLeast("Water (fl oz)", "Weight (lb)")
        val dayRow = sheets[1].rows[1]
        assertThat((dayRow.last() as Cell.Number).value).isWithin(0.1).of(176.4)
    }

    @Test fun theDialogDefaultsToTheLastWeekAndAPdf() {
        var chosen: ReportOptions? = null
        val today = LocalDate.of(2026, 10, 29)
        compose.setContent { ForkcastTheme { MealReportDialog(today, onContinue = { chosen = it }, onDismiss = {}) } }
        compose.onNodeWithText("Include water").performScrollTo().performClick()
        compose.onNodeWithText("Continue").performClick()
        assertThat(chosen).isEqualTo(ReportOptions(today.minusDays(6), today, ReportFormat.PDF, weight = true, water = false))
    }
}
