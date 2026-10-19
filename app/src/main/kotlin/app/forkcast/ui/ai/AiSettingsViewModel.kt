package app.forkcast.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.repository.AiProfileStatus
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.MonthUsage
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.model.AiSettings
import app.forkcast.core.model.ModelPrice
import app.forkcast.core.network.ai.ConnectionReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject

sealed interface TestState {
    data object Running : TestState
    data class Ok(val report: ConnectionReport) : TestState
    data class Failed(val error: Throwable?) : TestState
}

data class AiSettingsUiState(
    val loaded: Boolean = false,
    val settings: AiSettings = AiSettings(),
    val profiles: List<AiProfileStatus> = emptyList(),
    val usage: MonthUsage? = null,
    val tests: Map<String, TestState> = emptyMap(),
)

sealed interface AiSettingsEvent {
    data class SetEnabled(val enabled: Boolean) : AiSettingsEvent
    data class SetProfileEnabled(val id: String, val enabled: Boolean) : AiSettingsEvent
    /** Move within the list, from the drag handle or the accessibility actions. */
    data class Move(val from: Int, val to: Int) : AiSettingsEvent
    data class Test(val id: String) : AiSettingsEvent
    data class SetPersonalContext(val text: String) : AiSettingsEvent
    data class SetAutoAccept(val enabled: Boolean) : AiSettingsEvent
    data class SetRetention(val days: Int?) : AiSettingsEvent
    data class SetPrice(val model: String, val price: ModelPrice?) : AiSettingsEvent
}

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val ai: AiRepository,
    private val settingsRepo: SettingsRepository,
    clock: Clock,
) : ViewModel() {

    private val tests = MutableStateFlow<Map<String, TestState>>(emptyMap())

    val state: StateFlow<AiSettingsUiState> = combine(
        settingsRepo.settings.map { it.ai },
        ai.profiles,
        ai.monthUsage(YearMonth.now(clock)),
        tests,
    ) { settings, profiles, usage, t ->
        AiSettingsUiState(loaded = true, settings = settings, profiles = profiles, usage = usage, tests = t)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUiState())

    fun onEvent(event: AiSettingsEvent) {
        viewModelScope.launch {
            when (event) {
                is AiSettingsEvent.SetEnabled -> settingsRepo.updateAi { it.copy(enabled = event.enabled) }
                is AiSettingsEvent.SetProfileEnabled -> ai.setEnabled(event.id, event.enabled)
                is AiSettingsEvent.Move -> {
                    val ids = state.value.profiles.map { it.profile.id }.toMutableList()
                    if (event.from !in ids.indices || event.to !in ids.indices) return@launch
                    ids.add(event.to, ids.removeAt(event.from))
                    ai.reorder(ids)
                }
                is AiSettingsEvent.Test -> {
                    tests.update { it + (event.id to TestState.Running) }
                    val result = ai.test(event.id)
                    tests.update {
                        it + (event.id to result.fold({ r -> TestState.Ok(r) }, { e -> TestState.Failed(e) }))
                    }
                }
                is AiSettingsEvent.SetPersonalContext -> settingsRepo.updateAi { it.copy(personalContext = event.text) }
                is AiSettingsEvent.SetAutoAccept -> settingsRepo.updateAi { it.copy(autoAccept = event.enabled) }
                is AiSettingsEvent.SetRetention -> settingsRepo.updateAi { it.copy(photoRetentionDays = event.days) }
                is AiSettingsEvent.SetPrice -> settingsRepo.updateAi {
                    it.copy(priceOverrides = if (event.price == null) it.priceOverrides - event.model else it.priceOverrides + (event.model to event.price))
                }
            }
        }
    }
}
