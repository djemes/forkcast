package app.forkcast.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.settings.DevicePrefs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** The Ko-fi card (owner, 2026-10-30): hidden across restarts and minor updates, back after a major one. */
@RunWith(AndroidJUnit4::class)
class SupportCardRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun hiddenSurvivesARestartAndMinorUpdatesButNotAMajorOne() = runTest {
        val repo = DefaultSupportCardRepository(DevicePrefs(context))
        assertThat(repo.shown("1.8.6").first()).isTrue()
        repo.hide("1.8.6")
        assertThat(repo.shown("1.8.6").first()).isFalse()

        // A new process on a later 1.x reads what was saved.
        val again = DefaultSupportCardRepository(DevicePrefs(context))
        assertThat(again.shown("1.9.0").first()).isFalse()
        assertThat(again.shown("2.0.0").first()).isTrue()
    }

    @Test fun undoShowsItAgain() = runTest {
        val repo = DefaultSupportCardRepository(DevicePrefs(context))
        repo.hide("1.8.6")
        repo.show()
        assertThat(repo.shown("1.8.6").first()).isTrue()
    }
}
