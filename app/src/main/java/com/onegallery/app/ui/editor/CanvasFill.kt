package com.onegallery.app.ui.editor

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Putting the photo on a larger canvas of a chosen shape, with everything it does not cover
 * filled with one flat colour — green by default, for keying out later.
 *
 * The photo is never stretched, cropped or filled in. The canvas is the smallest one of the
 * chosen shape that holds it, made larger by [room] if the user wants space on every side, and
 * the photo sits wherever they dragged it ([anchorX], [anchorY]).
 *
 * Applied last: after the crop, so "crop a bit, then place it" works, and after the colour
 * adjustments, which must never tint the fill.
 *
 * Plain values only, like the rest of the edit state, so the geometry is unit-tested.
 */
data class CanvasFill(
    /** Canvas width over height, or null when the photo is left as it is. */
    val aspect: Float? = null,
    /** How much bigger than the smallest canvas that fits: 1 = snug, 2 = twice as wide and tall. */
    val room: Float = 1f,
    /** Where the photo sits in the spare width: 0 = hard left, 0.5 = centred, 1 = hard right. */
    val anchorX: Float = 0.5f,
    /** The same, top to bottom. */
    val anchorY: Float = 0.5f,
    /** Fill colour, ARGB. */
    val color: Int = DEFAULT_COLOR
) {
    val isActive: Boolean get() = aspect != null

    /**
     * Where everything goes for a photo of [imageWidth] x [imageHeight] pixels, or null when no
     * shape is chosen.
     *
     * The photo keeps its own pixels unless the canvas would exceed [maxPixels]; then the whole
     * composition is scaled down together, so the result is the same picture, smaller. That
     * only happens at the extremes (a large photo, a very different shape and a lot of room).
     */
    fun layout(imageWidth: Int, imageHeight: Int, maxPixels: Long = MAX_CANVAS_PIXELS): CanvasLayout? {
        val shape = aspect ?: return null
        if (imageWidth <= 0 || imageHeight <= 0 || shape <= 0f) return null
        val spare = room.coerceIn(MIN_ROOM, MAX_ROOM)

        // The smallest canvas of this shape that holds the photo, then the extra room
        val imageAspect = imageWidth.toFloat() / imageHeight
        var canvasWidth: Float
        var canvasHeight: Float
        if (imageAspect >= shape) {
            canvasWidth = imageWidth * spare
            canvasHeight = canvasWidth / shape
        } else {
            canvasHeight = imageHeight * spare
            canvasWidth = canvasHeight * shape
        }

        var drawnWidth = imageWidth.toFloat()
        var drawnHeight = imageHeight.toFloat()
        val pixels = canvasWidth.toDouble() * canvasHeight
        if (pixels > maxPixels) {
            val shrink = sqrt(maxPixels / pixels).toFloat()
            canvasWidth *= shrink
            canvasHeight *= shrink
            drawnWidth *= shrink
            drawnHeight *= shrink
        }

        val width = canvasWidth.roundToInt().coerceAtLeast(1)
        val height = canvasHeight.roundToInt().coerceAtLeast(1)
        val photoWidth = drawnWidth.roundToInt().coerceIn(1, width)
        val photoHeight = drawnHeight.roundToInt().coerceIn(1, height)
        return CanvasLayout(
            canvasWidth = width,
            canvasHeight = height,
            imageLeft = ((width - photoWidth) * anchorX.coerceIn(0f, 1f)).roundToInt(),
            imageTop = ((height - photoHeight) * anchorY.coerceIn(0f, 1f)).roundToInt(),
            imageWidth = photoWidth,
            imageHeight = photoHeight
        )
    }

    /**
     * The fill after the photo was dragged by ([dx], [dy]), given as fractions of the canvas's
     * width and height, for a photo covering [imageWidthFraction] x [imageHeightFraction] of it.
     * A direction with no spare room does not move.
     */
    fun dragged(dx: Float, dy: Float, imageWidthFraction: Float, imageHeightFraction: Float): CanvasFill {
        val spareX = 1f - imageWidthFraction
        val spareY = 1f - imageHeightFraction
        return copy(
            anchorX = if (spareX > NO_ROOM) (anchorX + dx / spareX).coerceIn(0f, 1f) else anchorX,
            anchorY = if (spareY > NO_ROOM) (anchorY + dy / spareY).coerceIn(0f, 1f) else anchorY
        )
    }

    companion object {
        /** Chroma-key green. What the fill is every time the editor opens, whatever was used last. */
        const val DEFAULT_COLOR: Int = 0xFF00FF00.toInt()

        const val MIN_ROOM = 1f
        const val MAX_ROOM = 3f

        /**
         * Largest canvas that is rendered at the photo's own scale: 24 megapixels, 96 MB as a
         * bitmap. A 12 MP photo made 9:16 with no extra room is 28 MP, so that one case already
         * comes out a little smaller than the source.
         */
        const val MAX_CANVAS_PIXELS = 24_000_000L

        private const val NO_ROOM = 0.0005f
    }
}

/** Pixel positions for one render: the canvas, and the rectangle the photo is drawn into. */
data class CanvasLayout(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val imageLeft: Int,
    val imageTop: Int,
    val imageWidth: Int,
    val imageHeight: Int
)

/** A shape offered for the canvas. [aspect] is width over height. */
data class CanvasShape(val label: String, val aspect: Float)

val CANVAS_SHAPES = listOf(
    CanvasShape("1:1", 1f),
    CanvasShape("4:5", 4f / 5f),
    CanvasShape("5:4", 5f / 4f),
    CanvasShape("3:4", 3f / 4f),
    CanvasShape("4:3", 4f / 3f),
    CanvasShape("2:3", 2f / 3f),
    CanvasShape("3:2", 3f / 2f),
    CanvasShape("9:16", 9f / 16f),
    CanvasShape("16:9", 16f / 9f)
)

/**
 * A position that settles on the left edge, the middle or the right edge when it comes close,
 * so "exactly centred" and "hard against the side" can be hit by thumb.
 */
fun snapAnchor(value: Float, tolerance: Float = 0.035f): Float = when {
    value <= tolerance -> 0f
    value >= 1f - tolerance -> 1f
    kotlin.math.abs(value - 0.5f) <= tolerance -> 0.5f
    else -> value
}

// --- Recently used fill colours ----------------------------------------------------------------

/** How many fill colours are remembered. */
const val MAX_RECENT_COLORS = 5

/**
 * [recent] with [color] put first. The default green is never listed — it has a place of its
 * own that it never loses — and neither is a colour twice.
 */
fun rememberedColors(recent: List<Int>, color: Int): List<Int> {
    val opaque = color or OPAQUE
    if (opaque == CanvasFill.DEFAULT_COLOR) return recent.take(MAX_RECENT_COLORS)
    return (listOf(opaque) + recent.filter { it != opaque }).take(MAX_RECENT_COLORS)
}

/** "FF8800,0044CC" — six hex digits each, newest first. */
fun encodeColors(colors: List<Int>): String =
    colors.joinToString(",") { colorToHex(it) }

fun decodeColors(text: String): List<Int> =
    text.split(',').mapNotNull { hexToColor(it) }.distinct().take(MAX_RECENT_COLORS)

/** "RRGGBB", upper case. */
fun colorToHex(color: Int): String = String.format(java.util.Locale.US, "%06X", color and 0xFFFFFF)

/** Reads "RRGGBB" or "#RRGGBB" (any case) as an opaque colour; null for anything else. */
fun hexToColor(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6) return null
    return digits.toIntOrNull(16)?.let { it or OPAQUE }
}

private const val OPAQUE: Int = 0xFF000000.toInt()
