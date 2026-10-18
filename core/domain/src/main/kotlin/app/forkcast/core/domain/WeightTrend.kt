package app.forkcast.core.domain

import app.forkcast.core.domain.NutritionConstants.TREND_SMOOTHING
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class WeighIn(val instant: Instant, val kg: Double)

data class TrendPoint(val date: LocalDate, val trendKg: Double, val rawKg: Double?)

/**
 * Exponentially smoothed weight trend (SPEC §5.5):
 * trend_t = trend_{t-1} + 0.1 × (w_t − trend_{t-1}); days without a weigh-in carry the trend forward;
 * several weigh-ins in one day use the earliest (morning) reading. The first weigh-in seeds the trend.
 */
object WeightTrend {

    fun daily(weighIns: List<WeighIn>, zone: ZoneId, through: LocalDate? = null): List<TrendPoint> {
        if (weighIns.isEmpty()) return emptyList()
        val byDay: Map<LocalDate, Double> = weighIns
            .groupBy { it.instant.atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.minBy { it.instant }.kg }
        val start = byDay.keys.min()
        val end = maxOf(through ?: byDay.keys.max(), byDay.keys.max())
        val out = ArrayList<TrendPoint>()
        var trend = byDay.getValue(start)
        var day = start
        while (!day.isAfter(end)) {
            val raw = byDay[day]
            if (raw != null) trend += TREND_SMOOTHING * (raw - trend)
            out += TrendPoint(day, trend, raw)
            day = day.plusDays(1)
        }
        return out
    }

    /** Change in trend per week over the last [days] days (negative = losing). Null with too little data. */
    fun weeklyRateKg(points: List<TrendPoint>, days: Int = 14): Double? {
        if (points.size < 2) return null
        val last = points.last()
        val first = points[maxOf(0, points.size - 1 - days)]
        val span = java.time.temporal.ChronoUnit.DAYS.between(first.date, last.date)
        if (span <= 0) return null
        return (last.trendKg - first.trendKg) / span * 7.0
    }
}
