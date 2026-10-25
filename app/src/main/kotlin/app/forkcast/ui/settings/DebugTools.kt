package app.forkcast.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.di.DebugAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A developer tool as Settings › Advanced lists it. */
data class DebugTool(@StringRes val label: Int, @StringRes val summary: Int, val run: () -> Unit)

data class DebugToolsUiState(
    val tools: List<DebugAction> = emptyList(),
    val running: Boolean = false,
    /** What the last tool said when it finished, shown once. */
    @StringRes val message: Int? = null,
)

sealed interface DebugToolsEvent {
    data class Run(val action: DebugAction) : DebugToolsEvent
    data object MessageShown : DebugToolsEvent
}

/** The tools debug builds bind ([DebugAction]); none in release builds, so nothing shows there. */
@HiltViewModel
class DebugToolsViewModel @Inject constructor(actions: Set<@JvmSuppressWildcards DebugAction>) : ViewModel() {

    private val _state = MutableStateFlow(DebugToolsUiState(tools = actions.sortedBy { it.label }))
    val state: StateFlow<DebugToolsUiState> = _state.asStateFlow()

    fun onEvent(event: DebugToolsEvent) {
        when (event) {
            is DebugToolsEvent.Run -> {
                if (_state.value.running) return
                _state.update { it.copy(running = true) }
                viewModelScope.launch {
                    val message = event.action.run()
                    _state.update { it.copy(running = false, message = message) }
                }
            }
            DebugToolsEvent.MessageShown -> _state.update { it.copy(message = null) }
        }
    }
}
