package app.forkcast

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** REVIEW-2 S7: a crash leaves a local report with no message text in it, and nothing else changes. */
@RunWith(AndroidJUnit4::class)
class CrashReportsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fakeKey = "AIzaSyFAKE-test-key-0000000000000000"

    @After fun tearDown() = CrashReports.discard(context)

    private fun crash(): Throwable = IllegalStateException(
        "Couldn't parse reply for 'grandma's lasagna' with key $fakeKey",
        java.io.IOException("server said: $fakeKey"),
    )

    @Test fun reportHasVersionsAndCodeLocationsButNoMessages() {
        val text = CrashReports.report(crash(), onMainThread = true, at = Instant.parse("2026-10-25T12:34:56Z"))
        assertThat(text).contains("App: ${BuildConfig.VERSION_NAME}")
        assertThat(text).contains("Android: ")
        assertThat(text).contains("Time: 2026-10-25T12:34:00Z")
        assertThat(text).contains("java.lang.IllegalStateException")
        assertThat(text).contains("Caused by: java.io.IOException")
        assertThat(text).contains("at app.forkcast.CrashReportsTest.crash(CrashReportsTest.kt:")
        assertThat(text).doesNotContain(fakeKey)
        assertThat(text).doesNotContain("lasagna")
        assertThat(text).doesNotContain("server said")
    }

    @Test fun theHandlerWritesTheReportAndHandsOnToAndroid() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var handedOn: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> handedOn = e }
        try {
            CrashReports.install(context)
            val error = crash()
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertThat(handedOn).isSameInstanceAs(error)
            val saved = CrashReports.pending(context)!!.readText()
            assertThat(saved).contains("java.lang.IllegalStateException")
            assertThat(saved).doesNotContain(fakeKey)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
        CrashReports.discard(context)
        assertThat(CrashReports.pending(context)).isNull()
    }
}
