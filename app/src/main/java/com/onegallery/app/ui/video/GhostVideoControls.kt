package com.onegallery.app.ui.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.onegallery.app.ui.common.ownedPress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * "Ghost" controls: outline + a whisper of fill instead of solid pills, so the video stays
 * visible through and around them. Shared here so every control on the video page reads as one
 * family.
 */

internal val GhostFill = Color(0x26000000)
internal val GhostOutline = Color(0x59FFFFFF)
internal val GhostOutlineStrong = Color(0xB3FFFFFF)
internal val GhostContent = Color(0xEBFFFFFF)

/** For text that should be readable but never compete with the controls. */
internal val GhostContentDim = Color(0xB3FFFFFF)

@Composable
internal fun GhostIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    emphasized: Boolean = false
) {
    val latestOnClick by rememberUpdatedState(onClick)
    // The tappable box is deliberately larger than the painted circle: these sit over video with
    // no chrome to aim at, and a thumb reaching across the phone lands short of centre.
    Box(
        modifier = modifier
            .size(touchTargetFor(size))
            .clip(CircleShape)
            // Not `clickable`: a thumb that slides a few pixels while pressing would hand the
            // gesture to the pager and turn the press into a swipe to the next item.
            .ownedPress(onRelease = { inside -> if (inside) latestOnClick() })
            .semantics {
                role = Role.Button
                onClick { latestOnClick(); true }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(GhostFill)
                .border(
                    width = if (emphasized) 2.dp else 1.dp,
                    color = if (emphasized) GhostOutlineStrong else GhostOutline,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = GhostContent,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * Hit area for a ghost control of a given painted [size]: at least [MIN_TOUCH_TARGET], and always
 * a little wider than the circle itself so neighbouring controls stay separated by real gaps
 * rather than by touching hit boxes.
 */
private fun touchTargetFor(size: Dp): Dp = maxOf(size + TOUCH_SLOP_RING * 2, MIN_TOUCH_TARGET)

/**
 * The frame-hunting control: a jog strip with the previous/next-frame buttons on its two ends,
 * drawn as one pill.
 *
 * One control instead of three so that all of it sits under the right thumb. The buttons used to
 * stand in a row of their own beside the strip, which pushed the strip itself out to the far
 * left of the phone where a thumb holding it cannot reach.
 *
 * [onStepFrames] carries whole frames from a drag on the strip; [onStepButton] is -1 or +1 from
 * an end button — once on press, then repeating while held.
 */
@Composable
internal fun FrameJogBar(
    onScrubStart: () -> Unit,
    onStepFrames: (Int) -> Unit,
    onStepButton: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(JogBarHeight)
            .clip(RoundedCornerShape(JogBarHeight / 2))
            .background(GhostFill)
            .border(1.dp, GhostOutline, RoundedCornerShape(JogBarHeight / 2)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        JogStepButton(Icons.Rounded.SkipPrevious, "Previous frame") { onStepButton(-1) }
        FrameJogStrip(
            onScrubStart = onScrubStart,
            onStepFrames = onStepFrames,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        JogStepButton(Icons.Rounded.SkipNext, "Next frame") { onStepButton(1) }
    }
}

/**
 * Fires [onStep] once on press, then keeps firing while held — tap for a single frame, hold to
 * walk through frames without lifting the thumb.
 */
@Composable
private fun JogStepButton(
    icon: ImageVector,
    contentDescription: String,
    onStep: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val latestOnStep by rememberUpdatedState(onStep)
    // Plain holder, not state: nothing is drawn from it
    val repeater = remember { arrayOfNulls<Job>(1) }

    Box(
        modifier = Modifier
            .width(JogButtonWidth)
            .fillMaxHeight()
            .ownedPress(
                onPress = {
                    // The first step is taken here, not inside the coroutine: a launched
                    // coroutine only starts on the next dispatch, and a quick tap lifts — and
                    // cancels it — before that, so the tap did nothing at all.
                    latestOnStep()
                    repeater[0] = scope.launch {
                        delay(HOLD_BEFORE_REPEAT_MS)
                        while (true) {
                            latestOnStep()
                            delay(REPEAT_INTERVAL_MS)
                        }
                    }
                },
                onRelease = {
                    repeater[0]?.cancel()
                    repeater[0] = null
                }
            )
            .semantics {
                role = Role.Button
                onClick { latestOnStep(); true }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = GhostContent,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * A jog strip for frame-accurate scrubbing. The seek bar maps the whole video onto one screen
 * width — on a 5-minute clip that is ~200 ms per pixel, so single frames are unreachable. Here
 * distance maps to *frames* instead: every [JOG_DP_PER_FRAME] dp dragged is exactly one frame,
 * whatever the video's length.
 *
 * Direction follows the film, not the playhead: dragging left pulls later frames towards the
 * needle, so left = forward and right = back, the way a reel behaves under a fixed head.
 *
 * [onStepFrames] receives whole frames only (positive = forward); the remainder carries over to
 * the next drag event so slow drags still add up.
 */
@Composable
internal fun FrameJogStrip(
    onScrubStart: () -> Unit,
    onStepFrames: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnScrubStart by rememberUpdatedState(onScrubStart)
    val latestOnStepFrames by rememberUpdatedState(onStepFrames)
    var tickOffsetPx by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                val pxPerFrame = JOG_DP_PER_FRAME.dp.toPx()
                var carriedPx = 0f

                detectHorizontalDragGestures(
                    onDragStart = {
                        carriedPx = 0f
                        latestOnScrubStart()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        // Consumed so the pager underneath doesn't also treat this as a swipe
                        change.consume()
                        carriedPx += dragAmount
                        tickOffsetPx += dragAmount

                        val dragged = (carriedPx / pxPerFrame).toInt()
                        if (dragged != 0) {
                            carriedPx -= dragged * pxPerFrame
                            // Negated: the ticks travel with the thumb, so the frame under the
                            // fixed needle moves the opposite way
                            latestOnStepFrames(-dragged)
                        }
                    }
                )
            }
    ) {
        val spacing = JOG_DP_PER_FRAME.dp.toPx()
        val centerY = size.height / 2f
        // Ticks slide with the thumb so the strip feels like a dial being turned
        var x = ((tickOffsetPx % spacing) + spacing) % spacing
        // Which tick this is on the (endless) dial, so every 5th stays tall as it slides
        var index = ((x - tickOffsetPx) / spacing).roundToInt()
        while (x <= size.width) {
            val isMajor = index % 5 == 0
            val halfHeight = size.height * (if (isMajor) 0.30f else 0.16f)
            drawLine(
                color = if (isMajor) GhostOutlineStrong else GhostOutline,
                start = Offset(x, centerY - halfHeight),
                end = Offset(x, centerY + halfHeight),
                strokeWidth = 1.dp.toPx()
            )
            x += spacing
            index++
        }

        // Where the strip ends and each button begins
        for (edge in listOf(0f, size.width)) {
            drawLine(
                color = GhostOutline,
                start = Offset(edge, size.height * 0.2f),
                end = Offset(edge, size.height * 0.8f),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Fixed centre needle: "this is the frame you're on"
        drawLine(
            color = Color.White,
            start = Offset(size.width / 2f, size.height * 0.12f),
            end = Offset(size.width / 2f, size.height * 0.88f),
            strokeWidth = 2.dp.toPx()
        )
    }
}

/**
 * The position to seek to in order to land [frames] frames away from the frame currently shown.
 *
 * ExoPlayer's exact seek shows the first frame whose timestamp is >= the requested position.
 * Frame N sits at N * 1000 / fps ms, which is usually not a whole millisecond, so the target is
 * rounded **down**: rounding up would overshoot the timestamp and land on frame N + 1. For the
 * same reason the current frame is derived from `currentMs + 1` — a position produced by this
 * function is up to 1 ms short of its frame's real timestamp.
 *
 * Assumes a constant frame rate. Phone footage is often variable-rate, where this is a close
 * approximation rather than exact; an unknown rate falls back to [DEFAULT_FRAME_RATE].
 */
internal fun frameStepPositionMs(
    currentMs: Long,
    frames: Int,
    frameRate: Float,
    durationMs: Long
): Long {
    val fps = if (frameRate > 0f) frameRate.toDouble() else DEFAULT_FRAME_RATE
    val currentFrame = floor((currentMs + 1) * fps / 1000.0).toLong()
    val targetFrame = (currentFrame + frames).coerceAtLeast(0)
    val targetMs = floor(targetFrame * 1000.0 / fps).toLong()
    return targetMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
}

internal const val DEFAULT_FRAME_RATE = 30.0

private const val JOG_DP_PER_FRAME = 12

/** A full touch target tall, because the step buttons on its ends are pressed, not dragged. */
private val JogBarHeight = 48.dp
private val JogButtonWidth = 48.dp
private const val HOLD_BEFORE_REPEAT_MS = 350L
private const val REPEAT_INTERVAL_MS = 70L

/** Android's recommended minimum, applied to controls that have no chrome to aim at. */
private val MIN_TOUCH_TARGET = 48.dp

/** Extra tappable ring around every ghost control, beyond the circle that is actually painted. */
private val TOUCH_SLOP_RING = 5.dp
