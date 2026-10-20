package app.forkcast.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.domain.DayTotals
import app.forkcast.core.model.UnitPrefs
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Home-screen widgets (SPEC §3 Phase 5, DESIGN.md §11): what each size shows for a given day. */
@RunWith(AndroidJUnit4::class)
class WidgetsTest {

    private val day = sampleSnapshot().copy(units = UnitPrefs.US)

    @Test fun squareCaloriesWidgetShowsWhatsLeft() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(CaloriesWidget.SMALL)
        provideComposable { CaloriesContent(day, WidgetStyle()) }
        // 2,100 − 1,240 = 860 left; the number is drawn in Manrope, so it's read from the description.
        onNode(hasContentDescription("860 kcal left")).assertExists()
        onNode(hasText("kcal left")).assertExists()
        onNode(hasContentDescription("Scan")).assertDoesNotExist()
    }

    @Test fun wideAddsMacrosTallAddsButtons() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(DpSize(300.dp, 120.dp))
        provideComposable { CaloriesContent(day, WidgetStyle()) }
        onNode(hasText("Protein")).assertExists()
        onNode(hasContentDescription("Scan")).assertDoesNotExist()
    }

    @Test fun tallCaloriesWidgetHasLogScanPhoto() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(DpSize(300.dp, 200.dp))
        provideComposable { CaloriesContent(day, WidgetStyle()) }
        onNode(hasContentDescription("Log")).assertExists()
        onNode(hasContentDescription("Scan")).assertExists()
        onNode(hasContentDescription("Photo meal")).assertExists()
    }

    @Test fun overBudgetSaysOver() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(CaloriesWidget.SMALL)
        val over = day.copy(totals = DayTotals(emptyMap(), eatenKcal = 2_300.0, budgetKcal = 2_100, exerciseKcal = 0.0))
        provideComposable { CaloriesContent(over, WidgetStyle()) }
        onNode(hasContentDescription("200 kcal over")).assertExists()
    }

    @Test fun waterWidgetShowsAmountThenUndo() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(WaterWidget.SMALL)
        provideComposable { WaterContent(day, WidgetStyle(), undoId = null, undoUntil = 0L) }
        onNode(hasContentDescription("Water today")).assertExists()
        // 1,250 ml in US units, with the short "oz" so it fits the 1x1 button.
        onNode(hasText("42 oz")).assertExists()
        onNode(hasText("fl oz")).assertDoesNotExist()
    }

    @Test fun wideWaterWidgetShowsTheGoalBesideTheGauge() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(WaterWidget.WIDE)
        provideComposable { WaterContent(day, WidgetStyle(), undoId = null, undoUntil = 0L) }
        onNode(hasText("Water")).assertExists()
        onNode(hasText("of 85 oz")).assertExists()
    }

    @Test fun waterWidgetOffersUndoRightAfterATap() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(WaterWidget.SMALL)
        provideComposable { WaterContent(day, WidgetStyle(), undoId = "w1", undoUntil = System.currentTimeMillis() + 60_000) }
        onNode(hasText("Undo")).assertExists()
    }

    @Test fun styleReadsBackFromWidgetState() {
        val prefs = mutablePreferencesOf(WidgetStyle.BACKGROUND to 0.25f, WidgetStyle.THEME to "DARK")
        assertThat(WidgetStyle.from(prefs)).isEqualTo(WidgetStyle(0.25f, WidgetTheme.DARK))
        assertThat(WidgetStyle.from(mutablePreferencesOf())).isEqualTo(WidgetStyle())
    }

    @Test fun firstBuildsTextColorBecomesTheMatchingWidgetColor() {
        // The first build stored the text color: light text is a Dark widget.
        val old = mutablePreferencesOf(stringPreferencesKey("text") to "LIGHT")
        assertThat(WidgetStyle.from(old).theme).isEqualTo(WidgetTheme.DARK)
    }
}
