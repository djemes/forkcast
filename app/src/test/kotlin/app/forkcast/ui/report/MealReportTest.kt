package app.forkcast.ui.report

import androidx.compose.material3.SnackbarHostState
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

    @Test fun sheetProgressReachesTheEnd() {
        val seen = mutableListOf<Float>()
        files.sheets(report(), ReportOptions(day, day, ReportFormat.SPREADSHEET, weight = true, water = true)) { seen += it }
        assertThat(seen.last()).isEqualTo(1f)
        assertThat(seen).isInOrder()
    }

    @Test fun theDialogDefaultsToTheLast30DaysAndAPdf() {
        var chosen: ReportOptions? = null
        val today = LocalDate.of(2026, 10, 29)
        compose.setContent { ForkcastTheme { MealReportDialog(today, firstDate = null, onContinue = { chosen = it }, onDismiss = {}) } }
        compose.onNodeWithText("Include every meal").assertDoesNotExist()
        compose.onNodeWithText("Include water").performScrollTo().performClick()
        compose.onNodeWithText("Make report").performClick()
        assertThat(chosen).isEqualTo(ReportOptions(today.minusDays(29), today, ReportFormat.PDF, weight = true, water = false))
        assertThat(chosen?.meals).isTrue()
    }

    @Test fun longRangesAreASummaryUnlessEveryMealIsAskedFor() {
        var chosen: ReportOptions? = null
        val today = LocalDate.of(2026, 10, 29)
        compose.setContent { ForkcastTheme { MealReportDialog(today, firstDate = null, onContinue = { chosen = it }, onDismiss = {}) } }
        compose.onNodeWithText("Last 90 days").performClick()
        compose.onNodeWithText("Daily totals against your targets, and weekly and monthly averages. No photos.").performScrollTo().assertExists()
        compose.onNodeWithText("Make report").performClick()
        assertThat(chosen?.from).isEqualTo(today.minusDays(89))
        assertThat(chosen?.summary).isTrue()
        assertThat(chosen?.meals).isFalse()

        compose.onNodeWithText("Include every meal").performScrollTo().performClick()
        compose.onNodeWithText("Make report").performClick()
        assertThat(chosen?.meals).isTrue()
    }

    @Test fun allTimeStartsOnTheFirstLoggedDay() {
        var chosen: ReportOptions? = null
        val today = LocalDate.of(2026, 10, 29)
        val first = LocalDate.of(2024, 3, 2)
        compose.setContent { ForkcastTheme { MealReportDialog(today, firstDate = first, onContinue = { chosen = it }, onDismiss = {}) } }
        compose.onNodeWithText("All time").performClick()
        compose.onNodeWithText("Spreadsheet").performScrollTo().performClick()
        // The spreadsheet always has every row: no summary choice.
        compose.onNodeWithText("Include every meal").assertDoesNotExist()
        compose.onNodeWithText("Make report").performClick()
        assertThat(chosen?.from).isEqualTo(first)
        assertThat(chosen?.to).isEqualTo(today)
        assertThat(chosen?.format).isEqualTo(ReportFormat.SPREADSHEET)
    }

    @Test fun theSpreadsheetPreviewSaysWhatsInItAndHasNoPrint() {
        val options = ReportOptions(day.minusDays(6), day, ReportFormat.SPREADSHEET, weight = true, water = true)
        val ready = ReportStep.Ready("/nowhere/forkcast-report.xlsx", options, days = 400, loggedDays = 5, entries = 3311)
        var saved = 0
        compose.setContent {
            ForkcastTheme {
                ReportPreview(ready, SnackbarHostState(), onSave = { saved++ }, onShare = {}, onPrint = {}, onClose = {})
            }
        }
        compose.onNodeWithText("400 days, 5 with something logged").assertExists()
        compose.onNodeWithText("3,311 foods logged").assertExists()
        compose.onNodeWithText("Print…").assertDoesNotExist()
        compose.onNodeWithText("Share…").assertExists()
        compose.onNodeWithText("Save…").performClick()
        assertThat(saved).isEqualTo(1)
    }
}
