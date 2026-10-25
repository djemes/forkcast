package app.forkcast.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.forkcast.GuideLinks
import app.forkcast.R
import app.forkcast.core.model.AiRecipient
import app.forkcast.core.model.ProviderId

/*
 * Permission before anything goes to an AI service (roadmap 2.0 task 4.2; SPEC §4.7): what is
 * sent, what never is, who receives it, whose terms apply, the free-tier note and the cost.
 * Asked once per recipient (AiRecipient); taken back in Settings › AI services.
 */

/** "Gemini", "Claude", or the server's host ("openrouter.ai") for sentences. */
@Composable
fun recipientName(provider: ProviderId, baseUrl: String?): String =
    if (provider == ProviderId.OPENAI_COMPAT) {
        AiRecipient.host(baseUrl).ifBlank { stringResource(R.string.ai_name_server) }
    } else {
        provider.shortName(LocalResources.current)
    }

/** A stored recipient ("gemini", "openai:openrouter.ai") as a name. */
@Composable
fun recipientName(recipient: String): String = when (recipient) {
    AiRecipient.of(ProviderId.GEMINI, null) -> recipientName(ProviderId.GEMINI, null)
    AiRecipient.of(ProviderId.CLAUDE, null) -> recipientName(ProviderId.CLAUDE, null)
    else -> recipient.substringAfter(':').ifBlank { stringResource(R.string.ai_name_server) }
}

@Composable
private fun ConsentFacts(provider: ProviderId, baseUrl: String?) {
    val uri = LocalUriHandler.current
    val name = recipientName(provider, baseUrl)
    Fact(R.string.consent_sent_title, stringResource(R.string.consent_sent))
    Fact(R.string.consent_never_title, stringResource(R.string.consent_never))
    Fact(
        R.string.consent_who_title,
        when (provider) {
            ProviderId.GEMINI -> stringResource(R.string.consent_who_gemini)
            ProviderId.CLAUDE -> stringResource(R.string.consent_who_claude)
            ProviderId.OPENAI_COMPAT -> stringResource(R.string.consent_who_server, name)
        },
    )
    Fact(
        R.string.consent_terms_title,
        if (provider == ProviderId.OPENAI_COMPAT) stringResource(R.string.consent_terms_server) else stringResource(R.string.consent_terms, name),
    )
    when (provider) {
        ProviderId.GEMINI -> {
            Text(stringResource(R.string.setup_gemini_privacy), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.setup_gemini_region), style = MaterialTheme.typography.bodyMedium)
        }
        ProviderId.CLAUDE -> Text(stringResource(R.string.setup_claude_privacy), style = MaterialTheme.typography.bodyMedium)
        ProviderId.OPENAI_COMPAT -> Unit
    }
    val terms = when (provider) {
        ProviderId.GEMINI -> R.string.setup_open_gemini_terms to GuideLinks.GEMINI_TERMS
        ProviderId.CLAUDE -> R.string.setup_open_claude_terms to GuideLinks.CLAUDE_TERMS
        ProviderId.OPENAI_COMPAT -> null
    }
    terms?.let { (label, url) ->
        OutlinedButton(onClick = { uri.openUri(url) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(label)) }
    }
    Fact(
        R.string.consent_cost_title,
        when (provider) {
            ProviderId.GEMINI -> stringResource(R.string.setup_gemini_cost)
            ProviderId.CLAUDE -> stringResource(R.string.setup_claude_cost)
            ProviderId.OPENAI_COMPAT -> stringResource(R.string.consent_cost_server)
        },
    )
    Text(stringResource(R.string.consent_revoke_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConsentButtons(onAllow: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.consent_not_now)) }
        Button(onClick = onAllow, modifier = Modifier.heightIn(min = 48.dp).testTag("consent_allow")) { Text(stringResource(R.string.consent_allow)) }
    }
}

/** The permission question as a sheet over a settings screen. Dismissing it is "Not now". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConsentSheet(provider: ProviderId, baseUrl: String?, onAllow: () -> Unit, onNotNow: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onNotNow, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
        ) {
            Title(stringResource(R.string.consent_title, recipientName(provider, baseUrl)))
            ConsentFacts(provider, baseUrl)
            ConsentButtons(onAllow, onNotNow)
        }
    }
}

/** The same question as a whole page, before the camera opens for a meal photo. */
@Composable
fun AiConsentPage(provider: ProviderId, baseUrl: String?, onAllow: () -> Unit, onNotNow: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
            ) {
                Title(stringResource(R.string.consent_title, recipientName(provider, baseUrl)))
                ConsentFacts(provider, baseUrl)
            }
            ConsentButtons(onAllow, onNotNow, Modifier.padding(16.dp))
        }
    }
}
