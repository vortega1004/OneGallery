package com.onegallery.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Placing a photo on a canvas of another shape. The promise under test: the photo keeps its own
 * pixels, and the canvas is the smallest of the chosen shape that holds it.
 */
class CanvasFillTest {

    private val square = CanvasFill(aspect = 1f)

    @Test fun `no shape chosen means no canvas`() {
        assertNull(CanvasFill().layout(1080, 1920))
    }

    @Test fun `a portrait made square grows sideways and keeps its size`() {
        val layout = square.layout(1080, 1920)!!
        assertEquals(1920, layout.canvasWidth)
        assertEquals(1920, layout.canvasHeight)
        assertEquals(1080, layout.imageWidth)
        assertEquals(1920, layout.imageHeight)
    }

    @Test fun `a landscape made square grows downwards and keeps its size`() {
        val layout = square.layout(1920, 1080)!!
        assertEquals(1920 to 1920, layout.canvasWidth to layout.canvasHeight)
        assertEquals(1920 to 1080, layout.imageWidth to layout.imageHeight)
    }

    @Test fun `the photo goes hard left, centre or hard right`() {
        assertEquals(0, square.copy(anchorX = 0f).layout(1080, 1920)!!.imageLeft)
        assertEquals(420, square.copy(anchorX = 0.5f).layout(1080, 1920)!!.imageLeft)
        assertEquals(840, square.copy(anchorX = 1f).layout(1080, 1920)!!.imageLeft)
    }

    @Test fun `with no spare height the photo cannot sit anywhere but the top`() {
        for (anchorY in listOf(0f, 0.5f, 1f)) {
            assertEquals(0, square.copy(anchorY = anchorY).layout(1080, 1920)!!.imageTop)
        }
    }

    @Test fun `sixteen by nine around a portrait`() {
        val layout = CanvasFill(aspect = 16f / 9f).layout(1080, 1920)!!
        assertEquals(3413, layout.canvasWidth)
        assertEquals(1920, layout.canvasHeight)
    }

    @Test fun `a photo already the chosen shape gets a canvas its own size`() {
        val layout = square.layout(1000, 1000)!!
        assertEquals(1000 to 1000, layout.canvasWidth to layout.canvasHeight)
        assertEquals(0 to 0, layout.imageLeft to layout.imageTop)
    }

    @Test fun `room leaves space on every side`() {
        val layout = square.copy(room = 2f).layout(1000, 1000)!!
        assertEquals(2000 to 2000, layout.canvasWidth to layout.canvasHeight)
        assertEquals(1000 to 1000, layout.imageWidth to layout.imageHeight)
        assertEquals(500 to 500, layout.imageLeft to layout.imageTop)
    }

    @Test fun `a canvas past the limit shrinks the whole picture together`() {
        // 4000 x 3000 made 9:16 would be 4000 x 7111 = 28 MP
        val layout = CanvasFill(aspect = 9f / 16f).layout(4000, 3000, maxPixels = 24_000_000L)!!
        assert(layout.canvasWidth.toLong() * layout.canvasHeight <= 24_010_000L)
        // Same proportions as the unshrunk version: the photo still spans the full width
        assertEquals(layout.canvasWidth, layout.imageWidth)
        assertEquals(4f / 3f, layout.imageWidth.toFloat() / layout.imageHeight, 0.01f)
        assertEquals(9f / 16f, layout.canvasWidth.toFloat() / layout.canvasHeight, 0.01f)
    }

    @Test fun `dragging moves the photo by the same distance as the finger`() {
        // The photo covers 1080/1920 of the width. Dragging by the whole spare width goes from
        // hard left to hard right.
        val photoFraction = 1080f / 1920f
        val moved = square.copy(anchorX = 0f).dragged(1f - photoFraction, 0f, photoFraction, 1f)
        assertEquals(1f, moved.anchorX, 0.0001f)
        val half = square.copy(anchorX = 0f).dragged((1f - photoFraction) / 2f, 0f, photoFraction, 1f)
        assertEquals(0.5f, half.anchorX, 0.0001f)
    }

    @Test fun `dragging stops at the edges and ignores a direction with no room`() {
        val photoFraction = 1080f / 1920f
        val pushed = square.dragged(5f, 5f, photoFraction, 1f)
        assertEquals(1f, pushed.anchorX, 0f)
        assertEquals(0.5f, pushed.anchorY, 0f)
    }

    @Test fun `a position near an edge or the middle settles on it`() {
        assertEquals(0f, snapAnchor(0.02f), 0f)
        assertEquals(0.5f, snapAnchor(0.52f), 0f)
        assertEquals(1f, snapAnchor(0.98f), 0f)
        assertEquals(0.3f, snapAnchor(0.3f), 0f)
    }

    @Test fun `the saved size follows the crop and then the canvas`() {
        // 1080 x 1920, cropped to its top half (1080 x 960), made square: 1080 x 1080
        val state = PhotoEditState(
            crop = NormalizedRect(0f, 0f, 1f, 0.5f),
            canvas = CanvasFill(aspect = 1f)
        )
        assertEquals(1080 to 1080, state.outputSize(1080, 1920, maxSourceSide = 4096))
    }

    @Test fun `the saved size of a large source starts from the reduced copy`() {
        val state = PhotoEditState(canvas = CanvasFill(aspect = 1f))
        // 8192 x 4096 is saved from a 4096 x 2048 copy
        assertEquals(4096 to 4096, state.outputSize(8192, 4096, maxSourceSide = 4096))
    }

    @Test fun `without a canvas the saved size is the cropped photo`() {
        assertEquals(1080 to 1920, PhotoEditState().outputSize(1080, 1920, maxSourceSide = 4096))
        assertEquals(1920 to 1080, PhotoEditState().rotateClockwise().outputSize(1080, 1920, maxSourceSide = 4096))
    }

    // --- remembered colours --------------------------------------------------------------------

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test fun `a colour that is used goes to the front`() {
        assertEquals(listOf(blue, red), rememberedColors(listOf(red), blue))
        assertEquals(listOf(red, blue), rememberedColors(listOf(blue, red), red))
    }

    @Test fun `the default green is never listed`() {
        assertEquals(listOf(red), rememberedColors(listOf(red), CanvasFill.DEFAULT_COLOR))
    }

    @Test fun `only the last five are kept`() {
        var recent = emptyList<Int>()
        for (i in 1..7) recent = rememberedColors(recent, 0xFF000000.toInt() or i)
        assertEquals(5, recent.size)
        assertEquals(0xFF000007.toInt(), recent.first())
        assertEquals(0xFF000003.toInt(), recent.last())
    }

    @Test fun `colours survive being saved and read back`() {
        val colors = listOf(red, blue, 0xFF0A0B0C.toInt())
        assertEquals("FF0000,0000FF,0A0B0C", encodeColors(colors))
        assertEquals(colors, decodeColors(encodeColors(colors)))
        assertEquals(emptyList<Int>(), decodeColors(""))
        assertEquals(listOf(red), decodeColors("nonsense,FF0000,12"))
    }

    @Test fun `a hex code is read with or without its hash, in either case`() {
        assertEquals(0xFF00B140.toInt(), hexToColor("#00b140"))
        assertEquals(0xFF00B140.toInt(), hexToColor("00B140"))
        assertNull(hexToColor("00B14"))
        assertNull(hexToColor("zzzzzz"))
    }
}
