package app.forkcast.ui.today

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
import app.forkcast.R
import app.forkcast.core.data.backup.folder.BackupNotice
import app.forkcast.core.data.backup.folder.FolderBackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Today's backup card (roadmap 2.0, task 1.4): the one-time Drive notice, or the no-backup reminder. */
@HiltViewModel
class BackupNoticeViewModel @Inject constructor(private val backups: FolderBackupRepository) : ViewModel() {
    val notice: StateFlow<BackupNotice?> = backups.notice.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismiss(notice: BackupNotice) = viewModelScope.launch { backups.dismissNotice(notice) }
}

/** "Not now" dismisses (the reminder comes back after another 14 days); the other button opens Backup & data. */
@Composable
fun BackupNoticeCard(notice: BackupNotice, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val drive = notice == BackupNotice.DRIVE_REPLACED
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            stringResource(if (drive) R.string.backup_notice_drive_title else R.string.backup_notice_reminder_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(if (drive) R.string.backup_notice_drive_body else R.string.backup_notice_reminder_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(end = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_notice_not_now)) }
            TextButton(onClick = onOpen) { Text(stringResource(if (drive) R.string.backup_notice_set_up else R.string.backup_notice_back_up)) }
        }
    }
}
