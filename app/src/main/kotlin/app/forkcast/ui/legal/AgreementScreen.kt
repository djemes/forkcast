package app.forkcast.ui.legal

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.forkcast.R

/**
 * The Terms of Use and Disclaimer before anything else (roadmap 2.0, task 4.1): the summary and
 * the full text, two required confirmations, and Decline, which closes the app. [whatChanged]:
 * the note for someone who accepted an older version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgreementScreen(@StringRes whatChanged: Int?, onAccept: () -> Unit, onDecline: () -> Unit) {
    var adult by rememberSaveable { mutableStateOf(false) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var confirmDecline by rememberSaveable { mutableStateOf(false) }

    if (confirmDecline) {
        AlertDialog(
            onDismissRequest = { confirmDecline = false },
            title = { Text(stringResource(R.string.agreement_decline_title)) },
            text = { Text(stringResource(R.string.agreement_decline_body)) },
            confirmButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.agreement_decline_close)) } },
            dismissButton = { TextButton(onClick = { confirmDecline = false }) { Text(stringResource(R.string.agreement_decline_back)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agreement_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    ConfirmRow(R.string.agreement_confirm_age, adult) { adult = it }
                    ConfirmRow(R.string.agreement_confirm_terms, agreed) { agreed = it }
                    if (!(adult && agreed)) {
                        Text(
                            stringResource(R.string.agreement_accept_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp),
                    ) {
                        TextButton(onClick = { confirmDecline = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.agreement_decline))
                        }
                        Button(onClick = onAccept, enabled = adult && agreed, modifier = Modifier.heightIn(min = 48.dp).testTag("agree")) {
                            Text(stringResource(R.string.agreement_accept))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.agreement_intro), style = MaterialTheme.typography.bodyLarge)
            if (whatChanged != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.agreement_changed_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(stringResource(whatChanged), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            LegalDocumentView(LegalTexts.terms, Modifier.padding(bottom = 16.dp))
        }
    }
}

/** A required confirmation: the whole row toggles and reads as one checkbox. */
@Composable
private fun ConfirmRow(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 8.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
    }
}
