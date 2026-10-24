package app.forkcast.ui.settings

import android.content.Context
import android.text.format.DateUtils
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
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.update.ApkProblem
import app.forkcast.core.data.update.Download
import app.forkcast.core.data.update.UpdateCheck
import app.forkcast.core.data.update.UpdateRepository
import app.forkcast.core.model.UpdateSettings
import app.forkcast.core.network.update.Release
import app.forkcast.core.network.update.UpdateError
import app.forkcast.di.GITHUB_URL
import app.forkcast.ui.common.SavedKeyField
import app.forkcast.ui.common.SecretText
import app.forkcast.ui.common.ToggleRow
import app.forkcast.update.AppInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** What the Updates page shows. */
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
class UpdatesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updates: UpdateRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val ui: StateFlow<UpdateUi> = _ui.asStateFlow()

    /** The saved token, masked with show/hide like the API keys; never logged. */
    val token: StateFlow<SecretText?> = updates.hasToken
        .map { has -> if (has) updates.savedToken()?.let(::SecretText) else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val options: StateFlow<UpdateSettings> = settings.settings.map { it.updates }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateSettings())

    val installer = AppInstaller.status

    fun check() = viewModelScope.launch {
        AppInstaller.reset()
        _ui.value = UpdateUi.Checking
        _ui.value = UpdateUi.Result(updates.check(BuildConfig.VERSION_NAME, includeBetas = settings.settings.first().updates.beta))
    }

    fun download(release: Release) = viewModelScope.launch {
        _ui.value = UpdateUi.Downloading(release, 0, release.apk?.sizeBytes ?: 0)
        _ui.value = when (val d = updates.download(release) { done, total -> _ui.value = UpdateUi.Downloading(release, done, total) }) {
            is Download.Ready -> UpdateUi.Ready(release, d.apk).also { install(it) }
            is Download.Refused -> UpdateUi.Refused(d.problem, d.apkVersionCode)
            is Download.Failed -> UpdateUi.DownloadFailed(d.error)
        }
    }

    /** Hands the APK to Android, once Forkcast may install (otherwise the page explains that first). */
    fun install(ready: UpdateUi.Ready) = viewModelScope.launch {
        if (AppInstaller.canInstall(context)) AppInstaller.install(context, ready.apk)
    }

    fun setAuto(on: Boolean) = viewModelScope.launch { settings.update { it.copy(updates = it.updates.copy(auto = on)) } }
    fun setBeta(on: Boolean) = viewModelScope.launch { settings.update { it.copy(updates = it.updates.copy(beta = on)) } }
    fun saveToken(token: String) = viewModelScope.launch { updates.setToken(token) }
    fun removeToken() = viewModelScope.launch { updates.setToken(null) }
}

@Composable
fun UpdatesRoute(onBack: () -> Unit, viewModel: UpdatesViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val token by viewModel.token.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val installer by viewModel.installer.collectAsStateWithLifecycle()
    UpdatesScreen(
        versionName = BuildConfig.VERSION_NAME,
        onBack = onBack,
        state = UpdatesState(ui, installer, token, options),
        actions = UpdatesActions(
            check = viewModel::check,
            download = viewModel::download,
            install = viewModel::install,
            setAuto = viewModel::setAuto,
            setBeta = viewModel::setBeta,
            saveToken = viewModel::saveToken,
            removeToken = viewModel::removeToken,
        ),
    )
}

data class UpdatesState(
    val ui: UpdateUi = UpdateUi.Idle,
    val installer: AppInstaller.Status = AppInstaller.Status.Idle,
    val token: SecretText? = null,
    val options: UpdateSettings = UpdateSettings(),
)

data class UpdatesActions(
    val check: () -> Unit = {},
    val download: (Release) -> Unit = {},
    val install: (UpdateUi.Ready) -> Unit = {},
    val setAuto: (Boolean) -> Unit = {},
    val setBeta: (Boolean) -> Unit = {},
    val saveToken: (String) -> Unit = {},
    val removeToken: () -> Unit = {},
)

/**
 * Settings › Updates (roadmap 2.0, task 1.5; GitHub build only): the version, Check now, the
 * release notes before installing, automatic checks and betas, and (under Advanced) the token
 * the private repository still needs until it goes public.
 */
