package app.forkcast.ui.ai

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.network.ai.AiError
import app.forkcast.core.model.ProviderId

/**
 * Result of a connection test, shared by the profile list and the editor so both read the same:
 * a check and "Connected to … in N ms", or the error with the provider's HTTP status and code,
 * then the provider's own words in small text.
 */
@Composable
fun TestResult(test: TestState?, provider: ProviderId, modifier: Modifier = Modifier) {
    val res = LocalResources.current
    when (test) {
        is TestState.Ok -> IconLine(
            icon = R.drawable.ic_check_circle,
            text = stringResource(R.string.ai_test_ok, test.report.model, test.report.latencyMs.toInt()),
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
        is TestState.Failed -> Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            IconLine(
                R.drawable.ic_error,
                // A connection test isn't an estimate: say so rather than "will run when back online".
                if (test.error is AiError.Network) res.getString(R.string.ai_err_network_test) else res.aiErrorMessage(test.error, provider),
                MaterialTheme.colorScheme.error,
            )
            aiErrorDetail(test.error)?.let { detail ->
                Text(
                    stringResource(R.string.ai_err_provider_said, provider.shortName(), detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp),
                )
            }
        }
        TestState.Running -> Text(
            stringResource(R.string.ai_testing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        null -> Unit
    }
}

/** A 20 dp icon and wrapping text: status lines never rely on color alone (DESIGN.md §2). */
@Composable
fun IconLine(@DrawableRes icon: Int, text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Number in the fallback order. The first ready profile gets the filled pine circle. */
@Composable
fun PositionBadge(position: Int, first: Boolean) {
    val description = stringResource(R.string.ai_position, position)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .sizeIn(minWidth = 32.dp, minHeight = 32.dp)
            .background(if (first) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            position.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (first) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Status line under a profile: what it does in the chain right now, and what to do about it. */
@Composable
fun RoleLine(role: ProfileRole, pausedUntil: String?, modifier: Modifier = Modifier) {
    val (icon, text, color) = when (role) {
        ProfileRole.FIRST -> Triple(R.drawable.ic_check_circle, stringResource(R.string.ai_role_first), MaterialTheme.colorScheme.primary)
        ProfileRole.BACKUP -> Triple(R.drawable.ic_check_circle, stringResource(R.string.ai_role_backup), MaterialTheme.colorScheme.onSurfaceVariant)
        ProfileRole.OFF -> Triple(R.drawable.ic_remove_circle, stringResource(R.string.ai_role_off), MaterialTheme.colorScheme.onSurfaceVariant)
        ProfileRole.NO_KEY -> Triple(R.drawable.ic_error, stringResource(R.string.ai_role_no_key), MaterialTheme.colorScheme.error)
        ProfileRole.KEY_REJECTED -> Triple(R.drawable.ic_error, stringResource(R.string.ai_role_key_rejected), MaterialTheme.colorScheme.error)
        ProfileRole.PAUSED -> Triple(
            R.drawable.ic_pause_circle,
            stringResource(R.string.ai_cooling_down, pausedUntil.orEmpty()),
            MaterialTheme.colorScheme.tertiary,
        )
    }
    IconLine(icon, text, color, modifier)
}

/**
 * "Delete Gemini 3.8 Flash?" with what happens next. Used from the list menu and the editor;
 * either way the list shows "Deleted …" with Undo afterwards.
 */
@Composable
fun DeleteProfileDialog(name: String, movesUp: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_delete_title, name)) },
        text = {
            Text(
                if (movesUp != null) stringResource(R.string.ai_delete_body_moves_up, movesUp)
                else stringResource(R.string.ai_delete_body),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
