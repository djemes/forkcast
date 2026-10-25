package app.forkcast.ui.help

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class HelpUiState(
    val query: String = "",
    val expanded: Set<HelpEntry> = emptySet(),
    /** The entry a link opened Help at: scrolled to once, then cleared. */
    val focus: HelpEntry? = null,
)

sealed interface HelpEvent {
    data class Search(val query: String) : HelpEvent
    data class Toggle(val entry: HelpEntry) : HelpEvent
    data object FocusShown : HelpEvent
}

/** Settings › Help. A link opens it at one entry, already expanded. */
@HiltViewModel
class HelpViewModel @Inject constructor(private val savedState: SavedStateHandle) : ViewModel() {

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<HelpUiState> = _state.asStateFlow()

    fun onEvent(event: HelpEvent) {
        when (event) {
            is HelpEvent.Search -> _state.update { it.copy(query = event.query) }
            is HelpEvent.Toggle -> _state.update { s ->
                s.copy(expanded = if (event.entry in s.expanded) s.expanded - event.entry else s.expanded + event.entry)
            }
            HelpEvent.FocusShown -> {
                // Rotation or coming back keeps the place without jumping to the entry again.
                savedState[FOCUS_SHOWN] = true
                _state.update { it.copy(focus = null) }
            }
        }
    }

    private fun initial(): HelpUiState {
        val linked = HelpEntry.byId(savedState.get<String>(ENTRY_ARG))
        val focus = linked?.takeUnless { savedState.get<Boolean>(FOCUS_SHOWN) == true }
        return HelpUiState(expanded = setOfNotNull(linked), focus = focus)
    }

    private companion object {
        /** HelpDestination's property. */
        const val ENTRY_ARG = "entry"
        const val FOCUS_SHOWN = "help_focus_shown"
    }
}
