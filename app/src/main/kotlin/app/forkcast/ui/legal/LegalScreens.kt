package app.forkcast.ui.legal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.forkcast.R
import app.forkcast.core.data.repository.ConsentRepository
import app.forkcast.core.model.AgreementAcceptance
import app.forkcast.ui.settings.SettingsScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class TermsViewModel @Inject constructor(consent: ConsentRepository) : ViewModel() {
    val accepted: StateFlow<AgreementAcceptance?> = consent.agreement.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun TermsRoute(onBack: () -> Unit, viewModel: TermsViewModel = hiltViewModel()) {
    val accepted by viewModel.accepted.collectAsStateWithLifecycle()
    TermsScreen(accepted, onBack)
}

/** Settings › About › Terms: the current text, and which version was accepted on this phone and when. */
@Composable
fun TermsScreen(accepted: AgreementAcceptance?, onBack: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale) }
    SettingsScaffold(title = stringResource(R.string.about_terms), onBack = onBack) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                accepted?.let {
                    stringResource(R.string.terms_accepted, it.version, dateFormat.format(Instant.ofEpochMilli(it.acceptedAt).atZone(ZoneId.systemDefault())))
                } ?: stringResource(R.string.terms_not_accepted),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            LegalDocumentView(LegalTexts.terms, Modifier.padding(bottom = 24.dp))
        }
    }
}

/** Settings › About › Privacy policy, from resources so it opens offline (roadmap 2.0, task 4.4). */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    SettingsScaffold(title = stringResource(R.string.about_privacy_policy), onBack = onBack) {
        LegalDocumentView(LegalTexts.privacy, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 24.dp))
    }
}
