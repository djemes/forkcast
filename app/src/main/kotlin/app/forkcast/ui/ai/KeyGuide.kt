package app.forkcast.ui.ai

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.model.ProviderId

/**
 * "How to get an API key" in the profile editor: steps and links for the selected provider.
 * Links and steps were checked against each provider's docs on 2026-10-19 (DECISIONS.md);
 * re-check them when a provider changes its console.
 */
data class KeyGuide(
    @StringRes val title: Int,
    @StringRes val intro: Int,
    val options: List<GuideOption>,
    @StringRes val footer: Int? = null,
    val footerLink: GuideLink? = null,
)

/** One way to get a key; [title] is null when the provider has only one. */
data class GuideOption(
    @StringRes val title: Int?,
    val steps: List<GuideStep>,
    @StringRes val note: Int? = null,
    /** Offered as "Use this server URL" (OpenAI-compatible only). */
    val serverUrl: String? = null,
)

data class GuideStep(@StringRes val text: Int, val link: GuideLink? = null)

data class GuideLink(@StringRes val label: Int, val url: String)

fun keyGuide(provider: ProviderId): KeyGuide = when (provider) {
    ProviderId.GEMINI -> KeyGuide(
        title = R.string.key_guide_gemini_title,
        intro = R.string.key_guide_gemini_intro,
        options = listOf(
            GuideOption(
                title = R.string.key_guide_gemini_studio,
                steps = listOf(
                    GuideStep(R.string.key_guide_gemini_studio_1, GuideLink(R.string.key_guide_open_ai_studio, "https://aistudio.google.com/apikey")),
                    GuideStep(R.string.key_guide_gemini_studio_2),
                    GuideStep(R.string.key_guide_copy_paste),
                ),
                note = R.string.key_guide_gemini_studio_note,
            ),
            GuideOption(
                title = R.string.key_guide_gemini_cloud,
                steps = listOf(
                    GuideStep(
                        R.string.key_guide_gemini_cloud_1,
                        GuideLink(R.string.key_guide_open_gemini_api, "https://console.cloud.google.com/apis/library/generativelanguage.googleapis.com"),
                    ),
                    GuideStep(R.string.key_guide_gemini_cloud_2, GuideLink(R.string.key_guide_open_credentials, "https://console.cloud.google.com/apis/credentials")),
                    GuideStep(R.string.key_guide_gemini_cloud_3),
                    GuideStep(R.string.key_guide_copy_paste),
                ),
            ),
        ),
        footer = R.string.key_guide_gemini_billing,
        footerLink = GuideLink(R.string.key_guide_open_billing, "https://console.cloud.google.com/billing"),
    )
    ProviderId.CLAUDE -> KeyGuide(
        title = R.string.key_guide_claude_title,
        intro = R.string.key_guide_claude_intro,
        options = listOf(
            GuideOption(
                title = null,
                steps = listOf(
                    GuideStep(R.string.key_guide_claude_1, GuideLink(R.string.key_guide_open_claude_console, "https://platform.claude.com/")),
                    GuideStep(R.string.key_guide_claude_2, GuideLink(R.string.key_guide_open_billing, "https://platform.claude.com/settings/billing")),
                    GuideStep(R.string.key_guide_claude_3, GuideLink(R.string.key_guide_open_limits, "https://platform.claude.com/settings/limits")),
                    GuideStep(R.string.key_guide_claude_4, GuideLink(R.string.key_guide_open_api_keys, "https://platform.claude.com/settings/keys")),
                    GuideStep(R.string.key_guide_claude_5),
                ),
            ),
        ),
    )
    ProviderId.OPENAI_COMPAT -> KeyGuide(
        title = R.string.key_guide_openai_title,
        intro = R.string.key_guide_openai_intro,
        options = listOf(
            GuideOption(
                title = R.string.key_guide_openai_openai,
                steps = listOf(
                    GuideStep(R.string.key_guide_openai_1, GuideLink(R.string.key_guide_open_openai, "https://platform.openai.com/")),
                    GuideStep(R.string.key_guide_openai_2, GuideLink(R.string.key_guide_open_billing, "https://platform.openai.com/settings/organization/billing/overview")),
                    GuideStep(R.string.key_guide_openai_3, GuideLink(R.string.key_guide_open_api_keys, "https://platform.openai.com/api-keys")),
                    GuideStep(R.string.key_guide_pick_vision_model),
                ),
                serverUrl = "https://api.openai.com",
            ),
            GuideOption(
                title = R.string.key_guide_openai_openrouter,
                steps = listOf(
                    GuideStep(R.string.key_guide_openrouter_1, GuideLink(R.string.key_guide_open_openrouter, "https://openrouter.ai/keys")),
                    GuideStep(R.string.key_guide_openrouter_2, GuideLink(R.string.key_guide_open_credits, "https://openrouter.ai/settings/credits")),
                    GuideStep(R.string.key_guide_pick_vision_model),
                ),
                serverUrl = "https://openrouter.ai/api",
            ),
            GuideOption(
                title = R.string.key_guide_openai_local,
                steps = listOf(
                    GuideStep(R.string.key_guide_local_1, GuideLink(R.string.key_guide_open_ollama, "https://ollama.com/download")),
                    GuideStep(R.string.key_guide_local_2, GuideLink(R.string.key_guide_open_ollama_faq, "https://docs.ollama.com/faq")),
                    GuideStep(R.string.key_guide_local_3),
                ),
                note = R.string.key_guide_local_note,
            ),
        ),
    )
}

/** [onUseServerUrl] fills the profile's server URL (OpenAI-compatible options) and closes the guide. */
@Composable
fun KeyGuideDialog(provider: ProviderId, onUseServerUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val guide = keyGuide(provider)
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(guide.title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(guide.intro), style = MaterialTheme.typography.bodyMedium)
                for (option in guide.options) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        option.title?.let {
                            Text(
                                stringResource(it),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                            )
                        }
                        option.steps.forEachIndexed { i, step ->
                            Row {
                                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 24.dp))
                                Column {
                                    Text(stringResource(step.text), style = MaterialTheme.typography.bodyMedium)
                                    step.link?.let { link ->
                                        TextButton(onClick = { uri.openUri(link.url) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                            Text(stringResource(link.label))
                                        }
                                    }
                                }
                            }
                        }
                        option.note?.let {
                            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        option.serverUrl?.let { url ->
                            OutlinedButton(onClick = { onUseServerUrl(url) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.key_guide_use_url, url))
                            }
                        }
                    }
                }
                guide.footer?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    guide.footerLink?.let { link ->
                        TextButton(onClick = { uri.openUri(link.url) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(link.label)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.key_guide_done)) } },
    )
}
