package app.forkcast.ui.today

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.CrashReports
import app.forkcast.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Today's crash card (REVIEW-2 S7): the report from the last time the app closed on an error. */
@HiltViewModel
class CrashReportViewModel @Inject constructor(@ApplicationContext private val app: Context) : ViewModel() {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    init {
        viewModelScope.launch { _pending.value = withContext(Dispatchers.IO) { CrashReports.pending(app) != null } }
    }

    /** Copies the report to the file the user picked, then forgets it. */
    fun save(uri: Uri) = viewModelScope.launch {
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                val report = CrashReports.pending(app) ?: return@runCatching
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(report.readBytes()) } ?: error("no stream")
                CrashReports.discard(app)
            }.isSuccess
        }
        // A failed save keeps the report and the card, so it can be tried again.
        if (saved) _pending.value = false
    }

    fun discard() = viewModelScope.launch {
        withContext(Dispatchers.IO) { CrashReports.discard(app) }
        _pending.value = false
    }
}

/** "Not now" deletes the report; "Save report…" opens Android's file picker. */
@Composable
fun CrashReportCard(onSave: (Uri) -> Unit, onDiscard: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let(onSave) }
    val fileName = stringResource(R.string.crash_file_name)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            stringResource(R.string.crash_card_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.crash_card_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(end = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.crash_discard)) }
            TextButton(onClick = { picker.launch(fileName) }) { Text(stringResource(R.string.crash_save)) }
        }
    }
}
