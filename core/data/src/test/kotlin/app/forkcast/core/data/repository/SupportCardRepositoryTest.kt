package app.forkcast.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.settings.DevicePrefs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The Ko-fi card (owner, 2026-10-30): hidden across restarts and minor updates, back after three months or a major update. */
@RunWith(AndroidJUnit4::class)
class SupportCardRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val hiddenAt = Instant.parse("2026-10-30T12:00:00Z")
    private fun clock(daysLater: Long = 0) = Clock.fixed(hiddenAt.plus(Duration.ofDays(daysLater)), ZoneId.of("America/New_York"))

    @Test fun hiddenSurvivesARestartAndMinorUpdatesButNotAMajorOne() = runTest {
        val repo = DefaultSupportCardRepository(DevicePrefs(context), clock())
        assertThat(repo.shown("1.8.6").first()).isTrue()
        repo.hide("1.8.6")
        assertThat(repo.shown("1.8.6").first()).isFalse()

        // A new process on a later 1.x reads what was saved.
        val again = DefaultSupportCardRepository(DevicePrefs(context), clock(daysLater = 30))
        assertThat(again.shown("1.9.0").first()).isFalse()
        assertThat(again.isShown("1.9.0")).isFalse()
        assertThat(again.shown("2.0.0").first()).isTrue()
    }

    @Test fun backAfterThreeMonths() = runTest {
        DefaultSupportCardRepository(DevicePrefs(context), clock()).hide("1.8.7")
        assertThat(DefaultSupportCardRepository(DevicePrefs(context), clock(daysLater = 89)).isShown("1.8.7")).isFalse()
        assertThat(DefaultSupportCardRepository(DevicePrefs(context), clock(daysLater = 90)).isShown("1.8.7")).isTrue()
    }

    @Test fun undoShowsItAgain() = runTest {
        val repo = DefaultSupportCardRepository(DevicePrefs(context), clock())
        repo.hide("1.8.6")
        repo.show()
        assertThat(repo.shown("1.8.6").first()).isTrue()
    }
}
