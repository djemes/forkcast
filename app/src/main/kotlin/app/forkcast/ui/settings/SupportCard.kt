package app.forkcast.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.BuildConfig
import app.forkcast.R
import app.forkcast.core.data.repository.SupportCardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether the Ko-fi card shows at the top of Settings (owner, 2026-10-30). */
@HiltViewModel
class SupportCardViewModel @Inject constructor(
    private val repository: SupportCardRepository,
) : ViewModel() {

    val shown: StateFlow<Boolean> = repository.shown(BuildConfig.VERSION_NAME)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.isShown(BuildConfig.VERSION_NAME))

    fun hide() {
        viewModelScope.launch { repository.hide(BuildConfig.VERSION_NAME) }
    }

    fun undoHide() {
        viewModelScope.launch { repository.show() }
    }
}

/** The Ko-fi card's state for [SettingsScreen]; the screen has no card when this is null (the Play build). */
data class SupportCardState(
    val shown: Boolean,
    /** The Ko-fi page; empty until it exists, and then a tap says "Coming soon". */
    val url: String,
    val onHide: () -> Unit = {},
    val onUndoHide: () -> Unit = {},
)

/**
 * Opens the Ko-fi page in the browser, or says "Coming soon" in [snackbar] while [url] is empty.
 * Nothing else: no reminders, pop-ups or nags anywhere (roadmap 2.0 Phase 4, task 4.6).
 */
@Composable
internal fun rememberDonationOpener(url: String, snackbar: SnackbarHostState): () -> Unit {
    val uri = LocalUriHandler.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    return {
        if (url.isBlank()) {
            scope.launch { snackbar.showSnackbar(resources.getString(R.string.support_coming_soon)) }
        } else {
            uri.openUri(url)
        }
    }
}

/**
 * About one settings row tall (DESIGN.md, Settings): Ko-fi's cup as Ko-fi publishes it, "Support on
 * Ko-fi" with one line under it, and × to hide it. The card is the theme's surface (white in light
 * theme, the dark surface in dark, never a white block), with the meal groups' 20 dp corners.
 */
@Composable
internal fun SupportCard(onOpen: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(painterResource(R.drawable.kofi_cup), contentDescription = null, modifier = Modifier.size(36.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.support_kofi), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.support_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onHide) {
            Icon(
                painterResource(R.drawable.ic_close),
                stringResource(R.string.support_card_hide),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
