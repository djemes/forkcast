package app.forkcast.core.domain

import kotlin.math.abs

/**
 * The portion slider's scale (roadmap 3.9, owner 2026-10-29): snap points ¼ ½ ¾ | 1 | 1¼ 1½ 2 3,
 * with 1× fixed in the middle of the track and each half evenly spaced on its own, so fractions
 * fill the left half and multipliers the right. A portion is a multiplier of the entry's 1× amount
 * (what was served or logged); entries store it so reopening one starts where it was left.
 */
object PortionScale {
    val SNAPS: List<Double> = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0)

    /** Index of 1× in [SNAPS]. */
    const val ONE: Int = 3

    /** Custom portions (typed): from 1 % to 10×. */
    const val MIN: Double = 0.01
    const val MAX: Double = 10.0

    private const val TOLERANCE = 0.005

    /** Where snap [index] sits on the track, from 0 (left end) to 1 (right end). */
    fun position(index: Int): Double {
        require(index in SNAPS.indices)
        val right = SNAPS.size - 1 - ONE
        return if (index <= ONE) 0.5 * index / ONE else 0.5 + 0.5 * (index - ONE) / right
    }

    /** The snap nearest to a point on the track (a drag or a tap), [fraction] from 0 to 1. */
    fun nearestIndex(fraction: Double): Int = SNAPS.indices.minBy { abs(position(it) - fraction.coerceIn(0.0, 1.0)) }

    /** The snap [portion] is, or null for a custom portion between or beyond them. */
    fun indexOf(portion: Double): Int? = SNAPS.indexOfFirst { abs(it - portion) < TOLERANCE }.takeIf { it >= 0 }

    /** Where the thumb shows for any portion: on its snap, between its neighbours, or at an end. */
    fun positionOf(portion: Double): Double {
        indexOf(portion)?.let { return position(it) }
        if (portion <= SNAPS.first()) return 0.0
        if (portion >= SNAPS.last()) return 1.0
        val upper = SNAPS.indexOfFirst { it > portion }
        val lower = upper - 1
        val t = (portion - SNAPS[lower]) / (SNAPS[upper] - SNAPS[lower])
        return position(lower) + t * (position(upper) - position(lower))
    }

    /** One step left or right (TalkBack's adjust gestures, volume keys): from a custom portion, the next snap that way. */
    fun step(portion: Double, up: Boolean): Double {
        indexOf(portion)?.let { i -> return SNAPS[(if (up) i + 1 else i - 1).coerceIn(SNAPS.indices)] }
        return if (up) SNAPS.firstOrNull { it > portion } ?: SNAPS.last() else SNAPS.lastOrNull { it < portion } ?: SNAPS.first()
    }

    /** A typed multiplier, kept in range and to two decimals. */
    fun custom(multiplier: Double): Double = (Math.round(multiplier.coerceIn(MIN, MAX) * 100) / 100.0)

    /**
     * The amount for a new portion: [quantity] was logged at [from], so 1× is quantity / from.
     * Nutrients scale by the same factor ([factor]); a past entry's snapshot is only ever scaled, never re-read.
     */
    fun quantityFor(quantity: Double, from: Double, to: Double): Double = quantity / from.coerceAtLeast(MIN) * to

    fun factor(from: Double, to: Double): Double = to / from.coerceAtLeast(MIN)
}
