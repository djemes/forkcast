package app.forkcast.ui.trends

import app.forkcast.ui.format.unitLabels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.ui.common.rememberReveal
import app.forkcast.ui.common.rememberFill
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.TrendsData
import app.forkcast.core.data.repository.TrendsRepository
import app.forkcast.core.domain.TrendPoint
import app.forkcast.core.domain.DailyValues
import app.forkcast.core.domain.Trends
import app.forkcast.core.domain.WeighIn
import app.forkcast.core.domain.WeightTrend
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.charts.CaloriesChart
import app.forkcast.ui.charts.WeightTrendChart
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.settings.SettingsScaffold
import app.forkcast.ui.theme.ForkcastTheme
import app.forkcast.ui.today.formatNutrient
import app.forkcast.ui.today.nutrientName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything on the Trends screen for one period, computed once per data change. */
data class TrendsUiState(
    val period: Trends.Period,
    val today: LocalDate,
    val units: UnitPrefs,
    val days: List<Trends.Day>,
    val calories: Trends.Calories?,
    val weeks: List<Trends.WeekAverage>,
    val macros: Trends.Macros?,
    val nutrients: List<Trends.NutrientLine>,
    val logging: Trends.Logging,
    /** The trend over the period (seeded by all earlier weigh-ins), empty without any weigh-in. */
    val weight: List<TrendPoint>,
    val hasWeighIns: Boolean,
    val weeklyRateKg: Double?,
    val goalKg: Double?,
    val projection: Trends.GoalProjection?,
    val timing: app.forkcast.core.domain.MealTiming.Insights? = null,
    /** Meal id → name, for the timing card. */
    val mealNames: Map<String, String> = emptyMap(),
) {
    val anyLogged: Boolean get() = days.any { it.nutrients != null }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrendsViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    trends: TrendsRepository,
    settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val period = savedState.getStateFlow(PERIOD, Trends.Period.WEEK.name)

    val state: StateFlow<TrendsUiState?> = combine(
        period.flatMapLatest { trends.observe(Trends.Period.valueOf(it)) },
        settings.settings,
    ) { data, s -> build(data, s.units ?: UnitFormatter.defaultsFor(Locale.getDefault())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPeriod(p: Trends.Period) { savedState[PERIOD] = p.name }

    private fun build(d: TrendsData, units: UnitPrefs): TrendsUiState {
        val targets = d.targets?.targets.orEmpty()
        val range = Trends.range(d.period, d.today)
        val trend = WeightTrend.daily(d.weights.map { WeighIn(it.timestamp, it.kg) }, clock.zone, d.today)
        val inPeriod = trend.filter { it.date in range }
        val rate = WeightTrend.weeklyRateKg(trend)
        val goalKg = d.goal?.takeIf { it.goalType != GoalType.MAINTAIN }?.targetWeightKg
        return TrendsUiState(
            period = d.period,
            today = d.today,
            units = units,
            days = d.days,
            calories = Trends.calories(d.days, d.today),
            weeks = if (d.period == Trends.Period.WEEK) emptyList() else Trends.weeklyAverages(d.days, d.today),
            macros = Trends.macros(d.days, d.today, targets),
            nutrients = Trends.nutrientReport(d.days, d.today, targets, d.coverage),
            logging = Trends.logging(d.loggedDates, range, d.today),
            weight = inPeriod,
            hasWeighIns = trend.isNotEmpty(),
            weeklyRateKg = rate,
            goalKg = goalKg,
            projection = goalKg?.let { g -> trend.lastOrNull()?.let { Trends.projectGoal(it.trendKg, rate, g, d.today) } },
            timing = d.timing,
            mealNames = d.mealSlots.associate { it.id to it.name },
        )
    }

    private companion object {
        const val PERIOD = "period"
    }
}

@Composable
fun TrendsRoute(onBack: () -> Unit, onOpenDay: (LocalDate) -> Unit, viewModel: TrendsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TrendsScreen(state, onBack, viewModel::setPeriod, onOpenDay)
}

/** Trends (SPEC §4.10, DESIGN.md §7): one card per question, a chart only where it helps. */
@Composable
fun TrendsScreen(
    state: TrendsUiState?,
    onBack: () -> Unit,
    onPeriod: (Trends.Period) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.trends_title), onBack = onBack) {
        val s = state ?: return@SettingsScaffold
        val f = UnitFormatter(s.units, LocalLocale.current.platformLocale, labels = unitLabels())
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PeriodPicker(s.period, onPeriod)
            CaloriesCard(s, f, onOpenDay)
            WeightCard(s, f)
            if (s.anyLogged) {
                MealTimingCard(s, f)
                s.macros?.let { MacrosCard(it, s, f) }
                if (s.nutrients.isNotEmpty()) NutrientsCard(s.nutrients, f)
            }
            LoggingCard(s.logging)
        }
    }
}

