package app.forkcast.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import app.forkcast.R
import app.forkcast.ui.settings.SettingsScaffold
import app.forkcast.ui.theme.DarkForkcastColors
import app.forkcast.ui.theme.ForkcastColors
import app.forkcast.ui.theme.ForkcastTheme
import app.forkcast.ui.theme.LightForkcastColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A widget's Settings (shown when adding it, and from the widget's long-press menu):
 * glass strength and widget color, with a live preview on a light and a dark wallpaper.
 */
class WidgetSettingsActivity : ComponentActivity() {
    // The app's own language on Android 10–12 (AppLanguage).
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(app.forkcast.AppLanguage.wrap(newBase))


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out keeps whatever the widget had.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        lifecycleScope.launch {
            val manager = GlanceAppWidgetManager(this@WidgetSettingsActivity)
            val glanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }.getOrNull()
            val initial = glanceId?.let {
                WidgetStyle.from(getAppWidgetState(this@WidgetSettingsActivity, PreferencesGlanceStateDefinition, it))
            } ?: WidgetStyle()
            setContent {
                ForkcastTheme {
                    val scope = rememberCoroutineScope()
                    WidgetSettingsScreen(initial, onBack = { finish() }) { style ->
                        scope.launch {
                            if (glanceId != null) {
                                updateAppWidgetState(this@WidgetSettingsActivity, glanceId) { prefs ->
                                    prefs[WidgetStyle.BACKGROUND] = style.background
                                    prefs[WidgetStyle.THEME] = style.theme.name
                                }
                                WidgetPush.all(this@WidgetSettingsActivity)
                            }
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                            finish()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetSettingsScreen(initial: WidgetStyle, onBack: () -> Unit, onDone: (WidgetStyle) -> Unit) {
    // Saveable: unsaved edits survive a rotation or a dark-mode switch.
    var background by rememberSaveable { mutableFloatStateOf(initial.background) }
    var theme by rememberSaveable { mutableStateOf(initial.theme) }
    val style = WidgetStyle(background, theme)
    SettingsScaffold(title = stringResource(R.string.widget_settings_title), onBack = onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // The same widget on a light and a dark wallpaper, so readability is easy to judge.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewOn(style, wallpaperDark = false, Modifier.weight(1f))
                PreviewOn(style, wallpaperDark = true, Modifier.weight(1f))
            }
            Text(stringResource(R.string.widget_settings_background), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            val percent = (background * 100).roundToInt()
            val percentText = java.text.NumberFormat.getPercentInstance(androidx.compose.ui.platform.LocalLocale.current.platformLocale).format(percent / 100.0)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = background,
                    onValueChange = { background = (it * 20).roundToInt() / 20f },
                    modifier = Modifier.weight(1f).semantics { stateDescription = percentText },
                )
                Text(percentText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
            }
            Text(stringResource(R.string.widget_settings_background_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.widget_settings_text), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            val options = listOf(WidgetTheme.AUTO to R.string.widget_text_auto, WidgetTheme.LIGHT to R.string.widget_text_light, WidgetTheme.DARK to R.string.widget_text_dark)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = theme == mode,
                        onClick = { theme = mode },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        icon = {},
                    ) { Text(stringResource(label), maxLines = 2) }
                }
            }
            Text(stringResource(R.string.widget_settings_text_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onDone(style) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.widget_settings_done))
            }
        }
    }
}

/** A Compose stand-in for the widget, on a sample wallpaper. */
@Composable
private fun PreviewOn(style: WidgetStyle, wallpaperDark: Boolean, modifier: Modifier) {
    val systemDark = isSystemInDarkTheme()
    val darkGlass = when (style.theme) {
        WidgetTheme.AUTO -> systemDark
        WidgetTheme.LIGHT -> false
        WidgetTheme.DARK -> true
    }
    val palette: ForkcastColors = if (darkGlass) DarkForkcastColors else LightForkcastColors
    // The same glass the widget draws (WidgetPalette): tinted fill, sheen from the top, bright rim.
    val glass = colorResource(if (darkGlass) R.color.widget_glass_dark else R.color.widget_glass_light)
    val rimAlpha = (style.background / 0.6f).coerceAtMost(1f)
    val sheen = Color.White.copy(alpha = (if (darkGlass) 0.12f else 0.4f) * rimAlpha)
    val rim = Color.White.copy(alpha = (if (darkGlass) 0.24f else 0.8f) * rimAlpha)
    val wallpaper = if (wallpaperDark) Brush.linearGradient(listOf(Color(0xFF1B2333), Color(0xFF3A2C4F)))
    else Brush.linearGradient(listOf(Color(0xFFF4E9D8), Color(0xFFC9DCEB)))
    val label = stringResource(if (wallpaperDark) R.string.widget_preview_dark else R.string.widget_preview_light)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(wallpaper)
            .semantics { contentDescription = label },
    ) {
        val shape = RoundedCornerShape(26.dp)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(108.dp)
                .clip(shape)
                .background(glass.copy(alpha = style.background))
                .background(Brush.verticalGradient(0f to sheen, 0.5f to Color.Transparent))
                .border(1.dp, rim, shape),
        ) {
            val track = palette.ink.copy(alpha = WidgetArt.TRACK_ALPHA)
            // The same sample afternoon as the widget picker's preview, in the phone's units.
            val sample = remember { sampleSnapshot() }
            val f = sample.format
            Canvas(Modifier.size(84.dp)) {
                val stroke = size.minDimension * 0.085f
                val arcSize = Size(size.width - stroke * 2, size.height - stroke * 2)
                val topLeft = Offset(stroke, stroke)
                drawArc(track, -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(palette.pine, -90f, 360f * 0.6f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(f.energyNumber(sample.totals?.remainingKcal ?: 0.0), style = MaterialTheme.typography.titleLarge, color = palette.ink)
                Text(stringResource(R.string.ring_left, f.energyUnitLabel), style = MaterialTheme.typography.labelSmall, color = palette.muted)
            }
        }
    }
}
