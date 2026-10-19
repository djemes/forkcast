package app.forkcast.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.health.HealthPermissions
import app.forkcast.core.data.health.HealthRepository
import app.forkcast.core.data.health.HealthStatus
import app.forkcast.core.data.health.SyncReason
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.model.HealthSettings
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

data class HealthUiState(
    val status: HealthStatus,
    val units: UnitPrefs,
    val profile: UserProfile?,
    val syncing: Boolean,
) {
    /** What the current choices need from Health Connect. */
    val wanted: Set<String>
        get() = HealthPermissions.wanted(status.settings, status.historySupported, status.backgroundSupported)

    /** Wanted but not granted (empty until the first permission check). */
    val missing: Set<String> get() = if (status.granted.isEmpty() && !status.connected) emptySet() else wanted - status.granted

    /** Health Connect's height, when it differs from the profile by a centimeter or more. */
    val heightSuggestion: Double?
        get() = status.heightCm?.takeIf { hc -> profile != null && abs(hc - profile.heightCm) >= 1.0 }
}

@HiltViewModel
class HealthConnectViewModel @Inject constructor(
    private val health: HealthRepository,
    private val profiles: ProfileRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val syncing = MutableStateFlow(false)

    val state: StateFlow<HealthUiState?> = combine(health.status, settings.settings, profiles.profile, syncing) { status, s, p, busy ->
        HealthUiState(status, s.units ?: UnitPrefs.US, p, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refresh() = viewModelScope.launch { health.refresh() }

    suspend fun permissionsToRequest(): Set<String> = health.permissionsToRequest()

    fun onPermissionsResult(granted: Set<String>) = viewModelScope.launch { busy { health.onPermissionsResult(granted) } }

    fun update(change: (HealthSettings) -> HealthSettings) = viewModelScope.launch { health.updateSettings(change) }

    fun syncNow() = viewModelScope.launch { busy { health.sync(SyncReason.MANUAL) } }

    fun disconnect() = viewModelScope.launch { health.disconnect() }

    fun useHeight(cm: Double) = viewModelScope.launch {
        val p = profiles.profile.first() ?: return@launch
        profiles.saveProfile(p.copy(heightCm = cm))
    }

    private suspend fun busy(block: suspend () -> Unit) {
        syncing.value = true
        try {
            block()
        } finally {
            syncing.value = false
        }
    }
}
