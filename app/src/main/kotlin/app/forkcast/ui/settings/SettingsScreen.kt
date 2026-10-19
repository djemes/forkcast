package app.forkcast.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.R

/** Where each settings entry leads. */
data class SettingsActions(
    val profile: () -> Unit = {},
    val goalBudget: () -> Unit = {},
    val dailyTargets: () -> Unit = {},
    val meals: () -> Unit = {},
    val water: () -> Unit = {},
    val units: () -> Unit = {},
    val healthConnect: () -> Unit = {},
    val ai: () -> Unit = {},
    val foodDatabases: () -> Unit = {},
    val notifications: () -> Unit = {},
    val appearance: () -> Unit = {},
    val backup: () -> Unit = {},
    val about: () -> Unit = {},
)

/**
 * Settings, grouped the way a health tracker is used (owner, 2026-10-19): you and your goal,
 * what you log, the services Forkcast talks to, and the app itself. Each entry says what's inside.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, actions: SettingsActions) {
    SettingsScaffold(title = stringResource(R.string.settings), onBack = onBack) {
        Group(R.string.settings_group_you)
        Item(R.string.settings_goal_budget, R.string.settings_goal_budget_summary, actions.goalBudget)
        Item(R.string.settings_profile, R.string.settings_profile_summary, actions.profile)
        Item(R.string.settings_daily_targets, R.string.settings_daily_targets_summary, actions.dailyTargets)

        Group(R.string.settings_group_logging)
        Item(R.string.settings_meals, R.string.settings_meals_summary, actions.meals)
        Item(R.string.settings_water, R.string.settings_water_summary, actions.water)
        Item(R.string.settings_units, R.string.settings_units_summary, actions.units)

        Group(R.string.settings_group_connections)
        Item(R.string.settings_health_connect, R.string.settings_health_connect_summary, actions.healthConnect)
        Item(R.string.settings_ai, R.string.settings_ai_summary, actions.ai)
        Item(R.string.settings_food_databases, R.string.settings_food_databases_summary, actions.foodDatabases)

        Group(R.string.settings_group_app)
        Item(R.string.settings_notifications, R.string.settings_notifications_summary, actions.notifications)
        Item(R.string.settings_backup_data, R.string.settings_backup_data_summary, actions.backup)
        Item(R.string.settings_appearance, R.string.settings_appearance_summary, actions.appearance)
        Item(R.string.settings_about, R.string.settings_about_summary, actions.about)
    }
}

@Composable
private fun Group(@StringRes title: Int) {
    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun Item(@StringRes title: Int, @StringRes summary: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(summary)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
