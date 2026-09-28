package com.onegallery.app.ui.gif

import org.junit.Assert.assertEquals
import org.junit.Test
import com.onegallery.app.ui.editor.NormalizedRect
import com.onegallery.app.ui.editor.PhotoEditState

class GifDraftTest {
    @Test fun `loop ordering does not duplicate boomerang endpoints`() {
        assertEquals(listOf(0, 1, 2, 3), gifFrameOrder(4, GifLoop.FORWARD))
        assertEquals(listOf(3, 2, 1, 0), gifFrameOrder(4, GifLoop.REVERSE))
        assertEquals(listOf(0, 1, 2, 3, 2, 1), gifFrameOrder(4, GifLoop.BOOMERANG))
        assertEquals(listOf(0, 1), gifFrameOrder(2, GifLoop.BOOMERANG))
        assertEquals(listOf(0), gifFrameOrder(1, GifLoop.BOOMERANG))
        assertEquals(emptyList<Int>(), gifFrameOrder(0, GifLoop.BOOMERANG))
    }

    @Test fun `select between follows visible order even when endpoints were tapped backwards`() {
        val ids = listOf(50L, 12L, 99L, 1L, 30L)
        assertEquals(setOf(12L, 99L, 1L), selectionBetween(ids, setOf(1L, 12L)))
        assertEquals(setOf(12L), selectionBetween(ids, setOf(12L)))
        assertEquals(setOf(12L, 404L), selectionBetween(ids, setOf(12L, 404L)))
    }

    @Test fun `duplicate inserts immediately after active and remove targets only that copy`() {
        val frames = (0..2).map { GifFrame(it.toLong(), "uri:$it", "$it") }
        val draft = GifDraft(frames, activeIndex = 1)
        val copy = draft.duplicateActive(9)
        assertEquals(listOf(0L, 1L, 9L, 2L), copy.frames.map { it.key })
        assertEquals("uri:1", copy.frames[2].uri)
        assertEquals(2, copy.activeIndex)
        assertEquals(frames, copy.removeActive().frames)
        assertEquals(listOf(1L, 0L, 2L), draft.moveActive(-1).frames.map { it.key })
        val pair = GifDraft(frames.take(2))
        assertEquals(pair, pair.removeActive())
    }

    @Test fun `duplicate stops at the ceiling instead of growing past it`() {
        val full = GifDraft((0 until MAX_GIF_FRAMES).map { GifFrame(it.toLong(), "$it", "$it") })
        assertEquals(full, full.duplicateActive(999))

        val room = GifDraft((0 until MAX_GIF_FRAMES - 1).map { GifFrame(it.toLong(), "$it", "$it") })
        assertEquals(MAX_GIF_FRAMES, room.duplicateActive(999).frames.size)
    }

    @Test fun `duration reflects exported loop and centisecond timing`() {
        val frames = (0..3).map { GifFrame(it.toLong(), "$it", "$it") }
        assertEquals(240L, GifDraft(frames, loop = GifLoop.BOOMERANG, delayCentiseconds = 4).durationMs)
        assertEquals(720 to 540, gifCanvasSize(4f / 3, 720))
        assertEquals(540 to 720, gifCanvasSize(3f / 4, 720))
        val portraitCrop = PhotoEditState(rotationDegrees = 90,
            crop = NormalizedRect.centered(1f, 9f / 16))
        assertEquals(405 to 405, GifDraft(frames, canvasAspect = 16f / 9, edit = portraitCrop).outputSize)
    }
}
