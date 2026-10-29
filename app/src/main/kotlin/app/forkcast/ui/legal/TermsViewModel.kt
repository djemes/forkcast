package app.forkcast.ui.legal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.repository.ConsentRepository
import app.forkcast.core.model.AgreementAcceptance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Settings › About › Terms: which agreement version this phone accepted, and when. */
@HiltViewModel
class TermsViewModel @Inject constructor(consent: ConsentRepository) : ViewModel() {
    val accepted: StateFlow<AgreementAcceptance?> = consent.agreement.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