@Composable
fun UpdatesScreen(versionName: String, onBack: () -> Unit, state: UpdatesState, actions: UpdatesActions) {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    // Re-read when coming back from Android's "Install unknown apps" screen.
    var canInstall by remember { mutableStateOf(AppInstaller.canInstall(context)) }
    LifecycleResumeEffect(Unit) {
        canInstall = AppInstaller.canInstall(context)
        onPauseOrDispose {}
    }
    var advanced by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(title = stringResource(R.string.update_title), onBack = onBack) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_from_github), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.update_current, versionName), style = MaterialTheme.typography.bodyLarge)
            val busy = state.ui is UpdateUi.Checking || state.ui is UpdateUi.Downloading || state.installer is AppInstaller.Status.Waiting
            Button(onClick = actions.check, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_check)) }

            statusText(state, canInstall)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }

            when (val ui = state.ui) {
                is UpdateUi.Result -> when (val c = ui.check) {
                    is UpdateCheck.Available -> {
                        c.release.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                            Heading(R.string.update_whats_new)
                            Text(notes.lines().take(NOTES_LINES).joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = { actions.download(c.release) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_install)) }
                    }
                    UpdateCheck.NeedsToken, is UpdateCheck.Failed ->
                        OutlinedButton(onClick = { uri.openUri("$GITHUB_URL/releases") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.about_releases_open)) }
                    is UpdateCheck.UpToDate -> Unit
                }
                is UpdateUi.Downloading -> LinearProgressIndicator(
                    progress = { if (ui.total > 0) (ui.done.toFloat() / ui.total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                is UpdateUi.Ready -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!canInstall) {
                        Button(onClick = { context.startActivity(AppInstaller.allowInstallsIntent(context)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.update_allow))
                        }
                    } else if (state.installer !is AppInstaller.Status.Waiting) {
                        Button(onClick = { actions.install(ui) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_install_again)) }
                    }
                }
                is UpdateUi.Refused -> if (ui.problem == ApkProblem.NO_CHECKSUM) {
                    OutlinedButton(onClick = { uri.openUri("$GITHUB_URL/releases") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.about_releases_open)) }
                }
                else -> Unit
            }

            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow(stringResource(R.string.update_auto), stringResource(R.string.update_auto_detail), state.options.auto, actions.setAuto)
                ToggleRow(stringResource(R.string.update_beta), stringResource(R.string.update_beta_detail), state.options.beta, actions.setBeta)
            }

            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (advanced) R.string.update_advanced_hide else R.string.update_advanced))
            }
            if (advanced) {
                Text(stringResource(R.string.update_token_why), style = MaterialTheme.typography.bodyMedium)
                TokenField(state.token, actions)
            }
        }
    }
}

@Composable
private fun statusText(state: UpdatesState, canInstall: Boolean): String? {
    val context = LocalContext.current
    return when (val ui = state.ui) {
        UpdateUi.Idle -> null
        UpdateUi.Checking -> stringResource(R.string.update_checking)
        is UpdateUi.Result -> when (val c = ui.check) {
            UpdateCheck.NeedsToken -> stringResource(R.string.update_no_token)
            is UpdateCheck.UpToDate -> stringResource(R.string.update_up_to_date, c.latestTag.removePrefix("v"))
            is UpdateCheck.Available -> stringResource(
                if (c.release.prerelease) R.string.update_available_beta else R.string.update_available,
                c.release.tag.removePrefix("v"),
            )
            is UpdateCheck.Failed -> errorText(c.error)
        }
        is UpdateUi.Downloading -> if (ui.total > 0) {
            stringResource(R.string.update_downloading, Formatter.formatShortFileSize(context, ui.done), Formatter.formatShortFileSize(context, ui.total))
        } else {
            stringResource(R.string.update_downloading_unknown)
        }
        is UpdateUi.Ready -> when (val i = state.installer) {
            is AppInstaller.Status.Failed -> i.message?.let { stringResource(R.string.update_failed_detail, it) } ?: stringResource(R.string.update_failed)
            else -> if (canInstall) stringResource(R.string.update_waiting) else stringResource(R.string.update_allow_body)
        }
        is UpdateUi.Refused -> when (ui.problem) {
            ApkProblem.NO_APK_IN_RELEASE -> stringResource(R.string.update_no_apk)
            ApkProblem.NOT_AN_APK -> stringResource(R.string.update_not_apk)
            ApkProblem.OTHER_APP -> stringResource(R.string.update_other_app)
            ApkProblem.OTHER_SIGNER -> stringResource(R.string.update_other_signer)
            ApkProblem.NOT_NEWER -> stringResource(R.string.update_not_newer, BuildConfig.VERSION_CODE, ui.apkVersionCode ?: 0L)
            ApkProblem.NO_CHECKSUM -> stringResource(R.string.update_no_checksum)
            ApkProblem.WRONG_CHECKSUM -> stringResource(R.string.update_wrong_checksum)
        }
        is UpdateUi.DownloadFailed -> errorText(ui.error)
    }
}

@Composable
private fun TokenField(token: SecretText?, actions: UpdatesActions) {
    val uri = LocalUriHandler.current
    var replacing by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    if (token != null && !replacing) {
        SavedKeyField(stringResource(R.string.update_token), token)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { replacing = true }) { Text(stringResource(R.string.key_replace)) }
            TextButton(onClick = actions.removeToken) { Text(stringResource(R.string.update_token_remove)) }
        }
    } else {
        KeyEntry(
            label = stringResource(if (token != null) R.string.update_token_new else R.string.update_token),
            onSave = { actions.saveToken(it); replacing = false },
            onCancel = if (token != null) ({ replacing = false }) else null,
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
    is UpdateError.RateLimited -> {
        val context = LocalContext.current
        e.resetAt?.let { stringResource(R.string.update_rate_limited_until, DateUtils.formatDateTime(context, it * 1000, DateUtils.FORMAT_SHOW_TIME)) }
            ?: stringResource(R.string.update_rate_limited)
    }
    is UpdateError.Http -> stringResource(R.string.update_http, e.code)
    is UpdateError.WrongDownload -> stringResource(R.string.update_wrong_download)
}

@Composable
private fun Heading(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics { heading() },
    )
}

private const val NOTES_LINES = 12

/** GitHub's page for creating a fine-grained token. */
private const val TOKEN_PAGE = "https://github.com/settings/personal-access-tokens/new"
