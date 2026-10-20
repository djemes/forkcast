package app.forkcast.ui.settings

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.BuildConfig
import app.forkcast.R
import app.forkcast.core.data.update.ApkProblem
import app.forkcast.core.data.update.Download
import app.forkcast.core.data.update.UpdateCheck
import app.forkcast.core.data.update.UpdateRepository
import app.forkcast.core.network.update.Release
import app.forkcast.core.network.update.UpdateError
import app.forkcast.di.GITHUB_URL
import app.forkcast.ui.common.SavedKeyField
import app.forkcast.ui.common.SecretText
import app.forkcast.update.AppInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** What About's Updates section shows. */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class Result(val check: UpdateCheck) : UpdateUi
    data class Downloading(val release: Release, val done: Long, val total: Long) : UpdateUi
    /** Downloaded and checked; waiting for "Install unknown apps" or Android's installer. */
    data class Ready(val release: Release, val apk: File) : UpdateUi
    data class Refused(val problem: ApkProblem, val apkVersionCode: Long?) : UpdateUi
    data class DownloadFailed(val error: UpdateError) : UpdateUi
}

@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updates: UpdateRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val ui: StateFlow<UpdateUi> = _ui.asStateFlow()

    /** The saved token, masked with show/hide like the API keys; never logged. */
    val token: StateFlow<SecretText?> = updates.hasToken
        .map { has -> if (has) updates.savedToken()?.let(::SecretText) else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val installer = AppInstaller.status

    fun check() = viewModelScope.launch {
        AppInstaller.reset()
        _ui.value = UpdateUi.Checking
        _ui.value = UpdateUi.Result(updates.check(BuildConfig.VERSION_NAME))
    }

    fun download(release: Release) = viewModelScope.launch {
        _ui.value = UpdateUi.Downloading(release, 0, release.apk?.sizeBytes ?: 0)
        _ui.value = when (val d = updates.download(release) { done, total -> _ui.value = UpdateUi.Downloading(release, done, total) }) {
            is Download.Ready -> UpdateUi.Ready(release, d.apk).also { install(it) }
            is Download.Refused -> UpdateUi.Refused(d.problem, d.apkVersionCode)
            is Download.Failed -> UpdateUi.DownloadFailed(d.error)
        }
    }

    /** Hands the APK to Android, once Forkcast may install (otherwise the screen asks for that first). */
    fun install(ready: UpdateUi.Ready) = viewModelScope.launch {
        if (AppInstaller.canInstall(context)) AppInstaller.install(context, ready.apk)
    }

    fun saveToken(token: String) = viewModelScope.launch { updates.setToken(token) }
    fun removeToken() = viewModelScope.launch { updates.setToken(null) }
}

@Composable
fun AboutRoute(onBack: () -> Unit, viewModel: AboutViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val token by viewModel.token.collectAsStateWithLifecycle()
    val installer by viewModel.installer.collectAsStateWithLifecycle()
    AboutScreen(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        onBack = onBack,
        updates = UpdateSection(ui, installer, token, viewModel::check, viewModel::download, viewModel::install, viewModel::saveToken, viewModel::removeToken),
    )
}

/** Everything the Updates section needs; null in previews and tests that don't cover it. */
class UpdateSection(
    val ui: UpdateUi,
    val installer: AppInstaller.Status,
    val token: SecretText?,
    val onCheck: () -> Unit,
    val onDownload: (Release) -> Unit,
    val onInstall: (UpdateUi.Ready) -> Unit,
    val onSaveToken: (String) -> Unit,
    val onRemoveToken: () -> Unit,
)

@Composable
fun AboutScreen(versionName: String, versionCode: Int, onBack: () -> Unit, updates: UpdateSection? = null) {
    val uri = LocalUriHandler.current
    SettingsScaffold(title = stringResource(R.string.settings_about), onBack = onBack) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.about_version, versionName, versionCode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (updates != null) Updates(updates)

            Heading(R.string.about_github_title)
            Text(stringResource(R.string.about_github_body), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { uri.openUri(GITHUB_URL) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.about_github_open))
            }

            AboutSection(R.string.about_privacy_title, stringResource(R.string.about_privacy_body))
            AboutSection(R.string.about_medical_title, stringResource(R.string.about_medical_body))
            AboutSection(R.string.about_licenses_title, stringResource(R.string.about_license_manrope))
        }
    }
}