/**
 * When you eat (owner, 2026-10-23): each meal's usual time and how much it moves, the eating
 * window, calories after 8 pm, and whether late eating differs on days over the budget.
 */
@Composable
private fun MealTimingCard(s: TrendsUiState, f: UnitFormatter) {
    Card(R.string.timing_title) {
        val t = s.timing
        if (t == null) {
            val n = app.forkcast.core.domain.MealTiming.MIN_DAYS
            Note(pluralStringResource(R.plurals.timing_not_enough, n, n))
            return@Card
        }
        t.meals.forEach { m ->
            Line(
                s.mealNames[m.mealSlotId].orEmpty(),
                stringResource(R.string.timing_meal_value, app.forkcast.ui.common.mealTimeText(m.medianMinute), m.spreadMinutes),
            )
        }
        Line(
            stringResource(R.string.timing_window),
            stringResource(
                R.string.timing_window_value,
                app.forkcast.ui.common.mealTimeText(t.windowStartMinute),
                app.forkcast.ui.common.mealTimeText(t.windowEndMinute),
                f.number(((t.windowEndMinute - t.windowStartMinute + 24 * 60) % (24 * 60)) / 60.0, 1),
            ),
        )
        Line(
            stringResource(R.string.timing_late, app.forkcast.ui.common.mealTimeText(app.forkcast.core.domain.MealTiming.LATE_MINUTE)),
            stringResource(R.string.timing_late_value, f.energy(t.lateKcalPerDay), (t.lateShare * 100).roundToInt()),
        )
        t.comparison?.let { c ->
            Note(
                stringResource(
                    R.string.timing_over_compare,
                    c.overDays, f.energy(c.lateKcalOver), c.otherDays, f.energy(c.lateKcalOther),
                    app.forkcast.ui.common.mealTimeText(app.forkcast.core.domain.MealTiming.LATE_MINUTE),
                ),
            )
        }
        Note(pluralStringResource(R.plurals.timing_based_on, t.days, t.days))
    }
}

