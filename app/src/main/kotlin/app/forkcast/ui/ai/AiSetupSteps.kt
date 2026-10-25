package app.forkcast.ui.ai

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.GuideLinks
import app.forkcast.R
import app.forkcast.core.model.ProviderId
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/* The AI setup guide's first three steps: choose a service, what it means, how to get a key. */

@Composable
internal fun ChooseStep(onEvent: (AiSetupEvent) -> Unit, onAdvanced: () -> Unit) {
    Title(stringResource(R.string.setup_choose_title), stringResource(R.string.setup_choose_body))
    ServiceCard(
        title = stringResource(R.string.setup_gemini_title),
        body = stringResource(R.string.setup_gemini_body),
        badge = stringResource(R.string.setup_recommended),
        onClick = { onEvent(AiSetupEvent.Choose(ProviderId.GEMINI)) },
    )
    ServiceCard(
        title = stringResource(R.string.setup_claude_title),
        body = stringResource(R.string.setup_claude_body),
        badge = null,
        onClick = { onEvent(AiSetupEvent.Choose(ProviderId.CLAUDE)) },
    )
    // Experts only, collapsed: the full profile editor.
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.setup_advanced_title))
            Icon(
                painterResource(if (advanced) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (advanced) {
            Text(stringResource(R.string.setup_advanced_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp))
            OutlinedButton(onClick = onAdvanced, modifier = Modifier.padding(12.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.setup_advanced_open))
            }
        }
    }
}

@Composable
private fun ServiceCard(title: String, body: String, badge: String?, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
        badge?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AboutStep(provider: ProviderId) {
    val gemini = provider == ProviderId.GEMINI
    val uri = LocalUriHandler.current
    Title(stringResource(R.string.setup_about_title), stringResource(if (gemini) R.string.setup_gemini_time else R.string.setup_claude_time))
    Text(stringResource(R.string.setup_about_key, serviceName(provider)), style = MaterialTheme.typography.bodyLarge)
    Fact(R.string.setup_about_sent_title, stringResource(R.string.setup_about_sent))
    Fact(R.string.setup_about_cost_title, stringResource(if (gemini) R.string.setup_gemini_cost else R.string.setup_claude_cost))
    Fact(R.string.setup_about_privacy_title, stringResource(if (gemini) R.string.setup_gemini_privacy else R.string.setup_claude_privacy))
    if (gemini) Text(stringResource(R.string.setup_gemini_region), style = MaterialTheme.typography.bodyMedium)
    Fact(R.string.setup_about_terms_title, stringResource(if (gemini) R.string.setup_gemini_terms else R.string.setup_claude_terms))
    TextButton(
        onClick = { uri.openUri(if (gemini) GuideLinks.GEMINI_TERMS else GuideLinks.CLAUDE_TERMS) },
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(stringResource(if (gemini) R.string.setup_open_gemini_terms else R.string.setup_open_claude_terms)) }
}

@Composable
private fun Fact(@StringRes title: Int, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

internal data class SetupLink(@StringRes val label: Int, val url: String)

private fun keySteps(provider: ProviderId): List<Pair<Int, SetupLink?>> = when (provider) {
    ProviderId.CLAUDE -> listOf(
        R.string.setup_claude_step_1 to SetupLink(R.string.setup_open_console, GuideLinks.CLAUDE_CONSOLE),
        R.string.setup_claude_step_2 to SetupLink(R.string.setup_open_billing, GuideLinks.CLAUDE_BILLING),
        R.string.setup_claude_step_3 to SetupLink(R.string.setup_open_limits, GuideLinks.CLAUDE_LIMITS),
        R.string.setup_claude_step_4 to SetupLink(R.string.setup_open_keys, GuideLinks.CLAUDE_KEYS),
        R.string.setup_claude_step_5 to null,
    )
    else -> listOf(
        R.string.setup_gemini_step_1 to SetupLink(R.string.setup_open_ai_studio, GuideLinks.AI_STUDIO_KEYS),
        R.string.setup_gemini_step_2 to null,
        R.string.setup_gemini_step_3 to null,
    )
}

@Composable
internal fun KeyStepsStep(provider: ProviderId) {
    val uri = LocalUriHandler.current
    Title(stringResource(R.string.setup_key_title), stringResource(R.string.setup_key_body))
    keySteps(provider).forEachIndexed { i, (text, link) ->
        Row {
            Text("${i + 1}.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 28.dp))
            Column {
                Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
                link?.let { l ->
                    OutlinedButton(onClick = { uri.openUri(l.url) }, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
                        Text(stringResource(l.label))
                    }
                }
            }
        }
    }
}
