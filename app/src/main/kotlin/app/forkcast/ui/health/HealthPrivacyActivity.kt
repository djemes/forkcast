package app.forkcast.ui.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.ui.settings.SettingsScaffold
import app.forkcast.ui.theme.ForkcastTheme

/**
 * Health Connect's "privacy policy" link opens this (SPEC §4.9): the rationale action through
 * Android 13, and the VIEW_PERMISSION_USAGE alias from Android 14 (see AndroidManifest.xml).
 */
class HealthPrivacyActivity : ComponentActivity() {
    // The app's own language on Android 10–12 (AppLanguage).
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(app.forkcast.AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ForkcastTheme(darkTheme = isSystemInDarkTheme(), useWallpaperColors = false) {
                SettingsScaffold(title = stringResource(R.string.hc_privacy_title), onBack = ::finish) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.hc_privacy_body), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = ::finish, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.hc_privacy_close))
                        }
                    }
                }
            }
        }
    }
}