@Composable
private fun PeriodPicker(period: Trends.Period, onPeriod: (Trends.Period) -> Unit) {
    val options = listOf(
        Trends.Period.WEEK to R.string.trends_week,
        Trends.Period.MONTH to R.string.trends_month,
        Trends.Period.QUARTER to R.string.trends_quarter,
    )
    val label = stringResource(R.string.trends_period_label)
    // Every segment as tall as the tallest, so "3 months" wrapping at 200 % keeps the pill whole.
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics { contentDescription = label }) {
        options.forEachIndexed { i, (p, text) ->
            SegmentedButton(
                selected = p == period,
                onClick = { onPeriod(p) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.fillMaxHeight(),
                icon = {},
            ) { Text(stringResource(text), maxLines = 2, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun Card(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** Label on the left, value on the right; wraps instead of truncating at 200 % font. */
@Composable
private fun Line(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
    }
}

@Composable
private fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

private fun dateText(date: LocalDate, skeleton: String, today: LocalDate): String {
    val locale = Locale.getDefault()
    val s = if (date.year == today.year) skeleton else "y$skeleton"
    return DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, s), locale).format(date)
}

@Composable
private fun CaloriesCard(s: TrendsUiState, f: UnitFormatter, onOpenDay: (LocalDate) -> Unit) {
    Card(R.string.trends_calories) {
        val c = s.calories
        if (!s.anyLogged) {
            Note(stringResource(R.string.trends_nothing))
            return@Card
        }
        if (c != null) {
            Line(stringResource(R.string.trends_avg_eaten), f.energy(c.avgKcal))
            Line(stringResource(R.string.trends_avg_allowance), f.energy(c.avgAllowanceKcal))
            val diff = c.avgDifferenceKcal.roundToInt()
            // Against half-logged days the difference would only say "you didn't log everything".
            if (c.completeOnly) Text(
                when {
                    diff <= -10 -> stringResource(R.string.trends_under, f.energy(-c.avgDifferenceKcal))
                    diff >= 10 -> stringResource(R.string.trends_over, f.energy(c.avgDifferenceKcal))
                    else -> stringResource(R.string.trends_on_budget)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ForkcastTheme.colors.pine,
            )
            if (c.completeOnly && c.daysOver > 0) Note(pluralStringResource(R.plurals.trends_days_over, c.daysOver, c.daysOver, c.daysAveraged))
        }
        var selected by rememberSaveable(s.period) { mutableStateOf<LocalDate?>(null) }
        CaloriesChart(
            days = s.days,
            today = s.today,
            f = f,
            description = stringResource(
                R.string.trends_calories_chart,
                c?.let { f.energy(it.avgKcal) } ?: "—",
                c?.let { f.energy(it.avgAllowanceKcal) } ?: "—",
            ),
            onSelect = { selected = it },
            modifier = Modifier.padding(top = 4.dp),
        )
        val day = selected?.let { d -> s.days.firstOrNull { it.date == d } }
        if (day == null) {
            Note(stringResource(R.string.trends_tap_bar))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(dateText(day.date, "EEEMMMd", s.today), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        day.kcal?.let { stringResource(R.string.trends_day_value, f.energy(it), f.energy(day.allowanceKcal)) }
                            ?: stringResource(R.string.trends_day_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onOpenDay(day.date) }) { Text(stringResource(R.string.trends_open_day)) }
            }
        }
        if (c != null) {
            Note(
                if (c.completeOnly) pluralStringResource(R.plurals.trends_averaged_complete, c.daysAveraged, c.daysAveraged)
                else pluralStringResource(R.plurals.trends_averaged_logged, c.daysAveraged, c.daysAveraged),
            )
        }
        if (s.weeks.size > 1) {
            Text(stringResource(R.string.trends_weekly), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            s.weeks.asReversed().forEach { w ->
                Line(
                    stringResource(R.string.trends_week_of, dateText(w.start, "MMMd", s.today)),
                    pluralStringResource(R.plurals.trends_week_days, w.days, f.energy(w.avgKcal), w.days),
                )
            }
        }
    }
}

@Composable
private fun WeightCard(s: TrendsUiState, f: UnitFormatter) {
    Card(R.string.trends_weight) {
        if (!s.hasWeighIns) {
            Note(stringResource(R.string.trends_weight_none))
            return@Card
        }
        val now = s.weight.lastOrNull()
        if (now != null) {
            WeightTrendChart(s.weight, f, stringResource(R.string.trends_weight_chart, f.weight(now.trendKg)))
            if (s.weight.none { it.rawKg != null }) Note(stringResource(R.string.trends_weight_none_in_period))
            Line(stringResource(R.string.trends_weight_trend), f.weight(now.trendKg))
        }
        s.weeklyRateKg?.let { rate ->
            val shown = if (abs(f.weightValue(rate)) < 0.05) f.weight(0.0) else (if (rate < 0) "−" else "+") + f.weight(abs(rate))
            Line(stringResource(R.string.trends_weight_rate), shown)
        }
        val goal = s.goalKg ?: return@Card
        Line(stringResource(R.string.trends_weight_goal), f.weight(goal))
        Note(
            when (val p = s.projection) {
                is Trends.GoalProjection.By -> stringResource(R.string.trends_goal_by, f.weight(goal), dateText(p.date, "MMMd", s.today))
                Trends.GoalProjection.TooFar -> stringResource(R.string.trends_goal_too_far)
                Trends.GoalProjection.NotMovingToward -> stringResource(R.string.trends_goal_not_toward, f.weight(goal))
                Trends.GoalProjection.Reached -> stringResource(R.string.trends_goal_reached)
                null -> stringResource(R.string.trends_goal_need_data)
            },
            ForkcastTheme.colors.pine,
        )
    }
}

@Composable
private fun MacrosCard(m: Trends.Macros, s: TrendsUiState, f: UnitFormatter) {
    val c = ForkcastTheme.colors
    Card(R.string.trends_macros) {
        Note(stringResource(R.string.trends_split_hint))
        m.split?.let { SplitBar(stringResource(R.string.trends_macros_you), it) }
        m.targetSplit?.let { SplitBar(stringResource(R.string.trends_macros_target), it) }
        val targets = s.nutrients.associateBy { it.key }
        listOf(
            Triple(R.string.macro_protein, m.avgProteinG, NutrientKey.PROTEIN_G),
            Triple(R.string.macro_carbs, m.avgCarbsG, NutrientKey.CARBS_G),
            Triple(R.string.macro_fat, m.avgFatG, NutrientKey.FAT_G),
        ).forEach { (label, avg, key) ->
            val target = targets[key]?.target?.amount
            Line(stringResource(label), if (target != null) stringResource(R.string.trends_macro_avg, f.grams(avg), f.grams(target)) else f.grams(avg))
        }
        // Legend: the colors are also named in each bar's text, so color is never the only signal.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(c.protein, stringResource(R.string.macro_protein))
            Legend(c.carbs, stringResource(R.string.macro_carbs))
            Legend(c.fat, stringResource(R.string.macro_fat))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(12.dp).height(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
    }
}

/** One stacked bar of protein / carbs / fat shares, with the numbers written out. */
@Composable
private fun SplitBar(label: String, split: Trends.MacroSplit) {
    val c = ForkcastTheme.colors
    val text = stringResource(R.string.trends_split, split.proteinPct.roundToInt(), split.carbsPct.roundToInt(), split.fatPct.roundToInt())
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        // Stacked rather than side by side, so neither squeezes the other at 200 % font.
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Grows in from the left the first time it scrolls into view.
        val reveal = rememberReveal("split-$label")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(12.dp)
                .then(reveal.modifier)
                .graphicsLayer {
                    scaleX = reveal.progress
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .clip(RoundedCornerShape(6.dp)),
        ) {
            listOf(split.proteinPct to c.protein, split.carbsPct to c.carbs, split.fatPct to c.fat).forEach { (pct, color) ->
                if (pct > 0.5) Box(Modifier.weight(pct.toFloat()).fillMaxHeight().background(color))
            }
        }
    }
}

@Composable
private fun NutrientsCard(lines: List<Trends.NutrientLine>, f: UnitFormatter) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    Card(R.string.trends_nutrients) {
        Note(stringResource(R.string.trends_nutrients_hint))
        val shown = if (showAll) lines else lines.take(COLLAPSED_NUTRIENTS)
        shown.forEachIndexed { i, line ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NutrientRow(line, f)
        }
        if (lines.size > COLLAPSED_NUTRIENTS) {
            TextButton(onClick = { showAll = !showAll }, modifier = Modifier.align(Alignment.End)) {
                Text(if (showAll) stringResource(R.string.trends_show_fewer) else stringResource(R.string.trends_show_all, lines.size))
            }
        }
    }
}

private const val COLLAPSED_NUTRIENTS = 6

/** Below this share of calories carrying a nutrient, its average is flagged as partial. */
private const val PARTIAL_COVERAGE = 0.6

@Composable
private fun NutrientRow(line: Trends.NutrientLine, f: UnitFormatter) {
    val c = ForkcastTheme.colors
    val (word, color) = when (line.standing) {
        Trends.Standing.UNDER -> stringResource(R.string.trends_nutrient_under) to c.apricotText
        Trends.Standing.OVER -> stringResource(R.string.trends_nutrient_over) to c.plum
        Trends.Standing.ON_TARGET -> stringResource(R.string.trends_nutrient_ok) to c.pine
        Trends.Standing.NO_DATA -> stringResource(R.string.trends_nutrient_no_data) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(nutrientName(line.key), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text(word, style = MaterialTheme.typography.labelLarge, color = color)
        }
        if (line.standing == Trends.Standing.NO_DATA) {
            Text(
                stringResource(R.string.trends_nutrient_no_data_hint, formatNutrient(line.key, line.target.amount, f)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        // Vitamins and minerals: % of the Daily Value too (owner, 2026-10-23); it's the same number unless the target was changed.
        val dv = DailyValues.percent(line.key, line.avg)
        val dvAmount = DailyValues.reference[line.key]
        Text(
            when {
                dv == null -> stringResource(R.string.trends_nutrient_value, formatNutrient(line.key, line.avg, f), formatNutrient(line.key, line.target.amount, f), (line.ratio * 100).roundToInt())
                dvAmount == line.target.amount -> stringResource(R.string.trends_nutrient_value_dv, formatNutrient(line.key, line.avg, f), formatNutrient(line.key, line.target.amount, f), dv)
                else -> stringResource(R.string.trends_nutrient_value_target_dv, formatNutrient(line.key, line.avg, f), formatNutrient(line.key, line.target.amount, f), (line.ratio * 100).roundToInt(), dv)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Progress toward the target, capped at twice it so one huge value doesn't flatten the rest.
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            // Fills in the first time it scrolls into view; a new period eases it to the new value.
            val fill = rememberFill((line.ratio / 2).toFloat().coerceIn(0f, 1f), "nutrient-${line.key}")
            Box(
                Modifier
                    .fillMaxSize()
                    .then(fill.modifier)
                    .drawBehind { drawRect(color, size = size.copy(width = size.width * fill.fraction)) },
            )
            // Target mark at the middle.
            Box(Modifier.fillMaxWidth(0.5f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface))
            }
        }
        if (line.coverage < PARTIAL_COVERAGE && line.standing == Trends.Standing.UNDER) {
            Text(
                stringResource(R.string.trends_nutrient_partial, (line.coverage * 100).roundToInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun LoggingCard(l: Trends.Logging) {
    Card(R.string.trends_logging) {
        Line(stringResource(R.string.trends_days_logged), stringResource(R.string.trends_days_logged_value, l.daysLogged, l.periodDays))
        Line(stringResource(R.string.trends_streak), pluralStringResource(R.plurals.trends_days, l.currentStreak, l.currentStreak))
        Line(stringResource(R.string.trends_longest), pluralStringResource(R.plurals.trends_days, l.longestStreak, l.longestStreak))
    }
}
