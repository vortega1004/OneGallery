package com.onegallery.app.ui.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The editor stores geometry as one canonical transform (flip, then rotate) no matter which
 * buttons were pressed in which order. These tests check that reduction against a brute-force
 * model: a tiny pixel grid that is *actually* flipped and rotated step by step.
 */
class PhotoEditStateTest {

    // ---- brute-force pixel model -------------------------------------------------------

    /** Row-major grid; every cell holds a unique value so any mix-up is detectable. */
    private class Grid(val width: Int, val height: Int, val cells: IntArray) {
        operator fun get(x: Int, y: Int) = cells[y * width + x]

        fun flippedHorizontally() = Grid(width, height, IntArray(cells.size) { i ->
            val x = i % width
            val y = i / width
            this[width - 1 - x, y]
        })

        /** 90° clockwise: the new image is height x width. */
        fun rotatedClockwise(): Grid {
            val newWidth = height
            val newHeight = width
            return Grid(newWidth, newHeight, IntArray(cells.size) { i ->
                val x = i % newWidth
                val y = i / newWidth
                // new (x, y) came from old (y, height - 1 - x)
                this[y, height - 1 - x]
            })
        }

        fun rotatedCounterClockwise() = rotatedClockwise().rotatedClockwise().rotatedClockwise()
    }

    private fun sourceGrid() = Grid(3, 2, IntArray(6) { it })

    /** What PhotoEditRenderer.orient does: flip first, then rotate clockwise. */
    private fun Grid.applyCanonical(state: PhotoEditState): Grid {
        var result = if (state.flipHorizontal) flippedHorizontally() else this
        repeat(state.rotationDegrees / 90) { result = result.rotatedClockwise() }
        return result
    }

    // ---- geometry reduction ------------------------------------------------------------

    @Test
    fun `any button sequence reduces to the same pixels as applying it step by step`() {
        val random = Random(seed = 42)
        repeat(200) {
            var state = PhotoEditState()
            var expected = sourceGrid()

            repeat(random.nextInt(1, 9)) {
                when (random.nextInt(3)) {
                    0 -> {
                        state = state.rotateClockwise()
                        expected = expected.rotatedClockwise()
                    }
                    1 -> {
                        state = state.rotateCounterClockwise()
                        expected = expected.rotatedCounterClockwise()
                    }
                    else -> {
                        state = state.flipHorizontally()
                        expected = expected.flippedHorizontally()
                    }
                }
            }

            val actual = sourceGrid().applyCanonical(state)
            assertEquals(expected.width, actual.width)
            assertEquals(expected.height, actual.height)
            assertArrayEquals(expected.cells, actual.cells)
        }
    }

    @Test
    fun `four clockwise rotations are the identity`() {
        val start = PhotoEditState(crop = NormalizedRect(0.1f, 0.2f, 0.6f, 0.9f))
        val end = start.rotateClockwise().rotateClockwise().rotateClockwise().rotateClockwise()
        assertEquals(start.rotationDegrees, end.rotationDegrees)
        assertRectEquals(start.crop, end.crop)
    }

    @Test
    fun `clockwise then counter-clockwise is the identity`() {
        val start = PhotoEditState(crop = NormalizedRect(0.1f, 0.2f, 0.6f, 0.9f))
        val end = start.rotateClockwise().rotateCounterClockwise()
        assertEquals(0, end.rotationDegrees)
        assertRectEquals(start.crop, end.crop)
    }

    @Test
    fun `flipping twice is the identity even when rotated`() {
        val start = PhotoEditState().rotateClockwise().copy(crop = NormalizedRect(0.1f, 0.2f, 0.6f, 0.9f))
        val end = start.flipHorizontally().flipHorizontally()
        assertEquals(start.flipHorizontal, end.flipHorizontal)
        assertEquals(start.rotationDegrees, end.rotationDegrees)
        assertRectEquals(start.crop, end.crop)
    }

    // ---- crop rectangle ----------------------------------------------------------------

    @Test
    fun `crop follows the image through a clockwise rotation`() {
        // A crop hugging the top-left corner ends up hugging the top-right corner
        val rotated = NormalizedRect(0f, 0f, 0.25f, 0.5f).rotatedClockwise()
        assertRectEquals(NormalizedRect(0.5f, 0f, 1f, 0.25f), rotated)
    }

    @Test
    fun `crop follows the image through a mirror flip`() {
        val flipped = NormalizedRect(0f, 0.1f, 0.25f, 0.5f).flippedHorizontally()
        assertRectEquals(NormalizedRect(0.75f, 0.1f, 1f, 0.5f), flipped)
    }

    @Test
    fun `dragging a corner cannot invert or shrink the crop below the minimum`() {
        val rect = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)
        val dragged = rect.dragged(CropHandle.TOP_LEFT, dx = 5f, dy = 5f)
        assertEquals(0.8f - NormalizedRect.MIN_SIZE, dragged.left, EPSILON)
        assertEquals(0.8f - NormalizedRect.MIN_SIZE, dragged.top, EPSILON)
        assertEquals(0.8f, dragged.right, EPSILON)
    }

    @Test
    fun `moving the crop stops at the image edge without resizing it`() {
        val rect = NormalizedRect(0.2f, 0.2f, 0.6f, 0.7f)
        val moved = rect.dragged(CropHandle.MOVE, dx = 5f, dy = -5f)
        assertEquals(1f, moved.right, EPSILON)
        assertEquals(0f, moved.top, EPSILON)
        assertEquals(rect.width, moved.width, EPSILON)
        assertEquals(rect.height, moved.height, EPSILON)
    }

    @Test
    fun `centered preset has the requested pixel aspect ratio`() {
        val imageAspect = 4f / 3f
        val square = NormalizedRect.centered(aspect = 1f, imageAspect = imageAspect)
        // Normalized width/height scaled back to pixels must be 1:1
        assertEquals(1f, (square.width * imageAspect) / square.height, EPSILON)
        assertEquals(square.left, 1f - square.right, EPSILON)

        val wide = NormalizedRect.centered(aspect = 16f / 9f, imageAspect = imageAspect)
        assertEquals(16f / 9f, (wide.width * imageAspect) / wide.height, EPSILON)
        assertEquals(0f, wide.left, EPSILON)
    }

    // ---- change tracking & colour ------------------------------------------------------

    @Test
    fun `a fresh state reports no changes and an identity colour matrix`() {
        val state = PhotoEditState()
        assertFalse(state.hasChanges)
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        assertArrayEquals(identity, state.colorMatrixValues(), EPSILON)
    }

    @Test
    fun `zero saturation maps every channel to the same luminance`() {
        val m = PhotoEditState(saturation = 0f).colorMatrixValues()
        assertTrue(PhotoEditState(saturation = 0f).hasColorAdjustments)
        // The three colour rows must be identical, and their weights must sum to 1
        for (column in 0..4) {
            assertEquals(m[column], m[5 + column], EPSILON)
            assertEquals(m[column], m[10 + column], EPSILON)
        }
        assertEquals(1f, m[0] + m[1] + m[2], EPSILON)
    }

    private fun assertRectEquals(expected: NormalizedRect, actual: NormalizedRect) {
        assertEquals(expected.left, actual.left, EPSILON)
        assertEquals(expected.top, actual.top, EPSILON)
        assertEquals(expected.right, actual.right, EPSILON)
        assertEquals(expected.bottom, actual.bottom, EPSILON)
    }

    private companion object {
        const val EPSILON = 1e-5f
    }
}
