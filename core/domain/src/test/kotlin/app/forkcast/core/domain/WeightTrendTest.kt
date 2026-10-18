package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class WeightTrendTest {

    private val zone = ZoneId.of("America/New_York")
    private fun at(day: Int, hour: Int, kg: Double) =
        WeighIn(ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone).toInstant(), kg)

    @Test fun `smooths, carries forward, and uses the earliest reading of a day`() {
        val points = WeightTrend.daily(
            listOf(
                at(1, 7, 90.0),
                at(2, 7, 91.0),
                at(2, 21, 95.0), // evening reading ignored
                at(4, 7, 89.0),
            ),
            zone,
        )
        assertThat(points.map { it.date.dayOfMonth }).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(points[0].trendKg).isWithin(1e-9).of(90.0)
        assertThat(points[1].trendKg).isWithin(1e-9).of(90.1)
        assertThat(points[2].trendKg).isWithin(1e-9).of(90.1)
        assertThat(points[2].rawKg).isNull()
        assertThat(points[3].trendKg).isWithin(1e-9).of(89.99)
        assertThat(points[1].rawKg).isEqualTo(91.0)
    }

    @Test fun `extends the trend to a later day`() {
        val points = WeightTrend.daily(listOf(at(1, 7, 90.0)), zone, through = LocalDate.of(2026, 10, 3))
        assertThat(points).hasSize(3)
        assertThat(points.last().trendKg).isEqualTo(90.0)
    }

    @Test fun `weekly rate is negative when losing`() {
        val weighIns = (1..15).map { at(it, 7, 90.0 - it * 0.1) }
        val rate = WeightTrend.weeklyRateKg(WeightTrend.daily(weighIns, zone))
        assertThat(rate).isLessThan(0.0)
    }

    @Test fun `empty input gives empty output`() {
        assertThat(WeightTrend.daily(emptyList(), zone)).isEmpty()
        assertThat(WeightTrend.weeklyRateKg(emptyList())).isNull()
    }
}
