package app.forkcast.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.R
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings › About › Licenses: Forkcast's own license, then every library it's built with. The list is
 * generated from the dependencies at build time (AboutLibraries), so it stays accurate by itself and
 * opens offline (roadmap 2.0 task 4.5).
 */
@Composable
fun LicensesScreen(onBack: () -> Unit, onOpenAppLicense: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    SettingsScaffold(title = stringResource(R.string.about_licenses_title), onBack = onBack, scroll = false) {
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize(),
            header = { item(key = "app-license") { AppLicenseSummary(onOpenAppLicense) } },
            licenseDialogConfirmText = stringResource(R.string.ok),
        )
    }
}

@Composable
private fun AppLicenseSummary(onOpenAppLicense: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
        AboutHeading(R.string.about_app_license_title)
        Text(stringResource(R.string.about_app_license_body), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onOpenAppLicense, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.about_app_license_open))
        }
        AboutHeading(R.string.about_libraries_title)
        Text(stringResource(R.string.about_libraries_body), style = MaterialTheme.typography.bodyLarge)
    }
}

/** The license files bundled from the repo root by app/build.gradle.kts (`copyAppLicense`): the notice first. */
private val APP_LICENSE_FILES = listOf("LICENSE-ADDITIONAL-PERMISSION.md", "LICENSE")

/** Forkcast's license in full (GPL-3.0 and its additional permission), from the assets so it opens offline. */
@Composable
fun AppLicenseScreen(onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    val paragraphs by produceState(emptyList<LicenseParagraph>(), assets) {
        value = withContext(Dispatchers.IO) {
            APP_LICENSE_FILES.flatMap { name -> licenseParagraphs(assets.open("licenses/$name").bufferedReader().use { it.readText() }) }
        }
    }
    val english = LocalConfiguration.current.locales[0].language == "en"
    SettingsScaffold(title = stringResource(R.string.about_app_license_title), onBack = onBack, scroll = false) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
            // The license is English in every language, like the terms and the privacy policy.
            if (!english) {
                item(key = "language") {
                    Text(
                        stringResource(R.string.agreement_legal_language),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            items(paragraphs) { paragraph ->
                if (paragraph.heading) {
                    Text(
                        paragraph.text,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
                    )
                } else {
                    Text(paragraph.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }
}

data class LicenseParagraph(val text: String, val heading: Boolean = false)

private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\([^)]+\)""")

/**
 * A license file as paragraphs that reflow on a phone: blank lines separate paragraphs, hard-wrapped
 * lines are joined, a Markdown heading (`# …`) becomes a heading and `[text](link)` becomes its text.
 */
fun licenseParagraphs(text: String): List<LicenseParagraph> =
    text.replace("\r\n", "\n").split(Regex("""\n\s*\n""")).mapNotNull { block ->
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        when {
            lines.isEmpty() -> null
            lines.size == 1 && lines[0].startsWith("#") -> LicenseParagraph(lines[0].trimStart('#').trim(), heading = true)
            else -> LicenseParagraph(lines.joinToString(" ").replace(MARKDOWN_LINK, "$1"))
        }
    }
