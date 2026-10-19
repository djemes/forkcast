package app.forkcast.ui.weight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.model.WeightSource
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.core.domain.TrendPoint
import app.forkcast.core.domain.WeighIn
import app.forkcast.core.domain.WeightTrend
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.WeightEntry
import app.forkcast.ui.charts.WeightTrendChart
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.DateField
import app.forkcast.ui.onboarding.NumberField
import app.forkcast.ui.onboarding.formatDate
import app.forkcast.ui.settings.SettingsScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import javax.inject.Inject

data class WeightUiState(
    val entries: List<WeightEntry>,
    val trend: List<TrendPoint>,
    val units: UnitPrefs,
    val today: LocalDate,
)

@HiltViewModel
class WeightViewModel @Inject constructor(
    private val weights: WeightRepository,
    settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    val state: StateFlow<WeightUiState?> = combine(weights.entries, settings.settings) { list, s ->
        WeightUiState(
            entries = list.sortedByDescending { it.timestamp },
            trend = WeightTrend.daily(list.map { WeighIn(it.timestamp, it.kg) }, clock.zone, LocalDate.now(clock)),
            units = s.units ?: UnitFormatter.defaultsFor(Locale.getDefault()),
            today = LocalDate.now(clock),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Today's entry gets the current time; past dates are stamped 7:00 (a typical morning weigh-in). */
    fun add(kg: Double, bodyFatPct: Double?, date: LocalDate) = viewModelScope.launch {
        val today = LocalDate.now(clock)
        val at = if (date == today) clock.instant() else date.atTime(LocalTime.of(7, 0)).atZone(clock.zone).toInstant()
        weights.add(kg, bodyFatPct, at)
    }

    fun delete(id: String) = viewModelScope.launch { weights.delete(id) }
}

@Composable
fun WeightRoute(onBack: () -> Unit, viewModel: WeightViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    state?.let { WeightScreen(it, onBack, viewModel::add, viewModel::delete) }
}

@Composable
fun WeightScreen(
    state: WeightUiState,
    onBack: () -> Unit,
    onAdd: (kg: Double, bodyFat: Double?, date: LocalDate) -> Unit,
    onDelete: (String) -> Unit,
) {
    val f = UnitFormatter(state.units, LocalLocale.current.platformLocale)
    var weight by rememberSaveable { mutableStateOf<Double?>(null) }
    var bodyFat by rememberSaveable { mutableStateOf<Double?>(null) }
    var date by rememberSaveable { mutableStateOf(state.today) }
    // Bumped after saving so the input fields clear.
    var formKey by remember { mutableIntStateOf(0) }

    SettingsScaffold(title = stringResource(R.string.weight_log_title), onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            if (state.trend.size >= 2) {
                WeightTrendChart(state.trend, f, stringResource(R.string.weight_chart_description))
                Text(
                    stringResource(R.string.weight_trend_now, f.weight(state.trend.last().trendKg)),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Text(stringResource(R.string.weight_add_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    initial = "",
                    label = stringResource(R.string.weight_field, f.weightUnitLabel),
                    suffix = f.weightUnitLabel,
                    onValue = { weight = it?.let(f::weightToKg) },
                    key = "w$formKey",
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    initial = "",
                    label = stringResource(R.string.body_fat_field),
                    suffix = "%",
                    onValue = { bodyFat = it },
                    key = "bf$formKey",
                    modifier = Modifier.weight(1f),
                )
            }
            DateField(date, stringResource(R.string.weight_date), state.today, { date = it }, selectableTo = state.today)
            Button(
                onClick = {
                    weight?.let { onAdd(it, bodyFat, date) }
                    weight = null
                    bodyFat = null
                    date = state.today
                    formKey++
                },
                enabled = weight?.let { it in 25.0..350.0 } == true && bodyFat?.let { it in 1.0..75.0 } != false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.weight_add)) }

            if (state.entries.isNotEmpty()) {
                Text(stringResource(R.string.weight_history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
            }
            state.entries.forEach { e ->
                val local = e.timestamp.atZone(java.time.ZoneId.systemDefault())
                val day = local.toLocalDate()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(formatDate(day), style = MaterialTheme.typography.bodyLarge)
                        // Time and source, so a replaced manual weigh-in (SPEC §4.9) is easy to follow.
                        val time = app.forkcast.ui.settings.timeText(local.hour * 60 + local.minute)
                        Text(
                            if (e.source == WeightSource.HEALTH_CONNECT) stringResource(R.string.weight_from_health_connect, time) else time,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        listOfNotNull(f.weight(e.kg), e.bodyFatPct?.let { "${f.number(it, 1)} %" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(onClick = { onDelete(e.id) }) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.weight_delete, formatDate(day)))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
