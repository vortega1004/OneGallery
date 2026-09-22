package com.onegallery.app.ui.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The index ↔ timestamp mapping behind frame-by-frame stepping.
 *
 * This maths has produced two separate off-by-ones on a real device — a walk that began one
 * frame behind the picture, and a "next frame" tap that appeared to do nothing — so the
 * round-trip is pinned here rather than re-derived by hand each time.
 */
class FrameIndexTest {

    private val last = 599

    @Test fun `a frame's timestamp maps back to that frame`() {
        for (fps in listOf(24f, 25f, 29.97f, 30f, 60f)) {
            for (index in listOf(0, 1, 2, 29, 100, 445, 599)) {
                val position = framePositionMs(index, fps)
                assertEquals(
                    "fps=$fps index=$index position=$position",
                    index,
                    frameIndexAt(position, fps, last)
                )
            }
        }
    }

    @Test fun `timestamps round down so an exact seek cannot overshoot`() {
        // Frame 100 at 30 fps really sits at 3333.33 ms; rounding up would land on frame 101
        assertEquals(3333L, framePositionMs(100, 30f))
        assertEquals(100, frameIndexAt(3333L, 30f, last))
        assertEquals(101, frameIndexAt(framePositionMs(101, 30f), 30f, last))
    }

    @Test fun `snapping picks the nearest frame, not the one before`() {
        // At 30 fps frame 100 sits at 3333.3 ms and frame 101 at 3366.7 ms.
        assertEquals(100, nearestFrameIndexAt(3340L, 30f, last))
        assertEquals(101, nearestFrameIndexAt(3360L, 30f, last))
        // ...where "which frame covers this instant" says 100 for both, which is the whole
        // reason the two functions exist separately.
        assertEquals(100, frameIndexAt(3340L, 30f, last))
        assertEquals(100, frameIndexAt(3360L, 30f, last))
        // Dead centre between two frames resolves upwards; it only has to be deterministic.
        assertEquals(101, nearestFrameIndexAt(3350L, 30f, last))
    }

    @Test fun `snapping a frame's own timestamp is a no-op`() {
        for (index in listOf(0, 1, 75, 300, 599)) {
            val position = framePositionMs(index, 30f)
            assertEquals(index, nearestFrameIndexAt(position, 30f, last))
        }
    }

    @Test fun `indices never leave the clip`() {
        assertEquals(0, frameIndexAt(-5_000L, 30f, last))
        assertEquals(last, frameIndexAt(9_999_999L, 30f, last))
        assertEquals(0, nearestFrameIndexAt(-5_000L, 30f, last))
        assertEquals(last, nearestFrameIndexAt(9_999_999L, 30f, last))
    }
}
