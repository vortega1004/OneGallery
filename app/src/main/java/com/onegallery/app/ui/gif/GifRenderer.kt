package com.onegallery.app.ui.gif

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.onegallery.app.ui.editor.PhotoEditRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.min

internal object GifRenderer {
    const val FOLDER = "Pictures/OneGallery Gifs"

    /** Every source fits the same black canvas, then the entire canvas is rotated/cropped. */
    fun render(context: Context, frame: GifFrame, draft: GifDraft, edge: Int = draft.longEdge): Bitmap {
        val (width, height) = gifCanvasSize(draft.canvasAspect, edge)
        val source = PhotoEditRenderer.decode(context, Uri.parse(frame.uri), edge)
        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.BLACK)
            val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
            val w = source.width * scale
            val h = source.height * scale
            canvas.drawBitmap(source, null, RectF((width - w) / 2, (height - h) / 2,
                (width + w) / 2, (height + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            val rendered = PhotoEditRenderer.render(canvasBitmap, draft.edit)
            if (rendered !== canvasBitmap) canvasBitmap.recycle()
            return rendered
        } catch (t: Throwable) {
            canvasBitmap.recycle()
            throw t
        } finally {
            source.recycle()
        }
    }

    /** Publish only a complete file. Cancellation/error removes the pending MediaStore row. */
    suspend fun save(context: Context, draft: GifDraft, onProgress: (Int, Int) -> Unit): Uri =
        withContext(Dispatchers.IO) {
            require(draft.frames.size >= 2)
            val resolver = context.contentResolver
            val now = System.currentTimeMillis()
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "OneGallery_${now}_${UUID.randomUUID().toString().take(8)}.gif")
                put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OneGallery Gifs")
                put(MediaStore.Images.Media.DATE_TAKEN, now)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }) ?: error("Couldn't create the GIF file")
            var published = false
            try {
                val order = draft.order
                var width = 0
                var height = 0
                resolver.openOutputStream(uri)?.buffered()?.use { stream ->
                    var encoder: GifEncoder? = null
                    order.forEachIndexed { position, index ->
                        currentCoroutineContext().ensureActive()
                        val bitmap = render(context, draft.frames[index], draft)
                        try {
                            if (encoder == null) {
                                width = bitmap.width; height = bitmap.height
                                encoder = GifEncoder(stream, width, height)
                            }
                            val pixels = IntArray(width * height)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                            encoder!!.frame(pixels, draft.delayCentiseconds)
                        } finally {
                            bitmap.recycle()
                        }
                        currentCoroutineContext().ensureActive()
                        onProgress(position + 1, order.size)
                    }
                    encoder!!.finish()
                } ?: error("Couldn't open the GIF file")
                currentCoroutineContext().ensureActive()
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.WIDTH, width)
                    put(MediaStore.Images.Media.HEIGHT, height)
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null) == 1) { "Couldn't publish the GIF" }
                published = true
                uri
            } finally {
                if (!published) resolver.delete(uri, null, null)
            }
        }
}
