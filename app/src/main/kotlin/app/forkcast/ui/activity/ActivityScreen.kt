package app.forkcast.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.forkcast.core.data.health.HealthRepository
import app.forkcast.core.data.repository.MaintenanceRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.ActivityBonus
import app.forkcast.core.domain.NutritionConstants
import app.forkcast.core.model.ActivityDay
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.formatDate
import app.forkcast.ui.settings.SettingsScaffold
import app.forkcast.ui.theme.ForkcastTheme
import app.forkcast.ui.today.exerciseName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

/** One day on the Activity screen: what devices reported and what it did to the budget. */
data class ActivityDayView(val date: LocalDate, val day: ActivityDay?, val bonus: ActivityBonus)

data class ActivityUiState(val units: UnitPrefs, val days: List<ActivityDayView>, val bonusOn: Boolean) {
    val selected: ActivityDayView? get() = days.firstOrNull()
    val recent: List<ActivityDayView> get() = days.drop(1)
}

@HiltViewModel
class ActivityViewModel @Inject constructor(
    savedState: SavedStateHandle,
    health: HealthRepository,
    maintenance: MaintenanceRepository,
    settings: SettingsRepository,
    clock: Clock,
) : ViewModel() {
    private val date: LocalDate = savedState.get<String>("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(clock)
    private val from = date.minusDays(RECENT_DAYS - 1L)

    val state: StateFlow<ActivityUiState?> = combine(
        health.observeActivityRange(from, date),
        maintenance.observeActivityBonuses(from, date),
        settings.settings,
    ) { days, bonuses, s ->
        val byDate = days.associateBy { it.date }
        ActivityUiState(
            units = s.units ?: UnitFormatter.defaultsFor(Locale.getDefault()),
            days = generateSequence(date) { it.minusDays(1) }.takeWhile { !it.isBefore(from) }
                .map { d -> ActivityDayView(d, byDate[d], bonuses[d] ?: ActivityBonus.NONE) }.toList(),
            bonusOn = s.activityBonus,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private companion object {
        const val RECENT_DAYS = 14
    }
}

@Composable
fun ActivityRoute(onBack: () -> Unit, viewModel: ActivityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    state?.let { ActivityScreen(it, onBack) }
}

/**
 * Activity detail (SPEC §4.2 item 7, owner 2026-10-19): what devices reported through Health
 * Connect, the usual day, the extra above it, and the part counted toward the budget.
 */
@Composable
fun ActivityScreen(state: ActivityUiState, onBack: () -> Unit) {
    val f = remember(state.units) { UnitFormatter(state.units) }
    SettingsScaffold(title = stringResource(R.string.activity_title), onBack = onBack) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (state.bonusOn) {
                    stringResource(
                        R.string.activity_explain,
                        (NutritionConstants.ACTIVITY_BONUS_SHARE * 100).toInt(),
                        f.energy(NutritionConstants.ACTIVITY_BONUS_CAP_KCAL),
                    )
                } else {
                    stringResource(R.string.activity_explain_off)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            state.selected?.let { DayDetail(it, f) }
            Text(stringResource(R.string.activity_source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.activity_recent),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
            state.recent.forEach { RecentRow(it, f) }
        }
    }
}

@Composable
private fun DayDetail(v: ActivityDayView, f: UnitFormatter) {
    val b = v.bonus
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(formatDate(v.date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        val day = v.day
        if (day == null) {
            Text(stringResource(R.string.activity_none), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Line(R.string.activity_row_steps, day.steps?.let { f.number(it.toDouble()) })
        Line(R.string.activity_row_active, day.activeKcal?.let { f.energy(it) })
        Line(
            R.string.activity_row_usual,
            b.usualKcal?.let { if (b.partialBaseline) stringResource(R.string.activity_usual_days, f.energy(it), b.baselineDays) else f.energy(it) }
                ?: stringResource(R.string.activity_not_yet),
        )
        if (b.usualKcal != null && day.activeKcal != null) {
            Line(R.string.activity_row_extra, f.energy(b.extraKcal))
            Line(R.string.activity_row_counted, if (b.enabled) "+${f.energy(b.bonusKcal)}" else "—", highlight = b.enabled && b.bonusKcal > 0)
        }
        if (day.sessions.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.activity_row_workouts), style = MaterialTheme.typography.titleSmall)
            day.sessions.sortedBy { it.start }.forEach { s ->
                Text(
                    listOfNotNull(
                        s.title?.takeIf { it.isNotBlank() } ?: exerciseName(s.exerciseType),
                        stringResource(R.string.activity_minutes, ((s.end - s.start) / 60_000).toInt()),
                        s.activeKcal?.let { f.energy(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Line(label: Int, value: String?, highlight: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value ?: "—",
            style = MaterialTheme.typography.labelLarge,
            color = if (highlight) ForkcastTheme.colors.pine else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/** A recent day: steps and active calories, then the extra and what counted. */
@Composable
private fun RecentRow(v: ActivityDayView, f: UnitFormatter) {
    val b = v.bonus
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDate(v.date), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (b.enabled && b.bonusKcal > 0) {
                Text("+${f.energy(b.bonusKcal)}", style = MaterialTheme.typography.labelLarge, color = ForkcastTheme.colors.pine)
            }
        }
        val day = v.day
        val workouts = day?.sessions?.sortedBy { it.start }?.map { s -> s.title?.takeIf { it.isNotBlank() } ?: exerciseName(s.exerciseType) }.orEmpty()
        Text(
            if (day == null) {
                stringResource(R.string.activity_no_data_day)
            } else {
                listOfNotNull(
                    day.steps?.let { stringResource(R.string.activity_steps, f.number(it.toDouble())) },
                    day.activeKcal?.let { stringResource(R.string.activity_active, f.energy(it)) },
                    workouts.takeIf { it.isNotEmpty() }?.joinToString(", "),
                ).joinToString(" · ")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
    }
}
