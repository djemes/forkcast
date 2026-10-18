package app.forkcast.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.BuildConfig
import app.forkcast.R

@Composable
fun AboutRoute(onBack: () -> Unit) {
    AboutScreen(versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE, onBack = onBack)
}

@Composable
fun AboutScreen(versionName: String, versionCode: Int, onBack: () -> Unit) {
    SettingsScaffold(title = stringResource(R.string.settings_about), onBack = onBack) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.about_version, versionName, versionCode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AboutSection(R.string.about_privacy_title, stringResource(R.string.about_privacy_body))
            AboutSection(R.string.about_medical_title, stringResource(R.string.about_medical_body))
            AboutSection(R.string.about_licenses_title, stringResource(R.string.about_license_manrope))
        }
    }
}

@Composable
private fun AboutSection(title: Int, body: String) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 24.dp, bottom = 4.dp)
            .semantics { heading() },
    )
    Text(text = body, style = MaterialTheme.typography.bodyLarge)
}
