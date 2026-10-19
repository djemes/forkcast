package app.forkcast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.NutritionConstants
import app.forkcast.core.model.Sex
import app.forkcast.core.model.UnitPrefs
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.onboarding.NumberField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class WaterSettingsState(val units: UnitPrefs, val targetMl: Double?, val defaultMl: Double) {
    val effectiveMl: Double get() = targetMl ?: defaultMl
}

@HiltViewModel
class WaterSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    profiles: ProfileRepository,
) : ViewModel() {
    val state: StateFlow<WaterSettingsState?> = combine(settings.settings, profiles.profile) { s, p ->
        WaterSettingsState(
            units = s.units ?: UnitFormatter.defaultsFor(Locale.getDefault()),
            targetMl = s.waterTargetMl,
            defaultMl = if (p?.sex == Sex.FEMALE) NutritionConstants.WATER_TARGET_FEMALE_ML else NutritionConstants.WATER_TARGET_MALE_ML,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setTarget(ml: Double?) = viewModelScope.launch { settings.setWaterTargetMl(ml) }
}

/** Settings › Water: the daily goal (SPEC §4.9: default 2.5 L men / 2.0 L women, editable). */
@Composable
fun WaterSettingsRoute(onBack: () -> Unit, viewModel: WaterSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(title = stringResource(R.string.settings_water), onBack = onBack) {
        val s = state ?: return@SettingsScaffold
        val f = remember(s.units) { UnitFormatter(s.units) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(
                initial = UnitFormatter.trimNumber(f.waterValue(s.effectiveMl), 0),
                label = stringResource(R.string.water_goal_field, f.waterUnitLabel),
                onValue = { v -> v?.takeIf { it > 0 }?.let { viewModel.setTarget(f.waterToMl(it)) } },
                // Re-seed only when reset to the default, not on every keystroke.
                key = s.targetMl == null,
            )
            Text(
                stringResource(R.string.water_goal_body, f.waterLiters(s.defaultMl)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.targetMl != null) {
                OutlinedButton(onClick = { viewModel.setTarget(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.water_goal_reset, f.waterLiters(s.defaultMl)))
                }
            }
        }
    }
}
