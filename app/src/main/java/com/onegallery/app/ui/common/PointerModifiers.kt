package com.onegallery.app.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

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
