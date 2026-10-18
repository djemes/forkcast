package app.forkcast.ui.weight

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.core.domain.TrendPoint
import app.forkcast.core.domain.WeighIn
import app.forkcast.core.domain.WeightTrend
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.WeightEntry
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.DateField
import app.forkcast.ui.onboarding.NumberField
import app.forkcast.ui.onboarding.formatDate
import app.forkcast.ui.settings.SettingsScaffold
import app.forkcast.ui.theme.ForkcastTheme
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor

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
                WeightChart(state.trend, f)
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
                val day = e.timestamp.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(formatDate(day), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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

/** Weight changes are small: fit the y-axis to the data (with padding) instead of starting at zero. */
private object FitToWeights : CartesianLayerRangeProvider {
    private fun pad(minY: Double, maxY: Double) = maxOf(1.0, (maxY - minY) * 0.2)
    override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = floor(minY - pad(minY, maxY))
    override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = ceil(maxY + pad(minY, maxY))
}

/** Raw weigh-ins as dots, the smoothed trend as a line (SPEC §4.9, §5.5). x = days since the first point. */
@Composable
private fun WeightChart(points: List<TrendPoint>, f: UnitFormatter) {
    val producer = remember { CartesianChartModelProducer() }
    val first = points.first().date
    LaunchedEffect(points, f.prefs) {
        val raw = points.filter { it.rawKg != null }
        producer.runTransaction {
            lineModel {
                series(x = raw.map { it.date.toEpochDay() - first.toEpochDay() }, y = raw.map { f.weightValue(it.rawKg!!) })
                series(x = points.map { it.date.toEpochDay() - first.toEpochDay() }, y = points.map { f.weightValue(it.trendKg) })
            }
        }
    }
    val pine = ForkcastTheme.colors.pine
    val dots = MaterialTheme.colorScheme.onSurfaceVariant
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    val description = stringResource(R.string.weight_chart_description)
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                LineCartesianLayer.LineProvider.series(
                    // Weigh-ins: points only, no connecting stroke.
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
                        areaFill = null,
                        pointProvider = LineCartesianLayer.PointProvider.single(
                            LineCartesianLayer.Point(rememberShapeComponent(Fill(dots), CircleShape), 6.dp),
                        ),
                    ),
                    // Trend line.
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(Fill(pine)),
                        areaFill = null,
                    ),
                ),
                rangeProvider = FitToWeights,
            ),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = CartesianValueFormatter { _, x, _ -> first.plusDays(x.toLong()).format(dateFormat) },
            ),
        ),
        modelProducer = producer,
        modifier = Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = description },
    )
}
