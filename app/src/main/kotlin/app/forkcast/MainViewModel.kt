package app.forkcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AppStartState(val settings: AppSettings, val needsOnboarding: Boolean)

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    profileRepository: ProfileRepository,
) : ViewModel() {

    /** Null until settings and profile are read; the splash screen stays up until then. */
    val start: StateFlow<AppStartState?> =
        combine(settingsRepository.settings, profileRepository.profile, profileRepository.activeGoal) { s, p, g ->
            AppStartState(s, needsOnboarding = p == null || g == null)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
