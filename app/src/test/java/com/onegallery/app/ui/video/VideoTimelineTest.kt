package com.onegallery.app.ui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The frame ↔ time mapping behind frame-by-frame stepping.
 *
 * It used to be arithmetic on a frame rate, and produced three separate wrong-frame bugs on real
 * footage. It is now a lookup in the file's own frame times, pinned here on the shapes of file
 * that broke the arithmetic: a rate that is not a whole number of milliseconds, a rate that
 * varies, and frames stored out of display order.
 */
class VideoTimelineTest {

    /** 16 fps, the rate ComfyUI's video models write: 62.5 ms a frame, 81 frames. */
    private val sixteen = VideoTimeline(LongArray(81) { it * 62_500L }, intArrayOf(0))

    /** Variable rate: gaps of one, two and three thirtieths of a second. */
    private val variable = VideoTimeline(
        longArrayOf(0, 33_333, 100_000, 133_333, 233_333, 266_666, 300_000),
        intArrayOf(0, 4)
    )

    @Test fun `seeking to a frame's position shows that frame`() {
        for (timeline in listOf(sixteen, variable)) {
            for (index in 0..timeline.lastIndex) {
                val positionUs = timeline.seekPositionMs(index) * 1000
                // An exact seek shows the first frame at or after the position
                val shown = (0..timeline.lastIndex).first { timeline.timeUs(it) >= positionUs }
                assertEquals("index $index", index, shown)
            }
        }
    }

    @Test fun `a frame's own time maps back to it`() {
        for (timeline in listOf(sixteen, variable)) {
            for (index in 0..timeline.lastIndex) {
                assertEquals(index, timeline.nearestIndexUs(timeline.timeUs(index)))
                assertEquals(index, timeline.indexAtOrBeforeUs(timeline.timeUs(index)))
            }
        }
    }

    @Test fun `the frame on screen is the last one whose time has come`() {
        assertEquals(1, variable.indexAtOrBeforeUs(99_999))
        assertEquals(2, variable.indexAtOrBeforeUs(100_000))
        assertEquals(0, variable.indexAtOrBeforeUs(-5))
        assertEquals(6, variable.indexAtOrBeforeUs(10_000_000))
    }

    @Test fun `nearest picks the closer neighbour across an uneven gap`() {
        // Between frame 1 (33.3 ms) and frame 2 (100 ms)
        assertEquals(1, variable.nearestIndexUs(60_000))
        assertEquals(2, variable.nearestIndexUs(70_000))
    }

    @Test fun `the last frame of a variable-rate clip is its last index`() {
        // Frames-over-duration put this eleven frames early on a real clip
        assertEquals(variable.lastIndex, variable.nearestIndexMs(300))
        assertEquals(variable.lastIndex, variable.nearestIndexMs(10_400))
    }

    @Test fun `a decoder starts from the keyframe at or before the frame`() {
        assertEquals(0, variable.keyframeAtOrBefore(3))
        assertEquals(4, variable.keyframeAtOrBefore(4))
        assertEquals(4, variable.keyframeAtOrBefore(6))
        // One keyframe for the whole clip: everything starts from frame 0
        assertEquals(0, sixteen.keyframeAtOrBefore(80))
    }

    @Test fun `the rate comes from the frames, not the container's duration`() {
        assertEquals(16f, sixteen.averageFrameRate, 0.001f)
    }

    @Test fun `samples in decode order are put in display order`() {
        // I P B B — stored as I(0) P(3) B(1) B(2)
        val timeline = VideoTimeline.fromSamples(
            longArrayOf(0, 187_500, 62_500, 125_000),
            booleanArrayOf(true, false, false, false)
        )!!
        assertEquals(listOf(0L, 62_500L, 125_000L, 187_500L), (0..3).map { timeline.timeUs(it) })
        assertEquals(0, timeline.keyframeAtOrBefore(3))
    }

    @Test fun `frames before the start of the track are left out`() {
        val timeline = VideoTimeline.fromSamples(
            longArrayOf(-62_500, 0, 62_500),
            booleanArrayOf(true, false, false)
        )!!
        assertEquals(2, timeline.frameCount)
        assertEquals(0L, timeline.timeUs(0))
    }

    @Test fun `a track with no usable frames has no timeline`() {
        assertNull(VideoTimeline.fromSamples(longArrayOf(), booleanArrayOf()))
    }
}
