package app.forkcast.ui.common

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.forkcast.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A saved API key held for the screen (show/hide, copy). It never prints itself, so it can't end
 * up in a log line, a crash message or a test failure. The value stays encrypted at rest; this
 * copy lives only as long as the screen.
 */
@Immutable
class SecretText(private val value: String) {
    fun reveal(): String = value

    /** "••••••••••••3xYz": the last four characters are enough to tell keys apart. */
    val masked: String
        get() = if (value.length < MIN_FOR_HINT) MASK.repeat(8) else MASK.repeat(12) + value.takeLast(4)

    override fun toString() = "SecretText(•••)"
    override fun equals(other: Any?) = other is SecretText && other.value == value
    override fun hashCode() = value.hashCode()

    private companion object {
        const val MASK = "•"
        /** Shorter keys show no characters at all. */
        const val MIN_FOR_HINT = 12
    }
}

/** How long one unlock lets keys be shown or copied on a screen (owner, 2026-10-23: about a minute). */
const val KEY_UNLOCK_MS = 60_000L

/**
 * One unlock for a screen: every saved key on it can be shown and copied until [until], or until
 * the screen is left or the app goes to the background. Screens with several key fields can
 * provide one through [LocalKeyGate]; otherwise each field keeps its own.
 */
@Stable
class KeyGate {
    var until by mutableLongStateOf(0L)
        private set

    fun isOpen(now: Long = System.currentTimeMillis()): Boolean = now < until
    fun open(now: Long = System.currentTimeMillis()) { until = now + KEY_UNLOCK_MS }
    fun close() { until = 0L }
}

val LocalKeyGate = staticCompositionLocalOf<KeyGate?> { null }

/**
 * A saved key, masked by default, with show/hide and copy (SPEC §4.7, owner request 2026-10-19).
 * Showing or copying asks Android's unlock prompt first (fingerprint or face, else the screen lock;
 * owner, 2026-10-23); the key masks itself again after [KEY_UNLOCK_MS], or when the screen or app
 * is left. Read-only: replacing a key happens in a separate field and needs no unlock.
 * The only place the app shows a saved secret, so every key field behaves the same.
 */
@Composable
fun SavedKeyField(label: String, secret: SecretText, modifier: Modifier = Modifier) {
    val gate = LocalKeyGate.current ?: remember { KeyGate() }
    var visible by remember(secret) { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var noLock by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shownState = stringResource(if (visible) R.string.key_state_shown else R.string.key_state_hidden)
    val unlockTitle = stringResource(R.string.key_unlock_title)
    val unlockSubtitle = stringResource(R.string.key_unlock_subtitle, label)
    // "Copied, clears in a minute" with Clear now, for as long as the clipboard would hold it.
    LaunchedEffect(copied) {
        if (copied) {
            delay(KeyClipboard.CLEAR_AFTER_MS)
            copied = false
        }
    }
    // Masks again when the unlock runs out, and on leaving the app.
    LaunchedEffect(gate.until) {
        val left = gate.until - System.currentTimeMillis()
        if (left > 0) delay(left)
        visible = false
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        gate.close()
        visible = false
    }
    /** Runs [action] now if this screen is unlocked, else after Android's unlock prompt succeeds. */
    fun unlocked(action: () -> Unit) {
        when {
            gate.isOpen() -> action()
            !DeviceAuth.hasScreenLock(context) -> noLock = true
            else -> DeviceAuth.authenticate(context, unlockTitle, unlockSubtitle, onSuccess = { gate.open(); action() })
        }
    }
    if (noLock) NoScreenLockDialog(onDismiss = { noLock = false })
    SecureWhile(visible)
    OutlinedTextField(
        value = if (visible) secret.reveal() else secret.masked,
        onValueChange = {},
        readOnly = true,
        // Shown in full, a long key wraps instead of scrolling out of view.
        singleLine = !visible,
        label = { Text(label) },
        supportingText = {
            Text(
                stringResource(
                    when {
                        copied -> R.string.key_copied
                        gate.isOpen() -> R.string.key_saved_encrypted
                        else -> R.string.key_locked
                    },
                ),
            )
        },
        trailingIcon = {
            Row {
                IconButton(onClick = { if (visible) visible = false else unlocked { visible = true } }) {
                    Icon(
                        painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        stringResource(if (visible) R.string.fdb_hide_key else R.string.fdb_show_key),
                    )
                }
                IconButton(onClick = {
                    unlocked {
                        scope.launch {
                            clipboard.copySensitive(label, secret.reveal())
                            KeyClipboard.clearSoon(context, secret.reveal())
                            copied = true
                        }
                    }
                }) {
                    Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.key_copy))
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = shownState },
    )
    if (copied) {
        TextButton(onClick = { KeyClipboard.clearNow(context, secret.reveal()); copied = false }) {
            Text(stringResource(R.string.key_clear_clipboard))
        }
    }
}

/**
 * No screen lock on the phone (owner, 2026-10-23, Claude's call): what [DeviceAuth] guards stays
 * locked (saved keys stay hidden, old backups stay), because anyone holding the phone could use
 * it, and the dialog says how to set a lock. [body] says what needs the lock.
 */
@Composable
fun NoScreenLockDialog(onDismiss: () -> Unit, body: String = stringResource(R.string.key_no_lock_body)) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_no_lock_title)) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(stringResource(R.string.key_open_security)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Copies [text] flagged as sensitive, so Android 13+ hides it in the clipboard preview and
 * keyboards don't suggest it.
 */
suspend fun Clipboard.copySensitive(label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    // ClipDescription.EXTRA_IS_SENSITIVE (API 33); older versions ignore the extra.
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    setClipEntry(ClipEntry(clip))
}

/**
 * A copied key doesn't stay on the clipboard (REVIEW-2 S2): [clearSoon] empties it after
 * [CLEAR_AFTER_MS] if it still holds that key, and [clearNow] does it at once. Runs on the main
 * looper, so leaving the screen doesn't cancel it.
 */
object KeyClipboard {
    const val CLEAR_AFTER_MS = 60_000L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun clearSoon(context: android.content.Context, key: String) {
        val app = context.applicationContext
        handler.postDelayed({ clearIfStill(app, key) }, CLEAR_AFTER_MS)
    }

    fun clearNow(context: android.content.Context, key: String) = clearIfStill(context.applicationContext, key)

    private fun clearIfStill(context: android.content.Context, key: String) {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
        // Only our own copy: if the user copied something else since, it stays.
        val current = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull()
        if (current == key) runCatching { cm.clearPrimaryClip() }
    }
}
