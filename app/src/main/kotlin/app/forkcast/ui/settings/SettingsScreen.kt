package app.forkcast.ui.settings

// One term per concept, in every screen and both languages (roadmap 2.0 task 3.5):
// - "budget" (orçamento): the daily calorie target. Not "goal", "limit" or "allowance".
// - "maintenance" (manutenção): what the body burns on a usual day; "TDEE" only in Help's glossary.
// - "goal" (meta): target weight and pace. "Daily targets" (metas diárias): per-nutrient amounts.
// - "AI service" (serviço de IA), or just "AI": one provider and model with its key. Never "profile"
//   in user text; "Profile" (Perfil) is the person (sex, age, height, activity).
// - "estimate" (estimativa): anything the AI returns. Never "result", "analysis" or "calculation".
// - "key" or "API key" (chave, chave de API); "token" only for GitHub's.
// - "log" (registro) for what was eaten; "entry" for one line of it; "weigh-in" (pesagem).
// - "backup" (backup): a .forkcast file. "Export" only for CSV files and the settings file.
// - Paths: "Settings › AI" (Configurações › IA), with › and the screen's title.

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.ui.common.AdvancedSection

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
    /** Null while Forkcast ships one language. */
    val language: (() -> Unit)? = null,
    val backup: () -> Unit = {},
    /** Null in builds without the updater (the Play build). */
    val updates: (() -> Unit)? = null,
    val help: () -> Unit = {},
    val about: () -> Unit = {},
    /** Debug builds' developer tools ("Load demo data"); empty in release builds. */
    val debugTools: List<DebugTool> = emptyList(),
)

/**
 * Settings, Basics and Advanced (roadmap 2.0 task 3.5, owner-approved): what most people use
 * shows by default, grouped as you and your log, reminders, AI and data, then the app itself.
 * Per-nutrient targets and the food databases wait under Advanced, which starts collapsed. Each
 * entry says what's inside.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, actions: SettingsActions, @StringRes message: Int? = null, onMessageShown: () -> Unit = {}) {
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(message) {
        val shown = message ?: return@LaunchedEffect
        onMessageShown()
        snackbar.showSnackbar(resources.getString(shown))
    }
    SettingsScaffold(title = stringResource(R.string.settings), onBack = onBack, snackbarHostState = snackbar) {
        Group(R.string.settings_group_you, divider = false)
        Item(R.string.settings_goal_budget, R.string.settings_goal_budget_summary, actions.goalBudget)
        Item(R.string.settings_profile, R.string.settings_profile_summary, actions.profile)
        Item(R.string.settings_meals, R.string.settings_meals_summary, actions.meals)
        Item(R.string.settings_water, R.string.settings_water_summary, actions.water)
        Item(R.string.settings_units, R.string.settings_units_summary, actions.units)

        Group(R.string.settings_group_connections)
        Item(R.string.settings_notifications, R.string.settings_notifications_summary, actions.notifications)
        Item(R.string.settings_ai, R.string.settings_ai_summary, actions.ai)
        Item(R.string.settings_health_connect, R.string.settings_health_connect_summary, actions.healthConnect)
        Item(R.string.settings_backup_data, R.string.settings_backup_data_summary, actions.backup)

        Group(R.string.settings_group_app)
        actions.updates?.let { Item(R.string.settings_updates, R.string.settings_updates_summary, it) }
        Item(R.string.settings_appearance, R.string.settings_appearance_summary, actions.appearance)
        actions.language?.let { Item(R.string.settings_language, R.string.settings_language_summary, it) }
        Item(R.string.settings_help, R.string.settings_help_summary, actions.help)
        Item(R.string.settings_about, R.string.settings_about_summary, actions.about)

        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        AdvancedSection(Modifier.padding(top = 4.dp)) {
            Item(R.string.settings_daily_targets, R.string.settings_daily_targets_summary, actions.dailyTargets)
            Item(R.string.settings_food_databases, R.string.settings_food_databases_summary, actions.foodDatabases)
            actions.debugTools.forEach { Item(it.label, it.summary, it.run) }
        }
    }
}

@Composable
private fun Group(@StringRes title: Int, divider: Boolean = true) {
    if (divider) HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
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
