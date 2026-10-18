package app.forkcast.ui.scan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.forkcast.core.data.repository.BarcodeLookup
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.domain.Gtin
import app.forkcast.core.network.FoodApiError
import app.forkcast.navigation.ScannerDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ScanState {
    data object Scanning : ScanState
    data class LookingUp(val gtin: String) : ScanState
    data class NotFound(val gtin: String) : ScanState
    data class Failed(val gtin: String, val error: FoodApiError) : ScanState
}

sealed interface ScanEffect {
    /** A code was recognized: buzz and beep. */
    data object Detected : ScanEffect
    data class OpenFood(val foodId: String) : ScanEffect
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val foods: FoodRepository,
) : ViewModel() {

    val args: ScannerDestination = savedState.toRoute()
    private val _state = MutableStateFlow<ScanState>(ScanState.Scanning)
    val state: StateFlow<ScanState> = _state.asStateFlow()
    private val effectChannel = Channel<ScanEffect>(Channel.BUFFERED)
    val effects: Flow<ScanEffect> = effectChannel.receiveAsFlow()

    /** From the camera (already GTIN-14). Ignored while a lookup is showing. */
    fun onScanned(gtin14: String) {
        if (_state.value !is ScanState.Scanning) return
        viewModelScope.launch { effectChannel.send(ScanEffect.Detected) }
        lookup(gtin14)
    }

    /** From "Type barcode". Returns false if the code isn't a valid barcode. */
    fun onTyped(code: String): Boolean {
        val gtin = Gtin.normalize(code) ?: return false
        lookup(gtin)
        return true
    }

    fun retry() {
        (_state.value as? ScanState.Failed)?.let { lookup(it.gtin) }
    }

    fun resume() {
        _state.value = ScanState.Scanning
    }

    private fun lookup(gtin14: String) {
        _state.value = ScanState.LookingUp(gtin14)
        viewModelScope.launch {
            when (val r = foods.lookupBarcode(gtin14)) {
                is BarcodeLookup.Found -> {
                    _state.value = ScanState.Scanning
                    effectChannel.send(ScanEffect.OpenFood(r.foodId))
                }
                BarcodeLookup.NotFound -> _state.value = ScanState.NotFound(gtin14)
                is BarcodeLookup.Failed -> _state.value = ScanState.Failed(gtin14, r.error)
            }
        }
    }
}
