package app.forkcast.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.repository.FoodDatabaseKeyRepository
import app.forkcast.core.network.FoodApiError
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The USDA key: checked with FoodData Central before it's saved (roadmap 2.0 task 3.3). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FoodDatabasesTest {

    @get:Rule val compose = createComposeRule()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeKeys(var error: Throwable? = null) : FoodDatabaseKeyRepository {
        override val savedKey = MutableStateFlow<String?>(null)
        override suspend fun test(key: String): Result<Unit> = error?.let { Result.failure(it) } ?: Result.success(Unit)
        override suspend fun save(key: String) { savedKey.value = key }
        override suspend fun remove() { savedKey.value = null }
    }

    private val key = "abcdefghijklmnopqrstuvwxyz0123456789ABCD"

    @Test fun aWorkingKeyIsSaved() = runTest(dispatcher) {
        val keys = FakeKeys()
        val vm = FoodDatabasesViewModel(keys)
        vm.saveUsdaKey(" $key ")
        advanceUntilIdle()
        assertThat(keys.savedKey.value).isEqualTo(key)
        assertThat(vm.check.value).isEqualTo(UsdaKeyCheck.Saved)
    }

    @Test fun aRejectedKeyIsNotSavedAndCannotBeForced() = runTest(dispatcher) {
        val keys = FakeKeys(FoodApiError.InvalidKey())
        val vm = FoodDatabasesViewModel(keys)
        vm.saveUsdaKey(key)
        advanceUntilIdle()
        assertThat(keys.savedKey.value).isNull()
        assertThat((vm.check.value as UsdaKeyCheck.Failed).canSaveAnyway).isFalse()
    }

    @Test fun offlineCanSaveAnyway() = runTest(dispatcher) {
        val keys = FakeKeys(FoodApiError.Offline())
        val vm = FoodDatabasesViewModel(keys)
        vm.saveUsdaKey(key)
        advanceUntilIdle()
        assertThat((vm.check.value as UsdaKeyCheck.Failed).canSaveAnyway).isTrue()
        vm.saveAnyway()
        advanceUntilIdle()
        assertThat(keys.savedKey.value).isEqualTo(key)
    }

    @Test fun withoutAKeyTheStepsShow() {
        compose.setContent { ForkcastTheme { FoodDatabasesScreen(savedKey = null, onSave = {}, onRemove = {}, onBack = {}) } }
        compose.onNodeWithText("Get a free key in 3 steps").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open the sign-up page").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check and save").performScrollTo().assertIsDisplayed()
    }
}
