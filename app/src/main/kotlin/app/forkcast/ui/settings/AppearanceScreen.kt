package app.forkcast.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.core.model.AppearanceSettings
import app.forkcast.core.model.ThemeMode

@Composable
fun AppearanceRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    settings?.let {
        AppearanceScreen(
            appearance = it.appearance,
            wallpaperColorsAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            onThemeMode = viewModel::setThemeMode,
            onUseWallpaperColors = viewModel::setUseWallpaperColors,
            onBack = onBack,
        )
    }
}

@Composable
fun AppearanceScreen(
    appearance: AppearanceSettings,
    wallpaperColorsAvailable: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onUseWallpaperColors: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val listColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
    SettingsScaffold(title = stringResource(R.string.settings_appearance), onBack = onBack) {
        Text(
            text = stringResource(R.string.appearance_theme),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { heading() },
        )
        Column(Modifier.selectableGroup()) {
            listOf(
                ThemeMode.SYSTEM to R.string.theme_system,
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark,
            ).forEach { (mode, label) ->
                val selected = appearance.themeMode == mode
                ListItem(
                    headlineContent = { Text(stringResource(label)) },
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    colors = listColors,
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { onThemeMode(mode) },
                )
            }
        }
        if (wallpaperColorsAvailable) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.appearance_wallpaper_colors)) },
                supportingContent = { Text(stringResource(R.string.appearance_wallpaper_colors_summary)) },
                trailingContent = { Switch(checked = appearance.useWallpaperColors, onCheckedChange = null) },
                colors = listColors,
                modifier = Modifier.toggleable(
                    value = appearance.useWallpaperColors,
                    role = Role.Switch,
                    onValueChange = onUseWallpaperColors,
                ),
            )
        }
    }
}
