package app.forkcast.ui.scan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import app.forkcast.core.data.repository.BarcodeLookup
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.network.FoodApiError
import app.forkcast.fakes.FakeFoodRepository
import app.forkcast.fakes.FakeSettingsRepository
import app.forkcast.navigation.CustomFoodDestination
import app.forkcast.navigation.ScannerDestination
import app.forkcast.ui.food.CustomFoodEffect
import app.forkcast.ui.food.CustomFoodViewModel
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Scan flow with a fake scanner (SPEC §9): the camera is replaced by calling onScanned directly. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ScanFlowTest {

    @get:Rule val compose = createComposeRule()
    private val foods = FakeFoodRepository()
    private fun vm() = ScannerViewModel(SavedStateHandle(route = ScannerDestination("l", "2026-10-18")), foods)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun foundOpensTheFood() = runTest {
        val id = foods.put(FakeFoodRepository.food("Cheerios", barcode = "00016000275287"))
        val vm = vm()
        vm.effects.test {
            vm.onScanned("00016000275287")
            assertThat(awaitItem()).isEqualTo(ScanEffect.Detected)
            assertThat(awaitItem()).isEqualTo(ScanEffect.OpenFood(id))
        }
        assertThat(vm.state.value).isEqualTo(ScanState.Scanning)
    }

    @Test fun notFoundOffersLabelScan() = runTest {
        val vm = vm()
        vm.onScanned("00012345678905")
        assertThat(vm.state.value).isEqualTo(ScanState.NotFound("00012345678905"))
        // Further frames are ignored while the result shows.
        vm.onScanned("00016000275287")
        assertThat(vm.state.value).isEqualTo(ScanState.NotFound("00012345678905"))
        vm.resume()
        assertThat(vm.state.value).isEqualTo(ScanState.Scanning)
    }

    @Test fun offlineIsAFailureWithRetry() = runTest {
        foods.barcodeResult = { BarcodeLookup.Failed(FoodApiError.Offline()) }
        val vm = vm()
        vm.onScanned("00016000275287")
        assertThat(vm.state.value).isInstanceOf(ScanState.Failed::class.java)
        foods.barcodeResult = { BarcodeLookup.NotFound }
        vm.retry()
        assertThat(vm.state.value).isEqualTo(ScanState.NotFound("00016000275287"))
    }

    @Test fun typedCodesAreValidated() = runTest {
        val vm = vm()
        assertThat(vm.onTyped("016000275288")).isFalse() // bad check digit
        assertThat(vm.onTyped("016000275287")).isTrue()
        assertThat(vm.state.value).isEqualTo(ScanState.NotFound("00016000275287"))
    }

    @Test fun notFoundSheetShowsActions() {
        var label: String? = null
        compose.setContent {
            ForkcastTheme {
                ScannerScreen(ScanState.NotFound("00012345678905"), 0f, analyzer = null, {}, { true }, {}, {}, { label = it }, {})
            }
        }
        compose.onNodeWithText("Not in the databases yet. Scan the nutrition label and we'll save it for next time.").assertIsDisplayed()
        compose.onNodeWithText("Scan label").performClick()
        assertThat(label).isEqualTo("00012345678905")
    }

    @Test fun labelPrefillBecomesALabelFoodWithItsBarcode() = runTest {
        val prefill = LabelPrefill("2/3 cup", 55.0, mapOf("ENERGY_KCAL" to 230.0, "PROTEIN_G" to 3.0)).encode()
        val vm = CustomFoodViewModel(
            SavedStateHandle(route = CustomFoodDestination(barcode = "00012345678905", mealSlotId = "l", date = "2026-10-18", prefill = prefill)),
            foods, FakeSettingsRepository(),
        )
        val form = vm.form.value
        assertThat(form.fromLabel).isTrue()
        assertThat(form.barcode).isEqualTo("012345678905")
        assertThat(form.servingGrams).isEqualTo(55.0)
        vm.update { copy(name = "Granola") }
        vm.effects.test {
            vm.save()
            val saved = awaitItem() as CustomFoodEffect.Saved
            val food = foods.foods.value.getValue(saved.foodId)
            assertThat(food.source).isEqualTo(FoodSource.LABEL)
            assertThat(food.barcode).isEqualTo("00012345678905")
            // 230 kcal per 55 g serving → 418.2 per 100 g
            assertThat(food.nutrientsPer100g[NutrientKey.ENERGY_KCAL]).isWithin(0.1).of(418.2)
        }
        // The next scan of that barcode resolves locally.
        assertThat(foods.lookupBarcode("00012345678905")).isInstanceOf(BarcodeLookup.Found::class.java)
    }
}
