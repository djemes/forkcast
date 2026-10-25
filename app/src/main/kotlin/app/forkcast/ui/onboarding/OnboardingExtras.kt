package app.forkcast.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.data.backup.folder.FolderBackupRepository
import app.forkcast.core.data.health.HealthRepository
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which optional extras are already set up, so their cards can say "Ready". */
data class ExtrasState(
    val aiReady: Boolean = false,
    val healthConnected: Boolean = false,
    val backupChosen: Boolean = false,
)

/** Where the extras' buttons lead (each one also lives in Settings). */
data class ExtrasActions(
    val setUpAi: () -> Unit = {},
    val healthConnect: () -> Unit = {},
    val backup: () -> Unit = {},
)

@HiltViewModel
class OnboardingExtrasViewModel @Inject constructor(
    ai: AiRepository,
    health: HealthRepository,
    backup: FolderBackupRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<ExtrasState> = combine(ai.profiles, health.status, backup.status) { profiles, h, b ->
        ExtrasState(aiReady = profiles.any { it.profile.enabled }, healthConnected = h.connected, backupChosen = b.chosen)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExtrasState())

    init {
        viewModelScope.launch { health.refresh() }
    }

    fun notificationPermissionAsked() = viewModelScope.launch { settings.updateNotifications { it.copy(permissionAsked = true) } }
}

/**
 * The optional extras at the end of onboarding (roadmap 2.0 task 3.2): each card says what it
 * gives, what it needs, and where it lives in Settings. Notifications ask Android's permission
 * here, after the card's sentence; the others open their own screen.
 */
@Composable
fun OnboardingExtras(state: ExtrasState, actions: ExtrasActions, onNotificationsAsked: () -> Unit) {
    val context = LocalContext.current
    fun notificationsOn() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    var notifications by remember { mutableStateOf(notificationsOn()) }
    LifecycleResumeEffect(Unit) {
        notifications = notificationsOn()
        onPauseOrDispose { }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = notificationsOn()
        onNotificationsAsked()
    }

    Text(stringResource(R.string.onb_extras_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.onb_extras_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ExtraCard(
        icon = R.drawable.ic_photo_camera,
        title = R.string.onb_extra_ai_title,
        body = R.string.onb_extra_ai_body,
        needs = R.string.onb_extra_ai_needs,
        settingsName = R.string.settings_ai,
        action = R.string.onb_extra_set_up,
        ready = state.aiReady,
        onAction = actions.setUpAi,
    )
    ExtraCard(
        icon = R.drawable.ic_bolt,
        title = R.string.onb_extra_hc_title,
        body = R.string.onb_extra_hc_body,
        needs = R.string.onb_extra_hc_needs,
        settingsName = R.string.settings_health_connect,
        action = R.string.onb_extra_connect,
        ready = state.healthConnected,
        onAction = actions.healthConnect,
    )
    ExtraCard(
        icon = R.drawable.ic_history,
        title = R.string.onb_extra_backup_title,
        body = R.string.onb_extra_backup_body,
        needs = R.string.onb_extra_backup_needs,
        settingsName = R.string.settings_backup_data,
        action = R.string.onb_extra_choose_folder,
        ready = state.backupChosen,
        onAction = actions.backup,
    )
    // Before Android 13 there's nothing to ask: notifications are on unless turned off in Android.
    if (Build.VERSION.SDK_INT >= 33) {
        ExtraCard(
            icon = R.drawable.ic_schedule,
            title = R.string.onb_extra_notify_title,
            body = R.string.onb_extra_notify_body,
            needs = R.string.onb_extra_notify_needs,
            settingsName = R.string.settings_notifications,
            action = R.string.onb_extra_turn_on,
            ready = notifications,
            onAction = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
    }
}

@Composable
private fun ExtraCard(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes body: Int,
    @StringRes needs: Int,
    @StringRes settingsName: Int,
    @StringRes action: Int,
    ready: Boolean,
    onAction: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        }
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(needs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.onb_extra_later, stringResource(settingsName)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ready) {
            // Icon and word together: color is never the only signal.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.onb_extra_ready), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(action)) }
        }
    }
}
