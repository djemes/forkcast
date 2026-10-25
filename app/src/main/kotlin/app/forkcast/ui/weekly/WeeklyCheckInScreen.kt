package app.forkcast.ui.weekly

import app.forkcast.ui.format.unitLabels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.WeeklyCheckInRepository
import app.forkcast.core.domain.WeeklyCheckIn
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.settings.SettingsScaffold
import app.forkcast.ui.theme.ForkcastTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

data class WeeklyUiState(val summary: WeeklyCheckIn.Summary, val units: UnitPrefs, val canGoNext: Boolean)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class WeeklyCheckInViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val weekly: WeeklyCheckInRepository,
    settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val today get() = LocalDate.now(clock)

    /** The week on screen; null = the last full week before the check-in day (from settings). */
    private val start = MutableStateFlow(savedState.get<String>("start")?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    private var shown: LocalDate? = null

    val state: StateFlow<WeeklyUiState?> = combine(start, settings.settings) { s, set ->
        (s ?: WeeklyCheckIn.lastWeek(today, DayOfWeek.of(set.notifications.weeklyDay.coerceIn(1, 7))).start) to set
    }.mapLatest { (s, set) ->
        shown = s
        WeeklyUiState(weekly.summary(s), set.units ?: UnitFormatter.defaultsFor(Locale.getDefault()), canGoNext = !s.plusDays(7).isAfter(today))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun previous() { shown?.let { start.value = it.minusDays(7) } }

    /** Weeks that have ended (or are under way) only. */
    fun next() { shown?.takeIf { !it.plusDays(7).isAfter(today) }?.let { start.value = it.plusDays(7) } }
}

@Composable
fun WeeklyCheckInRoute(onBack: () -> Unit, viewModel: WeeklyCheckInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WeeklyCheckInScreen(state, onBack, viewModel::previous, viewModel::next)
}

/** Weekly check-in (owner, 2026-10-19): food, weight, maintenance and activity for one week. */
@Composable
fun WeeklyCheckInScreen(state: WeeklyUiState?, onBack: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    SettingsScaffold(title = stringResource(R.string.weekly_title), onBack = onBack) {
        val s = state ?: return@SettingsScaffold
        val w = s.summary
        val labels = unitLabels()
        val f = remember(s.units, labels) { UnitFormatter(s.units, labels = labels) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            IconButton(onClick = onPrevious) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.weekly_previous)) }
            Text(
                stringResource(R.string.weekly_range, shortDate(w.start), shortDate(w.end)),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = onNext, enabled = s.canGoNext) { Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.weekly_next)) }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (w.daysLogged == 0) {
                Text(stringResource(R.string.weekly_nothing), style = MaterialTheme.typography.bodyLarge)
            }
            Section(R.string.weekly_food) {
                Line(R.string.weekly_days_logged, stringResource(R.string.weekly_days_value, w.daysLogged, w.completeDays))
                w.avgIntakeKcal?.let { Line(if (w.averagedOverComplete) R.string.weekly_avg_eaten_complete else R.string.weekly_avg_eaten, f.energy(it)) }
                w.avgAllowanceKcal?.let { Line(R.string.weekly_avg_budget, f.energy(it)) }
                w.avgDifferenceKcal?.let { d ->
                    val r = d.roundToInt()
                    Note(
                        when {
                            r <= -10 -> stringResource(R.string.weekly_under, f.energy(-d))
                            r >= 10 -> stringResource(R.string.weekly_over, f.energy(d))
                            else -> stringResource(R.string.weekly_on_budget)
                        },
                    )
                }
                if (!w.averagedOverComplete && w.daysLogged > 0) Note(stringResource(R.string.weekly_none_complete))
            }
            Section(R.string.weekly_weight) {
                Line(R.string.weekly_weigh_ins, w.weighIns.toString())
                w.trendChangeKg?.let { Line(R.string.weekly_trend, signedWeight(it, f)) }
                w.plannedKgPerWeek?.let { Line(R.string.weekly_plan, signedWeight(it, f)) }
                Note(
                    stringResource(
                        when (w.pace) {
                            WeeklyCheckIn.Pace.AHEAD -> R.string.weekly_pace_ahead
                            WeeklyCheckIn.Pace.ON_PACE -> R.string.weekly_pace_on
                            WeeklyCheckIn.Pace.BEHIND -> if (w.plannedKgPerWeek == null) R.string.weekly_pace_drift else R.string.weekly_pace_behind
                            WeeklyCheckIn.Pace.NO_DATA -> R.string.weekly_pace_no_data
                        },
                    ),
                )
            }
            Section(R.string.weekly_maintenance) {
                val m = w.maintenanceKcal
                if (m == null) {
                    Note(stringResource(R.string.weekly_maintenance_none))
                } else {
                    Line(R.string.weekly_maintenance_now, f.energy(m))
                    w.maintenanceChangeKcal?.takeIf { abs(it) >= 1 }?.let { c ->
                        Line(R.string.weekly_maintenance_change, (if (c > 0) "+" else "−") + f.energy(abs(c)))
                    }
                }
            }
            Section(R.string.weekly_activity) {
                if (w.avgSteps == null && w.avgActiveKcal == null) {
                    Note(stringResource(R.string.weekly_activity_none))
                } else {
                    w.avgSteps?.let { Line(R.string.weekly_avg_steps, f.number(it.toDouble())) }
                    w.avgActiveKcal?.let { Line(R.string.weekly_avg_active, f.energy(it)) }
                    Line(R.string.weekly_bonus_total, "+" + f.energy(w.totalBonusKcal))
                }
            }
        }
    }
}

private fun signedWeight(kg: Double, f: UnitFormatter): String = when {
    abs(f.weightValue(kg)) < 0.05 -> f.weight(0.0)
    kg < 0 -> "−" + f.weight(-kg)
    else -> "+" + f.weight(kg)
}

/** "Oct 12": the year only matters for old weeks, and the range header has to fit at 200 %. */
private fun shortDate(d: LocalDate): String {
    val locale = Locale.getDefault()
    val skeleton = if (d.year == LocalDate.now().year) "MMMd" else "yMMMd"
    return DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(d)
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun Line(label: Int, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = ForkcastTheme.colors.pine)
}
