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
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import app.forkcast.core.model.WaterContainer
import app.forkcast.ui.theme.ForkcastTheme
import java.util.Locale
import javax.inject.Inject

data class WaterSettingsState(
    val units: UnitPrefs,
    val targetMl: Double?,
    val defaultMl: Double,
    /** The glass; the other sizes are in [containers]. */
    val glassMl: Double?,
    val containers: app.forkcast.core.model.WaterContainers = app.forkcast.core.model.WaterContainers(),
) {
    val effectiveMl: Double get() = targetMl ?: defaultMl
}

@HiltViewModel
class WaterSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    profiles: ProfileRepository,
) : ViewModel() {
    val state: StateFlow<WaterSettingsState?> = combine(settings.settings, profiles.profile) { s, p ->
        val units = s.units ?: UnitFormatter.defaultsFor(Locale.getDefault())
        WaterSettingsState(
            units = units,
            targetMl = s.waterTargetMl,
            defaultMl = app.forkcast.core.domain.WaterMath.defaultTargetMl(p?.sex),
            glassMl = s.waterGlassMl,
            containers = s.waterContainers,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setTarget(ml: Double?) = viewModelScope.launch { settings.setWaterTargetMl(ml) }
    /** A container's size in ml; null goes back to the default. */
    fun setContainer(c: WaterContainer, ml: Double?) = viewModelScope.launch {
        settings.update {
            val w = it.waterContainers
            when (c) {
                WaterContainer.GLASS -> it.copy(waterGlassMl = ml)
                WaterContainer.CAN -> it.copy(waterContainers = w.copy(canMl = ml))
                WaterContainer.BOTTLE -> it.copy(waterContainers = w.copy(bottleMl = ml))
                WaterContainer.TUMBLER -> it.copy(waterContainers = w.copy(tumblerMl = ml))
            }
        }
    }
}

/** Settings › Water: the daily goal (SPEC §4.9: default 2.5 L men / 2.0 L women, editable) and the four container sizes. */
@Composable
fun WaterSettingsRoute(onBack: () -> Unit, viewModel: WaterSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WaterSettingsScreen(state, onBack, onTarget = viewModel::setTarget, onContainer = viewModel::setContainer)
}

@Composable
fun WaterSettingsScreen(
    state: WaterSettingsState?,
    onBack: () -> Unit,
    onTarget: (Double?) -> Unit,
    onContainer: (WaterContainer, Double?) -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.settings_water), onBack = onBack) {
        val s = state ?: return@SettingsScaffold
        val f = remember(s.units) { UnitFormatter(s.units) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(
                initial = UnitFormatter.trimNumber(f.waterValue(s.effectiveMl), 0),
                label = stringResource(R.string.water_goal_field, f.waterUnitLabel),
                onValue = { v -> v?.takeIf { it > 0 }?.let { onTarget(f.waterToMl(it)) } },
                // Re-seed only when reset to the default, not on every keystroke.
                key = s.targetMl == null,
            )
            Text(
                stringResource(R.string.water_goal_body, f.waterLiters(s.defaultMl)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.targetMl != null) {
                OutlinedButton(onClick = { onTarget(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.water_goal_reset, f.waterLiters(s.defaultMl)))
                }
            }
            // The four containers of Today's water drop, one layout each (owner, 2026-10-24), in the user's units.
            Text(stringResource(R.string.water_containers_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.water_containers_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val flOz = s.units.water == app.forkcast.core.model.WaterUnit.FL_OZ
            listOf(
                Triple(WaterContainer.GLASS, s.glassMl, R.string.water_glass_field),
                Triple(WaterContainer.CAN, s.containers.canMl, R.string.water_can_field),
                Triple(WaterContainer.BOTTLE, s.containers.bottleMl, R.string.water_bottle_field),
                Triple(WaterContainer.TUMBLER, s.containers.tumblerMl, R.string.water_tumbler_field),
            ).forEach { (c, ml, label) ->
                val default = app.forkcast.core.domain.WaterMath.defaultMl(c, flOz)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(app.forkcast.ui.today.containerIcon(c)), contentDescription = null, tint = ForkcastTheme.colors.protein)
                    NumberField(
                        initial = UnitFormatter.trimNumber(f.waterValue(ml ?: default), 0),
                        label = stringResource(label, f.waterUnitLabel),
                        onValue = { v -> v?.takeIf { it > 0 }?.let { onContainer(c, f.waterToMl(it)) } },
                        key = "${c.name}-${ml == null}",
                        modifier = Modifier.weight(1f),
                    )
                }
                if (ml != null) {
                    OutlinedButton(onClick = { onContainer(c, null) }, modifier = Modifier.padding(start = 36.dp).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.water_goal_reset, f.waterServing(default)))
                    }
                }
            }
        }
    }
}
