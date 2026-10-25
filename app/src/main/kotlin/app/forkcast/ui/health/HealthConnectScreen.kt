package app.forkcast.ui.health

import app.forkcast.ui.format.unitLabels
import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.data.health.HcAvailability
import app.forkcast.core.data.health.HealthError
import app.forkcast.core.data.health.HealthPermissions
import app.forkcast.core.model.HealthSettings
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.settings.SettingsScaffold
import kotlinx.coroutines.launch

@Composable
fun HealthConnectRoute(onBack: () -> Unit, viewModel: HealthConnectViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        viewModel.onPermissionsResult(granted)
    }
    val s = state ?: return
    HealthConnectScreen(
        state = s,
        onRequest = { scope.launch { launcher.launch(viewModel.permissionsToRequest()) } },
        onUpdate = { viewModel.update(it) },
        onSyncNow = viewModel::syncNow,
        onUseHeight = { viewModel.useHeight(it) },
        onDisconnect = viewModel::disconnect,
        onBack = onBack,
    )
}

@Composable
fun HealthConnectScreen(
    state: HealthUiState,
    onRequest: () -> Unit,
    onUpdate: ((HealthSettings) -> HealthSettings) -> Unit,
    onSyncNow: () -> Unit,
    onUseHeight: (Double) -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val status = state.status
    val settings = status.settings
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(title = stringResource(R.string.settings_health_connect), onBack = onBack) {
        when (status.availability) {
            HcAvailability.UNAVAILABLE, HcAvailability.UPDATE_REQUIRED -> {
                val update = status.availability == HcAvailability.UPDATE_REQUIRED
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (update) R.string.hc_update_required else R.string.hc_unavailable), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { openStore(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (update) R.string.hc_update else R.string.hc_get))
                    }
                }
                return@SettingsScaffold
            }
            HcAvailability.AVAILABLE -> Unit
        }

        if (!settings.connected) {
            Text(
                stringResource(R.string.hc_intro),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            StatusSection(state, onSyncNow, onRequest, context)
            state.heightSuggestion?.let { cm -> HeightCard(state, cm, onUseHeight) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        SectionTitle(R.string.hc_reads_title)
        ToggleRow(R.string.hc_read_weight, R.string.hc_read_weight_body, settings.readWeight) { on -> onUpdate { it.copy(readWeight = on) } }
        ToggleRow(R.string.hc_read_activity, R.string.hc_read_activity_body, settings.readActivity) { on -> onUpdate { it.copy(readActivity = on) } }
        SectionTitle(R.string.hc_writes_title)
        ToggleRow(R.string.hc_write_nutrition, R.string.hc_write_nutrition_body, settings.writeNutrition) { on -> onUpdate { it.copy(writeNutrition = on) } }
        ToggleRow(R.string.hc_write_hydration, R.string.hc_write_hydration_body, settings.writeHydration) { on -> onUpdate { it.copy(writeHydration = on) } }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hc_privacy_note), style = MaterialTheme.typography.bodyMedium)
            if (!settings.connected) {
                Text(stringResource(R.string.hc_extra_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = onRequest,
                    enabled = state.wanted.isNotEmpty() && !state.syncing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.hc_connect)) }
            } else {
                OutlinedButton(onClick = { confirmDisconnect = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.hc_disconnect))
                }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.hc_disconnect_title)) },
            text = { Text(stringResource(R.string.hc_disconnect_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) { Text(stringResource(R.string.hc_disconnect)) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StatusSection(state: HealthUiState, onSyncNow: () -> Unit, onRequest: () -> Unit, context: Context) {
    val status = state.status
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitleText(stringResource(R.string.hc_status_title))
        val synced = status.lastSyncAt?.let {
            stringResource(R.string.hc_status_synced, DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        } ?: stringResource(R.string.hc_status_never)
        Text(if (state.syncing) stringResource(R.string.hc_syncing) else synced, style = MaterialTheme.typography.bodyLarge)
        status.lastError?.let { error ->
            Text(
                stringResource(
                    when (error) {
                        HealthError.PERMISSION -> R.string.hc_error_permission
                        HealthError.RATE_LIMITED -> R.string.hc_error_rate_limited
                        HealthError.UNAVAILABLE -> R.string.hc_error_unavailable
                        HealthError.FAILED -> R.string.hc_error_failed
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val reads = status.settings.readWeight || status.settings.readActivity
        if (reads) {
            val background = HealthPermissions.READ_IN_BACKGROUND in status.granted
            val history = HealthPermissions.READ_HISTORY in status.granted
            if (status.backgroundSupported) {
                Text(stringResource(if (background) R.string.hc_background_on else R.string.hc_background_off), style = MaterialTheme.typography.bodyMedium)
            }
            if (status.historySupported) {
                Text(stringResource(if (history) R.string.hc_history_on else R.string.hc_history_off), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.missing.isNotEmpty()) {
            Text(
                stringResource(R.string.hc_missing, state.missing.map { permissionLabel(it) }.sorted().joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.hc_allow_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSyncNow, enabled = !state.syncing, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.hc_sync_now)) }
            if (state.missing.isNotEmpty()) {
                OutlinedButton(onClick = onRequest, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.hc_allow)) }
            }
            TextButton(onClick = { openHealthConnect(context) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.hc_open)) }
        }
    }
}

@Composable
private fun HeightCard(state: HealthUiState, cm: Double, onUseHeight: (Double) -> Unit) {
    val labels = unitLabels()
    val units = remember(state.units, labels) { UnitFormatter(state.units, labels = labels) }
    val profile = state.profile ?: return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.hc_height_title, units.height(cm)), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.hc_height_body, units.height(profile.heightCm)), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onUseHeight(cm) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.hc_height_use, units.height(cm)))
            }
        }
    }
}

@Composable
private fun permissionLabel(permission: String): String = stringResource(
    when (permission) {
        HealthPermissions.READ_WEIGHT -> R.string.hc_perm_weight
        HealthPermissions.READ_BODY_FAT -> R.string.hc_perm_body_fat
        HealthPermissions.READ_HEIGHT -> R.string.hc_perm_height
        HealthPermissions.READ_STEPS -> R.string.hc_perm_steps
        HealthPermissions.READ_ACTIVE_CALORIES -> R.string.hc_perm_active_calories
        HealthPermissions.READ_TOTAL_CALORIES -> R.string.hc_perm_total_calories
        HealthPermissions.READ_EXERCISE -> R.string.hc_perm_exercise
        HealthPermissions.WRITE_NUTRITION -> R.string.hc_perm_nutrition
        HealthPermissions.WRITE_HYDRATION -> R.string.hc_perm_hydration
        HealthPermissions.READ_HISTORY -> R.string.hc_perm_history
        else -> R.string.hc_perm_background
    },
)

@Composable
private fun SectionTitle(title: Int) = SectionTitleText(stringResource(title), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))

@Composable
private fun SectionTitleText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
}

@Composable
private fun ToggleRow(title: Int, body: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
    }
}

private fun openHealthConnect(context: Context) {
    try {
        context.startActivity(HealthConnectClient.getHealthConnectManageDataIntent(context))
    } catch (_: ActivityNotFoundException) {
        openStore(context)
    }
}

/** Health Connect's Play Store page (Android 13 and older install it as an app). */
private fun openStore(context: Context) {
    val uri = "market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE".toUri()))
    }
}

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
