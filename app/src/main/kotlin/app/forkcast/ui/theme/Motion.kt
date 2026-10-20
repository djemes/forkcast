package app.forkcast.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp

/**
 * Every duration and easing curve in the app (DESIGN.md §8). Tune motion here, nowhere else.
 *
 * All of these run through Compose's animation clock, which follows Android's animator duration
 * scale: with "Remove animations" on, every animation (including staggers, which are tween
 * delays, never coroutine delays) finishes instantly. Animations never block input.
 */
object Motion {
    /** Small changes: fades, icon swaps, a row appearing. */
    const val SHORT = 150

    /** Most transitions: screen changes, day changes, list items, numbers ticking to a new value. */
    const val MEDIUM = 250

    /** Larger movement: a screen sliding in, the day page. */
    const val LONG = 300

    /** Bars and charts filling in the first time they're seen. */
    const val FILL = 450

    /** The budget ring filling from empty when the app opens or the day changes. */
    const val RING_FILL = 600

    /** Gap between items that enter together (cards on Today). */
    const val STAGGER = 25

    /** Items past this many share the last stagger slot, so a long screen still settles in < 0.5 s. */
    const val STAGGER_CAP = 6

    /** Items that first appear later than this after an entrance started just show (no rise): the
     * first screenful can take a few frames to compose on a cold start. */
    const val ENTRANCE_WINDOW_MS = 1_000L

    /** How far entering content rises. */
    val RISE = 16.dp

    /** Screens slide this far (Material shared axis X), together with a cross-fade. */
    val SHARED_AXIS = 30.dp

    /** Shared axis: the old screen is gone after this long, then the new one fades in (no ghosting). */
    const val SHARED_AXIS_FADE_OUT = 90

    // Material 3 easing tokens.
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Something arriving (enter, fill, reveal). */
    fun <T> enter(duration: Int = MEDIUM, delay: Int = 0): FiniteAnimationSpec<T> = tween(duration, delay, EmphasizedDecelerate)

    /** Something leaving: quicker than arriving. */
    fun <T> exit(duration: Int = SHORT): FiniteAnimationSpec<T> = tween(duration, 0, EmphasizedAccelerate)

    /** Motion that tracks a finger (predictive back): linear, so the screen moves with the swipe. */
    fun <T> follow(duration: Int = LONG): FiniteAnimationSpec<T> = tween(duration, 0, LinearEasing)

    /** How bright the screen underneath starts when it slides back in (a light scrim effect). */
    const val BACK_UNDER_ALPHA = 0.6f

    /** A value moving to a new value in place (ring, bars, numbers after an edit). */
    fun <T> change(duration: Int = MEDIUM): FiniteAnimationSpec<T> = tween(duration, 0, Standard)
}
