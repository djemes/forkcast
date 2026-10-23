package app.forkcast.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier

/**
 * For a scrolling form inside a Scaffold: with edge-to-edge, the keyboard draws over the screen
 * instead of resizing it, so the scroll area has to step back by the keyboard's height (minus the
 * navigation bar the Scaffold already pads). A text field that gets focus then scrolls into view
 * above the keyboard (owner, 2026-10-23: the keyboard hid "Correct it"). Put it before
 * `verticalScroll`, after the Scaffold's padding.
 */
fun Modifier.keyboardAware(scaffoldPadding: PaddingValues): Modifier = consumeWindowInsets(scaffoldPadding).imePadding()
