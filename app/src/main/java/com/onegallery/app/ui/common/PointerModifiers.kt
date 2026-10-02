package com.onegallery.app.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

/**
 * Stops a tap that lands on this element — but on none of its children — from reaching whatever
 * is behind it.
 *
 * For control clusters drawn over something that is itself tappable. The video page toggles its
 * chrome on tap, so the few dp between two ghost buttons used to mean "hide everything": missing
 * the play button by a thumb's width dismissed the controls the user was aiming at. Inside a
 * cluster, a miss should simply do nothing.
 *
 * It is the **press** that has to be consumed, not the release. `clickable` opens with
 * `awaitFirstDown(requireUnconsumed = true)`, so a consumed press means its gesture never starts;
 * consuming only the release does not stop it, because `waitForUpOrCancellation` tests for an up
 * before it tests for consumption and hands the release back either way. Consuming the press is
 * also exactly what a `clickable` child does, which is why tapping one of these buttons has never
 * toggled the chrome.
 *
 * Dragging still works. A scrollable ancestor — the pager here — takes the press on the *initial*
 * pass with `requireUnconsumed = false`, before any of this runs, so a swipe that merely starts in
 * a gap still pages. That is the same reason a drag beginning on a grid thumbnail scrolls the grid.
 */
fun Modifier.swallowTaps(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = true).consume()
    }
}

/**
 * A press that keeps its finger: nothing behind this element sees the touch as a tap *or* as the
 * start of a drag.
 *
 * `clickable` gives the finger up as soon as it moves — a thumb reaching across a large phone
 * rarely lands dead still, and the few pixels it slides were enough for the pager underneath to
 * take the gesture, cancel the click and page to the next item. Pressing the shutter could show
 * a different video. Here every movement is consumed for as long as the finger is down, so the
 * pager never gets a drag to act on, and the press still counts if the finger is inside the
 * element when it lifts.
 *
 * [onPress] runs as the finger lands; [onRelease] always follows it, with whether the finger
 * lifted inside the element. A cancelled gesture releases with `false`.
 *
 * Keyed on Unit: pass lambdas that read their state through `rememberUpdatedState`.
 */
fun Modifier.ownedPress(
    onPress: () -> Unit = {},
    onRelease: (inside: Boolean) -> Unit
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        down.consume()
        onPress()
        var inside = false
        try {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUp()) {
                    inside = !change.isOutOfBounds(size, extendedTouchPadding)
                    change.consume()
                    break
                }
                if (change.positionChanged()) change.consume()
            }
        } finally {
            onRelease(inside)
        }
    }
}
