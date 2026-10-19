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
import androidx.compose.runtime.Immutable
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

/**
 * A saved key, masked by default, with show/hide and copy (SPEC §4.7, owner request 2026-10-19).
 * Read-only: replacing a key happens in a separate field.
 */
@Composable
fun SavedKeyField(label: String, secret: SecretText, modifier: Modifier = Modifier) {
    var visible by remember(secret) { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val shownState = stringResource(if (visible) R.string.key_state_shown else R.string.key_state_hidden)
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_500)
            copied = false
        }
    }
    OutlinedTextField(
        value = if (visible) secret.reveal() else secret.masked,
        onValueChange = {},
        readOnly = true,
        // Shown in full, a long key wraps instead of scrolling out of view.
        singleLine = !visible,
        label = { Text(label) },
        supportingText = { Text(stringResource(if (copied) R.string.key_copied else R.string.key_saved_encrypted)) },
        trailingIcon = {
            Row {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        stringResource(if (visible) R.string.fdb_hide_key else R.string.fdb_show_key),
                    )
                }
                IconButton(onClick = { scope.launch { clipboard.copySensitive(label, secret.reveal()); copied = true } }) {
                    Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.key_copy))
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = shownState },
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
