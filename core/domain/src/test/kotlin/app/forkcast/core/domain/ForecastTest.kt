package app.forkcast.core.domain

import app.forkcast.core.domain.Forecast.Logged
import app.forkcast.core.domain.Forecast.Status
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class ForecastTest {

    private val today = LocalDate.of(2026, 10, 18)

    /** [days] past days, each with breakfast 400 at 8:00 and dinner [dinner] at 19:00. */
    private fun history(days: Int, dinner: (Int) -> Double = { 700.0 }) = (1..days).flatMap { i ->
        val d = today.minusDays(i.toLong())
        listOf(Logged(d, 8 * 60, 400.0), Logged(d, 19 * 60, dinner(i)))
    }

    @Test fun noForecastBeforeSevenLoggedDays() {
        assertThat(Forecast.expectedAfter(history(6), today, 16 * 60)).isNull()
        assertThat(Forecast.expectedAfter(history(7), today, 16 * 60)).isEqualTo(700.0)
    }

    @Test fun medianOfWhatWasLoggedAfterNow() {
        // Dinners 100..1400 over 14 days → median 750; older days are ignored.
        val h = history(20) { i -> if (i <= 14) i * 100.0 else 5000.0 }
        assertThat(Forecast.expectedAfter(h, today, 16 * 60)).isEqualTo(750.0)
        // At 7:00 the whole day is still ahead.
        assertThat(Forecast.expectedAfter(h, today, 7 * 60)).isEqualTo(1150.0)
        // Today's own entries never count.
        assertThat(Forecast.expectedAfter(h + Logged(today, 20 * 60, 9999.0), today, 16 * 60)).isEqualTo(750.0)
    }

    @Test fun lateLoggedFoodCountsAtItsMealNotWhenItWasTyped() {
        val zone = java.time.ZoneId.of("America/New_York")
        val yesterday = today.minusDays(1)
        val typedThisMorning = today.atTime(8, 15).atZone(zone).toInstant()
        // Yesterday's dinner (window from 17:00) entered today at 8:15 counts at 17:00 yesterday.
        assertThat(Forecast.minuteEaten(yesterday, typedThisMorning, zone, 17 * 60)).isEqualTo(17 * 60)
        // A meal without a window (Snacks) falls back to noon.
        assertThat(Forecast.minuteEaten(yesterday, typedThisMorning, zone, null)).isEqualTo(12 * 60)
        // Logged on its own day: the time it was logged.
        val onTime = yesterday.atTime(19, 40).atZone(zone).toInstant()
        assertThat(Forecast.minuteEaten(yesterday, onTime, zone, 17 * 60)).isEqualTo(19 * 60 + 40)
    }

    @Test fun daysWithNothingAfterNowCountAsZero() {
        val h = history(4) + (5..8).map { Logged(today.minusDays(it.toLong()), 8 * 60, 500.0) }
        // 4 days with a 700 dinner, 4 days with nothing after 16:00 → median 350.
        assertThat(Forecast.expectedAfter(h, today, 16 * 60)).isEqualTo(350.0)
    }

    @Test fun statusWording() {
        assertThat(Forecast.outlook(1200.0, 2000.0, 700.0).status).isEqualTo(Status.ON_COURSE)
        assertThat(Forecast.outlook(1300.0, 2000.0, 750.0).status).isEqualTo(Status.TIGHT)
        val likely = Forecast.outlook(1500.0, 2000.0, 654.0)
        assertThat(likely.status).isEqualTo(Status.LIKELY_OVER)
        assertThat(likely.overByKcal).isEqualTo(150.0)
        val over = Forecast.outlook(2120.0, 2000.0, 300.0)
        assertThat(over.status).isEqualTo(Status.OVER)
        assertThat(over.overByKcal).isEqualTo(120.0)
    }
}
