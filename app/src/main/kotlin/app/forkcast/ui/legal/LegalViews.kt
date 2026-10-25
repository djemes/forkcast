package app.forkcast.ui.legal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.forkcast.R

/** A legal text's effective date and sections, for a scrolling column; works offline (it's all resources). */
@Composable
fun LegalDocumentView(document: LegalDocument, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    Column(modifier) {
        if (showTitle) {
            Text(
                stringResource(document.title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        // The legal texts are English in every language (strings_legal.xml); say so in the others.
        if (LocalConfiguration.current.locales[0].language != "en") {
            Text(
                stringResource(R.string.agreement_legal_language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            stringResource(document.effective),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        document.sections.forEach { section ->
            Text(
                stringResource(section.heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp).semantics { heading() },
            )
            section.body.forEach { LegalParagraphs(stringResource(it)) }
        }
    }
}

/** Lines of [text]; those starting with "• " get a hanging indent so wrapped lines line up. */
@Composable
private fun LegalParagraphs(text: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    text.split('\n').forEach { line ->
        if (line.startsWith(BULLET)) {
            Row(Modifier.padding(top = 4.dp)) {
                Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
                Text(linked(line.removePrefix(BULLET), linkColor), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Text(linked(line, linkColor), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Web addresses in the text open in the browser. */
private fun linked(text: String, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    URL.findAll(text).forEach { match ->
        append(text.substring(at, match.range.first))
        val styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
        withLink(LinkAnnotation.Url(match.value, styles)) { append(match.value) }
        at = match.range.last + 1
    }
    append(text.substring(at))
}

private const val BULLET = "• "

/** An https address, without the sentence's closing period or comma. */
private val URL = Regex("https://[^\\s]+[^\\s.,;)]")
