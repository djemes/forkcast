package app.forkcast.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import app.forkcast.R

/** Settings list in DESIGN.md §7 order. Groups light up as their phase lands. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProfileGoals: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenFoodDatabases: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.settings), onBack = onBack) {
        SettingsItem(R.string.settings_profile_goals, onOpenProfileGoals)
        ComingSoonItem(R.string.settings_daily_targets)
        ComingSoonItem(R.string.settings_meals)
        SettingsItem(R.string.settings_notifications, onOpenNotifications)
        SettingsItem(R.string.settings_ai, onOpenAi)
        SettingsItem(R.string.settings_food_databases, onOpenFoodDatabases)
        ComingSoonItem(R.string.settings_health_connect)
        SettingsItem(R.string.settings_backup_data, onOpenBackup)
        SettingsItem(R.string.settings_appearance, onOpenAppearance)
        SettingsItem(R.string.settings_about, onOpenAbout)
    }
}

@Composable
private fun SettingsItem(@StringRes title: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ComingSoonItem(@StringRes title: Int) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(R.string.coming_soon)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier
            .alpha(0.6f)
            .semantics { disabled() },
    )
}
