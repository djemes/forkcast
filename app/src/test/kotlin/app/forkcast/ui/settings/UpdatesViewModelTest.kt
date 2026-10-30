package app.forkcast.ui.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.update.Download
import app.forkcast.core.data.update.UpdateCheck
import app.forkcast.core.data.update.UpdateRepository
import app.forkcast.core.network.update.Release
import app.forkcast.fakes.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opening Updates checks right away (owner, 2026-10-25), once per visit. */
@RunWith(AndroidJUnit4::class)
class UpdatesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class CountingUpdates : UpdateRepository {
        var checks = 0
        override suspend fun check(currentVersionName: String, includeBetas: Boolean): UpdateCheck {
            checks++
            return UpdateCheck.UpToDate("v9.9.9")
        }
        override suspend fun download(release: Release, progress: (Long, Long) -> Unit): Download = error("unused")
    }

    @Test fun openingThePageChecksOnce() = runTest(dispatcher) {
        val updates = CountingUpdates()
        val vm = UpdatesViewModel(ApplicationProvider.getApplicationContext(), updates, FakeSettingsRepository())
        vm.checkOnOpen()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(updates.checks).isEqualTo(1)
        assertThat(vm.ui.value).isEqualTo(UpdateUi.Result(UpdateCheck.UpToDate("v9.9.9")))
        // Coming back to the page (a rotation, say) doesn't check again; the button still does.
        vm.checkOnOpen()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(updates.checks).isEqualTo(1)
        vm.check()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(updates.checks).isEqualTo(2)
    }
}
