package com.onegallery.app.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Calls [onSwipeUp] once when a single finger drags clearly upward.
 *
 * Built to coexist with everything else on a viewer page rather than compete with it:
 * - It never consumes, so taps, the pager's horizontal swipe and the seek bar are unaffected.
 * - It gives up as soon as any child consumed the event (a zoomed photo panning, the seek bar
 *   dragging) or a second finger lands (pinch).
 * - The drag must be at least twice as vertical as it is horizontal, so a sloppy page swipe
 *   does not also open the details sheet.
 *
 * [enabled] is read through `rememberUpdatedState` instead of adding/removing the modifier.
 * Changing the modifier chain mid-gesture (e.g. the moment a pinch takes the scale past 1x)
 * can restart the sibling pointerInput blocks and drop the gesture in progress.
 */
@Composable
fun Modifier.swipeUpToReveal(
    enabled: Boolean = true,
    onSwipeUp: () -> Unit
): Modifier {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnSwipeUp by rememberUpdatedState(onSwipeUp)

    return pointerInput(Unit) {
        val triggerDistancePx = 56.dp.toPx()

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragged = Offset.Zero

            do {
                val event = awaitPointerEvent()
                if (!latestEnabled) break
                if (event.changes.count { it.pressed } > 1) break
                if (event.changes.any { it.isConsumed }) break

                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                dragged += change.positionChange()

                val isClearlyVertical = abs(dragged.y) > 2f * abs(dragged.x)
                if (-dragged.y > triggerDistancePx && isClearlyVertical) {
                    latestOnSwipeUp()
                    break
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
