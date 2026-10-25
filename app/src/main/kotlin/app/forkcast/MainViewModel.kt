package app.forkcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.repository.ConsentRepository
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.model.AppSettings
import app.forkcast.ui.legal.Agreement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [needsAgreement]: the terms come before anything else (roadmap 2.0, task 4.1); [acceptedVersion]
 * is the older version accepted on this phone, if any, for the "What changed" note.
 */
data class AppStartState(
    val settings: AppSettings,
    val needsOnboarding: Boolean,
    val needsAgreement: Boolean = false,
    val acceptedVersion: Int? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    profileRepository: ProfileRepository,
    private val consent: ConsentRepository,
) : ViewModel() {

    /** Null until settings and profile are read; the splash screen stays up until then. */
    val start: StateFlow<AppStartState?> =
        combine(settingsRepository.settings, profileRepository.profile, profileRepository.activeGoal, consent.agreement) { s, p, g, a ->
            AppStartState(
                s,
                needsOnboarding = p == null || g == null,
                needsAgreement = Agreement.needsAcceptance(a?.version),
                acceptedVersion = a?.version,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun acceptAgreement() {
        viewModelScope.launch { consent.acceptAgreement(Agreement.VERSION, BuildConfig.VERSION_NAME) }
    }
}
