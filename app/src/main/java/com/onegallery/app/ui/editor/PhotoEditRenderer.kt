package com.onegallery.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a [PhotoEditState] into pixels. The editor preview and the saved file both go through
 * [orient], so what is saved is what was shown.
 *
 * Blocking — call from a background dispatcher.
 */
object PhotoEditRenderer {

    /** Longest side of the bitmap the editor works on. Enough for a phone screen, cheap to rotate. */
    const val PREVIEW_MAX_PX = 1600

    /**
     * Longest side of the saved file. Caps memory: a 50 MP source is ~200 MB as ARGB_8888 and
     * rendering holds two bitmaps at once. 4096 px keeps the peak near 100 MB.
     */
    const val SAVE_MAX_PX = 4096

    /**
     * Decodes [uri] no larger than [maxPx] on its longest side. `ImageDecoder` applies the EXIF
     * orientation itself, so the result is always upright and the saved copy needs no
     * orientation tag. Software-allocated because hardware bitmaps cannot be drawn to a Canvas.
     */
    fun decode(context: Context, uri: Uri, maxPx: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longestSide = max(info.size.width, info.size.height)
            if (longestSide > maxPx) {
                val ratio = maxPx.toFloat() / longestSide
                decoder.setTargetSize(
                    (info.size.width * ratio).roundToInt().coerceAtLeast(1),
                    (info.size.height * ratio).roundToInt().coerceAtLeast(1)
                )
            }
        }
    }

    /**
     * Applies the flip and rotation. Returns [source] itself when there is nothing to do, so
     * callers must compare identities before recycling.
     */
    fun orient(source: Bitmap, state: PhotoEditState): Bitmap {
        if (!state.flipHorizontal && state.rotationDegrees == 0) return source
        val matrix = Matrix().apply {
            // Order matters and mirrors PhotoEditState's canonical form: flip, then rotate
            if (state.flipHorizontal) postScale(-1f, 1f)
            if (state.rotationDegrees != 0) postRotate(state.rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Full pipeline: orient, crop, colour. Intermediate bitmaps are recycled; [source] never
     * is. The result may be [source] itself when the state changes nothing.
     */
    fun render(source: Bitmap, state: PhotoEditState): Bitmap {
        val oriented = orient(source, state)

        val cropped = crop(oriented, state.crop)
        if (cropped !== oriented && oriented !== source) oriented.recycle()

        if (!state.hasColorAdjustments) return cropped

        val output = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(state.colorMatrixValues()))
        }
        Canvas(output).drawBitmap(cropped, 0f, 0f, paint)
        if (cropped !== source) cropped.recycle()
        return output
    }

    private fun crop(bitmap: Bitmap, rect: NormalizedRect): Bitmap {
        if (rect.isFull) return bitmap
        val x = (rect.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val y = (rect.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val width = (rect.width * bitmap.width).roundToInt().coerceIn(1, bitmap.width - x)
        val height = (rect.height * bitmap.height).roundToInt().coerceIn(1, bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, width, height)
    }
}
