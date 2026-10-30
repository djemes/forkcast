package app.forkcast.ui.today

import app.forkcast.ui.format.formatMinute
import app.forkcast.ui.format.clockFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.domain.BudgetSheet
import app.forkcast.core.domain.DailyTargets
import app.forkcast.core.domain.DayTotals
import app.forkcast.core.domain.Forecast
import app.forkcast.core.model.NutrientKey
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.theme.ForkcastTheme
import kotlin.math.abs

/**
 * "Today's budget" (owner, 2026-10-24; DESIGN.md §5): what opens from a tap on the ring. Three
 * short parts, readable at a glance: how the number is built (and calories per meal), the outlook
 * (the day's forecast, the week so far), and what fits in what's left from the user's own foods.
 * Neutral wording throughout; no AI, works offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayBudgetSheet(
    state: TodayUiState,
    totals: DayTotals,
    targets: DailyTargets,
    f: UnitFormatter,
    onDismiss: () -> Unit,
    onLogFit: (FitSuggestion, String) -> Unit,
) {
    val equation = BudgetSheet.equation(targets, totals)
    val mealForNow = state.defaultMealId ?: state.loggableSlots.firstOrNull()?.id
    val mealName = state.meals.firstOrNull { it.slot.id == mealForNow }?.slot?.name.orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Text(
                if (state.isToday) stringResource(R.string.budget_sheet_title) else stringResource(R.string.budget_sheet_title_day, shortDayLabel(state.date, state.today)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Equation(equation, f)
            MealBars(state.meals.filter { it.kcal > 0 }, totals.allowanceKcal, f)
            Outlook(state.forecast, state.forecastFromMinute, state.weekSoFar, totals, f)
            if (state.fits.isNotEmpty() && mealForNow != null) {
                Fits(state, totals, targets, f, mealName) { onLogFit(it, mealForNow) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() },
    )
}

/** maintenance − deficit = budget, + activity − eaten = left, one line each so it reads like a sum. */
@Composable
private fun Equation(e: BudgetSheet.Equation, f: UnitFormatter) {
    val colors = ForkcastTheme.colors
    Column(Modifier.padding(top = 8.dp)) {
        val maintenance = e.maintenanceKcal
        if (maintenance != null) {
            Line(
                stringResource(if (e.adaptive) R.string.budget_maintenance_adaptive else R.string.budget_maintenance_formula),
                f.energy(maintenance.toDouble()),
            )
            e.goalDeltaKcal?.let { delta ->
                Line(
                    stringResource(if (delta > 0) R.string.budget_deficit else R.string.budget_surplus),
                    signed(if (delta > 0) "−" else "+", abs(delta), f),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        Line(stringResource(if (e.customBudget) R.string.budget_custom else R.string.budget_base), f.energy(e.budgetKcal.toDouble()), strong = true)
        if (e.activityBonusKcal > 0) {
            Line(stringResource(R.string.budget_activity), signed("+", e.activityBonusKcal, f), valueColor = colors.pine)
        }
        Line(stringResource(R.string.budget_eaten), signed("−", e.eatenKcal, f))
        HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
        val over = e.remainingKcal < 0
        Line(
            stringResource(if (over) R.string.budget_over else R.string.budget_left),
            f.energy(abs(e.remainingKcal).toDouble()),
            strong = true,
            valueColor = if (over) colors.plum else colors.pine,
        )
    }
}

private fun signed(sign: String, kcal: Int, f: UnitFormatter) = "$sign ${f.energy(kcal.toDouble())}"

@Composable
private fun Line(label: String, value: String, strong: Boolean = false, valueColor: Color = Color.Unspecified) {
    val style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp)) {
        Text(label, style = style, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(value, style = style, fontWeight = if (strong) FontWeight.SemiBold else null, color = valueColor)
    }
}

/** Calories per meal as small bars, each against the day's allowance. */
@Composable
private fun MealBars(meals: List<MealSection>, allowanceKcal: Double, f: UnitFormatter) {
    if (meals.isEmpty()) return
    SectionTitle(stringResource(R.string.budget_by_meal))
    val other = stringResource(R.string.meal_other)
    meals.forEach { m ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp)) {
            Text(
                m.slot.name.ifBlank { other },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(96.dp),
            )
            val fraction = if (allowanceKcal > 0) (m.kcal / allowanceKcal).toFloat().coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(ForkcastTheme.colors.pine))
            }
            Text(f.energy(m.kcal), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The day's forecast (after 7+ logged days) and the week so far; hidden when there's neither. */
@Composable
private fun Outlook(forecast: Forecast.Outlook?, fromMinute: Int, week: BudgetSheet.WeekSoFar?, totals: DayTotals, f: UnitFormatter) {
    if (forecast == null && week == null) return
    SectionTitle(stringResource(R.string.budget_outlook))
    if (forecast != null && forecast.status != Forecast.Status.OVER) {
        Text(
            stringResource(R.string.budget_forecast, timeLabel(fromMinute), f.energy(forecast.forecastTotalKcal), f.energy(totals.allowanceKcal)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (week != null) {
        val amount = f.energy(abs(week.averageDifferenceKcal))
        Text(
            when {
                week.onBudget -> pluralStringResource(R.plurals.budget_week_on, week.days, week.days)
                week.averageDifferenceKcal < 0 -> pluralStringResource(R.plurals.budget_week_under, week.days, amount, week.days)
                else -> pluralStringResource(R.plurals.budget_week_over, week.days, amount, week.days)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Up to four of the user's own foods or saved meals that fit; one tap logs to the meal for now. */
@Composable
private fun Fits(state: TodayUiState, totals: DayTotals, targets: DailyTargets, f: UnitFormatter, mealName: String, onLog: (FitSuggestion) -> Unit) {
    SectionTitle(stringResource(R.string.budget_fits_title, f.energy(totals.remainingKcal)))
    if (state.proteinBehind) {
        val left = (targets.targets[NutrientKey.PROTEIN_G]?.amount ?: 0.0) - totals[NutrientKey.PROTEIN_G]
        Text(stringResource(R.string.budget_fits_protein, f.grams(left)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.fits.forEach { fit ->
        val c = fit.candidate
        val label = stringResource(R.string.budget_fit_add, c.name, mealName)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = label, role = Role.Button) { onLog(fit) }
                .padding(vertical = 4.dp),
        ) {
            Icon(
                painterResource(if (c.savedMeal) R.drawable.ic_star else R.drawable.ic_history),
                contentDescription = if (c.savedMeal) stringResource(R.string.budget_saved_meal) else null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.budget_fit_line, f.energy(c.kcal), f.grams(c.proteinG)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = ForkcastTheme.colors.pine)
        }
    }
}

@Composable
private fun timeLabel(minuteOfDay: Int): String = clockFormatter().formatMinute(minuteOfDay)
