package app.forkcast.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R
import app.forkcast.ui.common.AdvancedSection
import app.forkcast.ui.help.HelpLink
import app.forkcast.ui.help.HelpLinks
import app.forkcast.core.data.repository.AiProfileStatus
import app.forkcast.core.model.AiProfile
import app.forkcast.core.model.AiRecipient
import app.forkcast.core.model.CheckFailure
import app.forkcast.core.model.ProfileCheck
import app.forkcast.ui.settings.SettingsScaffold
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

@Composable
fun AiSettingsRoute(
    onBack: () -> Unit,
    onEditProfile: (String?) -> Unit,
    onAddAi: () -> Unit,
    /** A delete the profile editor already confirmed; this screen does it and offers Undo. */
    deleteRequest: String? = null,
    onDeleteHandled: () -> Unit = {},
    viewModel: AiSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(deleteRequest) {
        if (deleteRequest != null) {
            viewModel.onEvent(AiSettingsEvent.Delete(deleteRequest))
            onDeleteHandled()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is AiSettingsEffect.Deleted -> {
                    val result = snackbar.showSnackbar(
                        resources.getString(R.string.ai_deleted, effect.name),
                        resources.getString(R.string.undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.onEvent(AiSettingsEvent.UndoDelete)
                }
                is AiSettingsEffect.BackupAdded -> snackbar.showSnackbar(resources.getString(R.string.backup_model_added, effect.name))
            }
        }
    }
    AiSettingsScreen(state, viewModel::onEvent, onBack, onEditProfile, onAddAi, snackbar)
}

private val RETENTION_CHOICES = listOf(7, 14, 30, 90, null)

/**
 * Settings › AI (SPEC §4.7), reworked 2026-10-19 so each card answers four questions in order:
 * what it is (number, name, service · model), what it does (used first / backup / off), whether
 * it works (last test, kept across restarts) and what you can do (Test, Edit, Delete). Since
 * roadmap 2.0 task 3.5 the order, manual setup and prices are under Advanced ([AiAdvanced]); Edit
 * stays on the card because a rejected key is fixed there (replacing a key is everyday, not expert).
 */
@Composable
fun AiSettingsScreen(
    state: AiSettingsUiState,
    onEvent: (AiSettingsEvent) -> Unit,
    onBack: () -> Unit,
    onEditProfile: (String?) -> Unit,
    onAddAi: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    // Roadmap 2.0 task 4.2: the permission sheet for a profile's recipient, and "Take back".
    var askPermission by remember { mutableStateOf<AiProfile?>(null) }
    var confirmRevoke by remember { mutableStateOf<String?>(null) }
    askPermission?.let { p ->
        AiConsentSheet(
            p.provider, p.baseUrl,
            onAllow = { askPermission = null; onEvent(AiSettingsEvent.AllowAi(AiRecipient.of(p))) },
            onNotNow = { askPermission = null },
        )
    }
    confirmRevoke?.let { recipient ->
        val name = recipientName(recipient)
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            title = { Text(stringResource(R.string.consent_take_back_title, name)) },
            text = { Text(stringResource(R.string.consent_take_back_body, name)) },
            confirmButton = {
                TextButton(onClick = { confirmRevoke = null; onEvent(AiSettingsEvent.RevokeAi(recipient)) }) {
                    Text(stringResource(R.string.consent_take_back_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    SettingsScaffold(title = stringResource(R.string.settings_ai), onBack = onBack, snackbarHostState = snackbarHostState) {
        if (!state.loaded) return@SettingsScaffold
        val s = state.settings
        SwitchRow(stringResource(R.string.ai_enabled), stringResource(R.string.ai_enabled_body), s.enabled) {
            onEvent(AiSettingsEvent.SetEnabled(it))
        }
        if (!s.enabled) return@SettingsScaffold
        HorizontalDivider()

        SectionTitle(R.string.ai_profiles_title)
        if (state.profiles.isEmpty()) {
            Text(
                stringResource(R.string.ai_no_profiles),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                state.profiles.forEachIndexed { index, item ->
                    key(item.profile.id) {
                        ProfileCard(
                            item = item,
                            role = state.roleAt(index),
                            test = state.tests[item.profile.id],
                            check = s.lastChecks[item.profile.id],
                            index = index,
                            onEvent = onEvent,
                            onEdit = { onEditProfile(item.profile.id) },
                            onDelete = { confirmDelete = item.profile.id },
                            onAllow = { askPermission = item.profile }.takeUnless { state.allowed(item.profile) },
                        )
                    }
                }
            }
        }
        // One Gemini profile: its budget model on the same key is a free second quota (owner, 2026-10-23).
        state.backupOffer?.let { offer ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                app.forkcast.ui.photo.BackupModelCard(offer.name, offer.fromProfileName) { onEvent(AiSettingsEvent.AddBackupModel) }
            }
        }
        // Adding is one obvious button: the setup guide. The full editor stays for experts.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Button(onClick = onAddAi, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ai_add)) }
        }
        HorizontalDivider(Modifier.padding(top = 16.dp))

        Permissions(state.consents) { confirmRevoke = it }
        HorizontalDivider()

        SectionTitle(R.string.ai_section_photos)
        PersonalContextField(s.personalContext) { onEvent(AiSettingsEvent.SetPersonalContext(it)) }
        SwitchRow(stringResource(R.string.ai_auto_accept), stringResource(R.string.ai_auto_accept_body), s.autoAccept) {
            onEvent(AiSettingsEvent.SetAutoAccept(it))
        }
        RetentionPicker(s.photoRetentionDays) { onEvent(AiSettingsEvent.SetRetention(it)) }
        HorizontalDivider()

        // Order, editing, manual setup and prices are for experts (roadmap 2.0 task 3.5).
        AdvancedSection { AiAdvanced(state, onEvent, onEditProfile) }
    }

    confirmDelete?.let { id ->
        val index = state.profiles.indexOfFirst { it.profile.id == id }
        val profile = state.profiles.getOrNull(index)?.profile
        if (profile == null) {
            confirmDelete = null
        } else {
            DeleteProfileDialog(
                name = profile.name,
                movesUp = state.profiles.getOrNull(index + 1)?.profile?.name,
                onConfirm = { confirmDelete = null; onEvent(AiSettingsEvent.Delete(id)) },
                onDismiss = { confirmDelete = null },
            )
        }
    }
}

@Composable
internal fun SectionTitle(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
    }
}

/** "Gemini (Google) · gemini-3.8-flash" */
@Composable
private fun profileLine(item: AiProfileStatus): String {
    val p = item.profile
    return listOf(stringResource(p.provider.label()), p.model).joinToString(" · ")
}

@Composable
private fun ProfileCard(
    item: AiProfileStatus,
    role: ProfileRole,
    test: TestState?,
    check: ProfileCheck?,
    index: Int,
    onEvent: (AiSettingsEvent) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** Set while its recipient isn't allowed to receive meals: Allow and Test open the permission sheet. */
    onAllow: (() -> Unit)? = null,
) {
    val p = item.profile
    val res = LocalResources.current
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        // What it is
        Row(verticalAlignment = Alignment.CenterVertically) {
            PositionBadge(index + 1, first = role == ProfileRole.FIRST)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Text(profileLine(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = p.enabled,
                onCheckedChange = { onEvent(AiSettingsEvent.SetProfileEnabled(p.id, it)) },
                modifier = Modifier.semantics { contentDescription = res.getString(R.string.ai_profile_enabled, p.name) },
            )
        }
        // What it does, and whether it works
        RoleLine(role, p.cooldownUntil?.let(::formatTime))
        if (test != null) TestResult(test, p.provider) else CheckLine(check)
        Text(
            stringResource(R.string.ai_used_today, item.usedToday),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 28.dp),
        )
        // What you can do
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (onAllow != null) TextButton(onClick = onAllow) { Text(stringResource(R.string.ai_allow)) }
            TextButton(onClick = { onAllow?.invoke() ?: onEvent(AiSettingsEvent.Test(p.id)) }, enabled = test !is TestState.Running) {
                Text(stringResource(if (test is TestState.Running) R.string.ai_testing else R.string.ai_test))
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.ai_edit)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Who may receive meals, since when, and Take back (roadmap 2.0 task 4.2). */
@Composable
private fun Permissions(consents: Map<String, Long>, onTakeBack: (String) -> Unit) {
    SectionTitle(R.string.consent_section)
    if (consents.isEmpty()) {
        Text(stringResource(R.string.consent_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        return
    }
    consents.entries.sortedBy { it.value }.forEach { (recipient, at) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(recipientName(recipient), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.consent_since, formatDate(at)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onTakeBack(recipient) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.consent_take_back)) }
        }
    }
}

/** The last test, remembered across restarts: when, and whether it worked. */
@Composable
private fun CheckLine(check: ProfileCheck?) {
    if (check == null) {
        IconLine(R.drawable.ic_remove_circle, stringResource(R.string.ai_check_never), MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val time = formatDateTime(check.atMillis)
    if (check.ok) {
        IconLine(R.drawable.ic_check_circle, stringResource(R.string.ai_check_ok, time), MaterialTheme.colorScheme.primary)
    } else {
        val why = stringResource(
            when (check.failure) {
                CheckFailure.KEY -> R.string.ai_check_why_key
                CheckFailure.QUOTA -> R.string.ai_check_why_quota
                CheckFailure.MODEL -> R.string.ai_check_why_model
                CheckFailure.NETWORK -> R.string.ai_check_why_network
                else -> R.string.ai_check_why_other
            },
        )
        IconLine(R.drawable.ic_error, stringResource(R.string.ai_check_failed, time, why), MaterialTheme.colorScheme.error)
        HelpLink(HelpLinks.check(check.failure))
    }
}

private fun formatDateTime(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun formatDate(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun formatTime(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

@Composable
private fun PersonalContextField(saved: String, onChange: (String) -> Unit) {
    // Local copy so typing never fights the stored value.
    var text by rememberSaveable { mutableStateOf(saved) }
    LaunchedEffect(saved) { if (saved != text && text.isEmpty()) text = saved }
    Column(Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(500); onChange(text) },
            label = { Text(stringResource(R.string.ai_personal_context)) },
            placeholder = { Text(stringResource(R.string.ai_personal_context_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.ai_personal_context_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RetentionPicker(days: Int?, onChange: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = days?.let { stringResource(R.string.ai_retention_days, it) } ?: stringResource(R.string.ai_retention_forever)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { open = true }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(stringResource(R.string.ai_photo_retention), style = MaterialTheme.typography.bodyLarge)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.ai_photo_retention_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.ai_photo_retention)) },
            text = {
                Column {
                    for (choice in RETENTION_CHOICES) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = choice == days, role = Role.RadioButton) { onChange(choice); open = false },
                        ) {
                            RadioButton(selected = choice == days, onClick = null)
                            Text(
                                choice?.let { stringResource(R.string.ai_retention_days, it) } ?: stringResource(R.string.ai_retention_forever),
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
