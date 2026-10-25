package app.forkcast.ui.help

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.R

/**
 * Opens Help at an entry. Provided once by the nav host, so an error deep inside a card links to
 * help without every screen passing a callback down (tests and previews get a no-op).
 */
val LocalHelp = staticCompositionLocalOf<(HelpEntry) -> Unit> { {} }

/**
 * The small "Help" next to an error; screen readers hear which question it opens. [color]: on an
 * error container, its on-color, so the link keeps its contrast. [onOpen] runs first (a dialog
 * closes itself, so it isn't still open on the way back).
 */
@Composable
fun HelpLink(entry: HelpEntry, modifier: Modifier = Modifier, color: Color = Color.Unspecified, onOpen: () -> Unit = {}) {
    val open = LocalHelp.current
    val description = stringResource(R.string.help_link_description, stringResource(entry.question))
    TextButton(
        onClick = { onOpen(); open(entry) },
        colors = if (color == Color.Unspecified) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColors(contentColor = color),
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
    ) {
        Text(stringResource(R.string.help_link))
    }
}

/** An error snackbar with a "Help" action, for errors that only show as a snackbar. */
suspend fun SnackbarHostState.showError(message: String, helpLabel: String, onHelp: () -> Unit) {
    val result = showSnackbar(message, actionLabel = helpLabel, withDismissAction = true, duration = SnackbarDuration.Long)
    if (result == SnackbarResult.ActionPerformed) onHelp()
}
