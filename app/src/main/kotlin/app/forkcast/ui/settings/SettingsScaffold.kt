package app.forkcast.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.forkcast.ui.common.DiscardChangesDialog
import app.forkcast.ui.common.keyboardAware
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.forkcast.R
import app.forkcast.ui.common.ScreenEntrance

/**
 * Shared top bar + scrolling column for settings screens. [dirty]: the screen has unsaved edits,
 * so Back (arrow or gesture) asks "Discard changes?" first (owner, 2026-10-23).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    dirty: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { confirmDiscard = true }
    if (confirmDiscard) DiscardChangesDialog(onDiscard = { confirmDiscard = false; onBack() }, onKeepEditing = { confirmDiscard = false })
    val back = { if (dirty) confirmDiscard = true else onBack() }
    Scaffold(
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // The keyboard shrinks the scroll area, so the focused field scrolls into view above it.
                .keyboardAware(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Charts and bars fill in the first time this screen is opened, not when coming back (DESIGN.md §8).
            val column = this
            ScreenEntrance { column.content() }
        }
    }
}
