package com.onegallery.app.ui.video

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ceil

class FrameStepTest {

    /** Mirrors ExoPlayer's exact seek: the first frame whose timestamp is >= the position. */
    private fun frameShownAt(positionMs: Long, fps: Double): Long =
        ceil(positionMs * fps / 1000.0 - 1e-9).toLong()

    @Test
    fun `stepping lands on exactly the next or previous frame at every common frame rate`() {
        val frameRates = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f, 120f, 240f)
        for (fps in frameRates) {
            var position = 0L
            var expectedFrame = 0L
            repeat(2_000) {
                position = frameStepPositionMs(position, 1, fps, Long.MAX_VALUE)
                expectedFrame++
                assertEquals("forward @ $fps fps", expectedFrame, frameShownAt(position, fps.toDouble()))
            }
            repeat(2_000) {
                position = frameStepPositionMs(position, -1, fps, Long.MAX_VALUE)
                expectedFrame--
                assertEquals("backward @ $fps fps", expectedFrame, frameShownAt(position, fps.toDouble()))
            }
        }
    }

    @Test
    fun `a position in the middle of a frame steps relative to the frame on screen`() {
        // 50 ms at 30 fps is inside frame 1 (33.3 - 66.7 ms), so "next" is frame 2
        assertEquals(66L, frameStepPositionMs(50, 1, 30f, 10_000))
    }

    @Test
    fun `steps are clamped to the video`() {
        assertEquals(0L, frameStepPositionMs(0, -1, 30f, 10_000))
        assertEquals(10_000L, frameStepPositionMs(9_990, 5, 30f, 10_000))
    }

    @Test
    fun `an unknown frame rate falls back to 30 fps`() {
        assertEquals(frameStepPositionMs(100, 1, 30f, 10_000), frameStepPositionMs(100, 1, -1f, 10_000))
    }
}
