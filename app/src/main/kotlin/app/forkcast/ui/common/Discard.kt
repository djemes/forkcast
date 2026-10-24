package app.forkcast.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.forkcast.R

/**
 * "Discard changes?" (owner, 2026-10-23): every edit screen saves only on Save; Back or Cancel with
 * unsaved changes asks first, without changes it just leaves.
 */
@Composable
fun DiscardChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.discard_title)) },
        text = { Text(stringResource(R.string.discard_body)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard_confirm)) } },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text(stringResource(R.string.discard_keep)) } },
    )
}
