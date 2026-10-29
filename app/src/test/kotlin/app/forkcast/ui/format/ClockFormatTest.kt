package app.forkcast.ui.format

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.util.Locale

/** The phone's 12/24-hour setting decides, whatever the language (owner, 2026-10-29). */
@RunWith(AndroidJUnit4::class)
class ClockFormatTest {

    private val evening = LocalDateTime.of(2026, 10, 29, 19, 5, 9)

    private fun time(locale: Locale, is24: Boolean, seconds: Boolean = false) =
        ClockFormat.formatter(locale, is24, seconds).format(evening).replace(' ', ' ')

    @Test fun `24-hour phone shows 19 05 in English and Portuguese`() {
        assertThat(time(Locale.US, is24 = true)).isEqualTo("19:05")
        assertThat(time(Locale.forLanguageTag("pt-BR"), is24 = true)).isEqualTo("19:05")
    }

    @Test fun `12-hour phone shows 7 05 PM, also in Portuguese`() {
        assertThat(time(Locale.US, is24 = false)).isEqualTo("7:05 PM")
        assertThat(time(Locale.forLanguageTag("pt-BR"), is24 = false)).startsWith("7:05")
    }

    @Test fun `seconds and minutes after midnight`() {
        assertThat(time(Locale.US, is24 = true, seconds = true)).isEqualTo("19:05:09")
        assertThat(ClockFormat.formatter(Locale.US, is24 = true).formatMinute(7 * 60 + 8)).isEqualTo("07:08")
        assertThat(ClockFormat.formatter(Locale.US, is24 = true).formatMinute(24 * 60)).isEqualTo("00:00")
    }

    @Test fun `with the date`() {
        val text = ClockFormat.formatter(Locale.US, is24 = true, date = true).format(evening)
        assertThat(text).contains("10/29/2026")
        assertThat(text).contains("19:05")
    }
}
