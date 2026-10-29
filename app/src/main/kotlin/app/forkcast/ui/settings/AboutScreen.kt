package app.forkcast.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.BuildConfig
import app.forkcast.Distribution
import app.forkcast.R
import app.forkcast.di.DONATION_URL
import app.forkcast.di.GITHUB_URL

@Composable
fun AboutRoute(
    onBack: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    AboutScreen(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        onBack = onBack,
        onOpenUpdates = onOpenUpdates.takeIf { Distribution.current.hasUpdater },
        onOpenTerms = onOpenTerms,
        onOpenPrivacy = onOpenPrivacy,
        onOpenLicenses = onOpenLicenses,
        donationUrl = DONATION_URL.takeIf { Distribution.current.hasDonationLink && it.isNotBlank() },
    )
}

/** Settings › About: version (and the way to Updates in the GitHub build), source, privacy, licenses, attributions. */
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    onBack: () -> Unit,
    onOpenUpdates: (() -> Unit)? = null,
    onOpenTerms: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    donationUrl: String? = null,
) {
    val uri = LocalUriHandler.current
    SettingsScaffold(title = stringResource(R.string.settings_about), onBack = onBack) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.about_version, versionName, versionCode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onOpenUpdates != null) {
                OutlinedButton(onClick = onOpenUpdates, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.update_check))
                }
            }

            AboutHeading(R.string.about_github_title)
            Text(stringResource(R.string.about_github_body), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { uri.openUri(GITHUB_URL) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.about_github_open))
            }

            // Voluntary tips (roadmap 2.0 task 4.6): only here, never a reminder or pop-up anywhere else.
            if (donationUrl != null) {
                AboutHeading(R.string.about_support_title)
                Text(stringResource(R.string.about_support_body), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { uri.openUri(donationUrl) }, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.about_support_open))
                }
            }

            TextButton(onClick = onOpenTerms, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.about_terms)) }
            TextButton(onClick = onOpenPrivacy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.about_privacy_policy)) }
            TextButton(onClick = onOpenLicenses, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.about_licenses_title)) }
            AboutSection(R.string.about_privacy_title, stringResource(R.string.about_privacy_body))
            AboutSection(R.string.about_medical_title, stringResource(R.string.about_medical_body))

            // Data attributions and the non-affiliation note (roadmap 2.0 task 4.5).
            AboutHeading(R.string.about_attributions_title)
            listOf(R.string.about_attribution_off, R.string.about_attribution_usda).forEach {
                Text(stringResource(it), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(
                stringResource(R.string.about_not_affiliated),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
internal fun AboutHeading(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 24.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun AboutSection(title: Int, body: String) {
    AboutHeading(title)
    Text(text = body, style = MaterialTheme.typography.bodyLarge)
}
