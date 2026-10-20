package app.forkcast.core.data.di

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone

class SystemZoneClockTest {

    /** A trip changes the phone's zone while the app keeps running; "today" must follow it. */
    @Test fun followsTheCurrentDefaultZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertThat(SystemZoneClock.zone).isEqualTo(ZoneId.of("America/New_York"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertThat(SystemZoneClock.zone).isEqualTo(ZoneId.of("Asia/Tokyo"))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
