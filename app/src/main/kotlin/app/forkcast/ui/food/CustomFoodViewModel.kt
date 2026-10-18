package app.forkcast.ui.food

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.domain.Gtin
import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.ServingSize
import app.forkcast.core.model.UnitPrefs
import app.forkcast.navigation.CustomFoodDestination
import app.forkcast.ui.format.UnitFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** Form values; nutrients are per serving, as printed on labels. */
data class CustomFoodForm(
    val loaded: Boolean = false,
    val existing: Food? = null,
    val name: String = "",
    val brand: String = "",
    val servingLabel: String = "1 serving",
    val servingGrams: Double? = null,
    val barcode: String = "",
    val perServing: Map<NutrientKey, Double> = emptyMap(),
    val units: UnitPrefs = UnitPrefs.US,
    /** Initial text values for number fields (only set on load). */
    val seed: Int = 0,
) {
    val normalizedBarcode: String? get() = barcode.takeIf { it.isNotBlank() }?.let { Gtin.normalize(it) }
    val barcodeInvalid: Boolean get() = barcode.isNotBlank() && normalizedBarcode == null
    val canSave: Boolean
        get() = name.isNotBlank() && (servingGrams ?: 0.0) > 0 && (perServing[NutrientKey.ENERGY_KCAL] ?: -1.0) >= 0 && !barcodeInvalid
}

sealed interface CustomFoodEffect {
    data class Saved(val foodId: String, val isNew: Boolean) : CustomFoodEffect
    data object Deleted : CustomFoodEffect
}

/** GTIN-14 back to the code printed on the package (UPC-A 12 digits, EAN-13, or EAN-8). */
private fun printedCode(gtin14: String): String = when {
    gtin14.startsWith("000000") -> gtin14.takeLast(8)
    gtin14.startsWith("00") -> gtin14.takeLast(12)
    gtin14.startsWith("0") -> gtin14.takeLast(13)
    else -> gtin14
}

@HiltViewModel
class CustomFoodViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val foods: FoodRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val args: CustomFoodDestination = savedState.toRoute()
    private val _form = MutableStateFlow(CustomFoodForm())
    val form: StateFlow<CustomFoodForm> = _form.asStateFlow()
    private val effectChannel = Channel<CustomFoodEffect>(Channel.BUFFERED)
    val effects: Flow<CustomFoodEffect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val units = settings.settings.first().units ?: UnitFormatter.defaultsFor(Locale.getDefault())
            val food = args.foodId?.let { foods.get(it) }
            _form.value = if (food == null) {
                CustomFoodForm(loaded = true, barcode = args.barcode.orEmpty(), units = units, seed = 1)
            } else {
                val serving = food.servingSizes.firstOrNull() ?: ServingSize("100 g", 100.0)
                CustomFoodForm(
                    loaded = true,
                    existing = food,
                    name = food.name,
                    brand = food.brand.orEmpty(),
                    servingLabel = serving.label,
                    servingGrams = serving.grams,
                    barcode = food.barcode?.let(::printedCode).orEmpty(),
                    perServing = food.nutrientsPer100g.mapValues { (_, v) -> v * serving.grams / 100.0 },
                    units = units,
                    seed = 1,
                )
            }
        }
    }

    fun update(change: CustomFoodForm.() -> CustomFoodForm) = _form.update(change)

    fun setNutrient(key: NutrientKey, value: Double?) = _form.update {
        it.copy(perServing = if (value == null) it.perServing - key else it.perServing + (key to value))
    }

    fun save() {
        val f = _form.value
        if (!f.canSave) return
        val grams = f.servingGrams ?: return
        viewModelScope.launch {
            val food = Food(
                id = f.existing?.id.orEmpty(),
                name = f.name.trim(),
                brand = f.brand.trim().ifEmpty { null },
                source = f.existing?.source?.takeIf { it == FoodSource.LABEL } ?: FoodSource.CUSTOM,
                sourceId = null,
                barcode = f.normalizedBarcode,
                servingSizes = listOf(ServingSize(f.servingLabel.trim().ifEmpty { "1 serving" }, grams)),
                nutrientsPer100g = FoodLogging.perServingToPer100g(f.perServing, grams),
                isFavorite = f.existing?.isFavorite ?: false,
                lastUsedAt = f.existing?.lastUsedAt,
                lastQuantity = f.existing?.lastQuantity,
                lastServingLabel = f.existing?.lastServingLabel,
            )
            val id = foods.saveCustom(food)
            effectChannel.send(CustomFoodEffect.Saved(id, isNew = f.existing == null))
        }
    }

    fun delete() {
        val id = _form.value.existing?.id ?: return
        viewModelScope.launch {
            foods.delete(id)
            effectChannel.send(CustomFoodEffect.Deleted)
        }
    }
}