@Composable
private fun Updates(s: UpdateSection) {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    // Re-read when coming back from Android's "Install unknown apps" screen.
    var canInstall by remember { mutableStateOf(AppInstaller.canInstall(context)) }
    LifecycleResumeEffect(Unit) {
        canInstall = AppInstaller.canInstall(context)
        onPauseOrDispose {}
    }
    Heading(R.string.update_title)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val busy = s.ui is UpdateUi.Checking || s.ui is UpdateUi.Downloading || s.installer is AppInstaller.Status.Waiting
        Button(onClick = s.onCheck, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_check)) }

        val status: String? = when (val ui = s.ui) {
            UpdateUi.Idle -> null
            UpdateUi.Checking -> stringResource(R.string.update_checking)
            is UpdateUi.Result -> when (val c = ui.check) {
                UpdateCheck.NoToken -> stringResource(R.string.update_no_token)
                is UpdateCheck.UpToDate -> stringResource(R.string.update_up_to_date, c.latestTag.removePrefix("v"))
                is UpdateCheck.Available -> stringResource(R.string.update_available, c.release.tag.removePrefix("v"))
                is UpdateCheck.Failed -> errorText(c.error)
            }
            is UpdateUi.Downloading -> if (ui.total > 0) {
                stringResource(R.string.update_downloading, Formatter.formatShortFileSize(context, ui.done), Formatter.formatShortFileSize(context, ui.total))
            } else {
                stringResource(R.string.update_downloading_unknown)
            }
            is UpdateUi.Ready -> when (s.installer) {
                is AppInstaller.Status.Failed -> s.installer.message?.let { stringResource(R.string.update_failed_detail, it) } ?: stringResource(R.string.update_failed)
                else -> if (canInstall) stringResource(R.string.update_waiting) else stringResource(R.string.update_allow_body)
            }
            is UpdateUi.Refused -> when (ui.problem) {
                ApkProblem.NO_APK_IN_RELEASE -> stringResource(R.string.update_no_apk)
                ApkProblem.NOT_AN_APK -> stringResource(R.string.update_not_apk)
                ApkProblem.OTHER_APP -> stringResource(R.string.update_other_app)
                ApkProblem.OTHER_SIGNER -> stringResource(R.string.update_other_signer)
                ApkProblem.NOT_NEWER -> stringResource(R.string.update_not_newer, BuildConfig.VERSION_CODE, ui.apkVersionCode ?: 0L)
            }
            is UpdateUi.DownloadFailed -> errorText(ui.error)
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }

        when (val ui = s.ui) {
            is UpdateUi.Result -> when (val c = ui.check) {
                is UpdateCheck.Available -> {
                    c.release.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                        Text(notes.lines().take(NOTES_LINES).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { s.onDownload(c.release) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_install)) }
                }
                UpdateCheck.NoToken, is UpdateCheck.Failed ->
                    OutlinedButton(onClick = { uri.openUri("$GITHUB_URL/releases") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.about_releases_open)) }
                else -> Unit
            }
            is UpdateUi.Downloading -> LinearProgressIndicator(
                progress = { if (ui.total > 0) (ui.done.toFloat() / ui.total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            is UpdateUi.Ready -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!canInstall) {
                    OutlinedButton(onClick = { context.startActivity(AppInstaller.allowInstallsIntent(context)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.update_allow))
                    }
                }
                if (s.installer !is AppInstaller.Status.Waiting) {
                    Button(
                        onClick = { if (canInstall) s.onInstall(ui) else context.startActivity(AppInstaller.allowInstallsIntent(context)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.update_install_again)) }
                }
            }
            else -> Unit
        }

        TokenField(s)
    }
}

@Composable
private fun TokenField(s: UpdateSection) {
    val uri = LocalUriHandler.current
    var replacing by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    if (s.token != null && !replacing) {
        SavedKeyField(stringResource(R.string.update_token), s.token)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { replacing = true }) { Text(stringResource(R.string.key_replace)) }
            TextButton(onClick = s.onRemoveToken) { Text(stringResource(R.string.update_token_remove)) }
        }
    } else {
        KeyEntry(
            label = stringResource(if (s.token != null) R.string.update_token_new else R.string.update_token),
            onSave = { s.onSaveToken(it); replacing = false },
            onCancel = if (s.token != null) ({ replacing = false }) else null,
        )
    }
    TextButton(onClick = { help = !help }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_token_help_title)) }
    if (help) {
        Text(stringResource(R.string.update_token_help), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { uri.openUri(TOKEN_PAGE) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_token_page)) }
    }
}

@Composable
private fun errorText(e: UpdateError): String = when (e) {
    is UpdateError.Offline -> stringResource(R.string.update_offline)
    is UpdateError.TokenRejected -> stringResource(R.string.update_token_rejected)
    is UpdateError.NoRelease -> stringResource(R.string.update_no_release)
    is UpdateError.Http -> stringResource(R.string.update_http, e.code)
}

@Composable
private fun Heading(title: Int) {
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
    Heading(title)
    Text(text = body, style = MaterialTheme.typography.bodyLarge)
}

private const val NOTES_LINES = 8

/** GitHub's page for creating a fine-grained token. */
private const val TOKEN_PAGE = "https://github.com/settings/personal-access-tokens/new"
