package app.forkcast.ui.common

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalDensity
import app.forkcast.ui.theme.Motion

/**
 * Whether the content below is being seen for the first time (DESIGN.md §8): the app just opened,
 * the day changed, or a screen was just opened. Then the ring fills from empty, numbers count up,
 * bars and charts fill in as they scroll into view, and (on app open only) cards rise in.
 * Coming back to a screen shows the current state without replaying any of it.
 *
 * Each effect claims a key once, so a list row scrolled away and back doesn't animate again.
 */
@Stable
class Entrance(val active: Boolean, private val rise: Boolean) {
    private val claimed = HashSet<String>()
    private var firstRiseAt = 0L

    /** True the first time [key] asks while this entrance is active. */
    fun claim(key: String): Boolean = active && claimed.add(key)

    /** Rise in only for the first screenful: items near the top that appear within a moment of the first. */
    fun claimRise(key: String, index: Int = 0): Boolean {
        if (!rise || index > Motion.STAGGER_CAP + 2 || !claim("rise:$key")) return false
        val now = SystemClock.uptimeMillis()
        if (firstRiseAt == 0L) firstRiseAt = now
        return now - firstRiseAt < Motion.ENTRANCE_WINDOW_MS
    }

    companion object {
        val None = Entrance(active = false, rise = false)
    }
}

val LocalEntrance = staticCompositionLocalOf { Entrance.None }

/**
 * An entrance for a screen that plays once per visit: opening the screen, not coming back to it
 * (the flag is saved with the screen's state). Charts and bars fill in; cards don't rise, the screen
 * transition already brings them in.
 */
@Composable
fun ScreenEntrance(content: @Composable () -> Unit) {
    var played by rememberSaveable { mutableStateOf(false) }
    val entrance = remember { Entrance(active = !played, rise = false) }
    LaunchedEffect(Unit) { played = true }
    CompositionLocalProvider(LocalEntrance provides entrance, content = content)
}

/**
 * Fades and lifts an item into place during an entrance, [index] places apart from the first
 * (DESIGN.md §8). Drawing-only (graphicsLayer): never blocks touches and never re-lays out.
 */
@Composable
fun rememberRise(key: String, index: Int): Modifier {
    val entrance = LocalEntrance.current
    val play = remember(entrance) { entrance.claimRise(key, index) }
    if (!play) return Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, Motion.enter(Motion.MEDIUM, delay = index.coerceAtMost(Motion.STAGGER_CAP) * Motion.STAGGER))
    }
    val rise = with(LocalDensity.current) { Motion.RISE.toPx() }
    return Modifier.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * rise
    }
}

/** A 0 → 1 multiplier for a bar or chart that fills in the first time it's on screen. */
@Stable
class Reveal(private val anim: Animatable<Float, *>, val modifier: Modifier, val animated: Boolean) {
    val progress: Float get() = anim.value

    /** True once the content may show its data (immediately unless waiting to be scrolled into view). */
    var ready by mutableStateOf(!animated)
        internal set
}

/**
 * Fills in [key]'s bar or chart when it first scrolls into view during an entrance; otherwise it
 * just shows (progress 1). Pass [Reveal.modifier] to the element that has to be seen.
 */
@Composable
fun rememberReveal(key: String, duration: Int = Motion.FILL, delay: Int = 0): Reveal {
    val entrance = LocalEntrance.current
    val play = remember(entrance) { entrance.claim("reveal:$key") }
    val anim = remember { Animatable(if (play) 0f else 1f) }
    val reveal = remember {
        lateinit var r: Reveal
        val mod = if (play) Modifier.onVisibilityChanged(minFractionVisible = 0.25f) { visible -> if (visible) r.ready = true } else Modifier
        r = Reveal(anim, mod, play)
        r
    }
    LaunchedEffect(reveal.ready) {
        if (reveal.ready && anim.value < 1f) anim.animateTo(1f, Motion.enter(duration, delay))
    }
    return reveal
}

/** A bar's shown fraction: fills in on first sight ([rememberReveal]) and eases to new values. */
@Stable
class Fill(private val value: State<Float>, private val reveal: Reveal) {
    /** Read it in a draw or progress lambda, so only drawing updates per frame. */
    val fraction: Float get() = value.value * reveal.progress
    val modifier: Modifier get() = reveal.modifier
}

@Composable
fun rememberFill(target: Float, key: String, delay: Int = 0): Fill {
    val reveal = rememberReveal(key, delay = delay)
    val value = animateFloatAsState(target, Motion.change(), label = key)
    return remember(reveal) { Fill(value, reveal) }
}
