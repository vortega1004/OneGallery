package com.onegallery.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.toAndroidUri
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Serves grid and filmstrip cells from MediaStore's own pre-generated thumbnails.
 *
 * Coil's default path opens the original file and downsamples it. That is correct but far too
 * expensive here: a 12 MP camera JPEG gets decoded to fill a 44x60 dp filmstrip cell, and on a
 * 3k-item library doing that per cell during a scrub is what makes the strip stutter.
 * `ContentResolver.loadThumbnail` returns a thumbnail MediaStore has already generated and
 * cached, which is a fraction of the work.
 *
 * **Only small requests are intercepted** (see [MAX_THUMBNAIL_PX]). The fullscreen pager asks
 * for display-sized images and must keep the original decode path, or photos would render from
 * a blurry thumbnail. Returning `null` from the factory hands the request back to Coil.
 *
 * Requires API 29+, which `minSdk` guarantees.
 */
class MediaThumbnailFetcher(
    private val context: Context,
    private val uri: Uri,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val bitmap = try {
            context.contentResolver.loadThumbnail(uri, requestedSize(), null)
        } catch (e: Exception) {
            // No thumbnail, or the item vanished mid-scroll. Returning null lets Coil fall
            // back to decoding the original rather than showing a hole.
            null
        } ?: return@withContext null

        ImageFetchResult(
            image = bitmap.asImage(),
            // The bitmap is smaller than the source, so Coil must not treat it as full-size.
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    private fun requestedSize(): Size {
        val width = options.size.width.pxOrNull() ?: FALLBACK_PX
        val height = options.size.height.pxOrNull() ?: FALLBACK_PX
        return Size(
            width.coerceIn(MIN_THUMBNAIL_PX, MAX_THUMBNAIL_PX),
            height.coerceIn(MIN_THUMBNAIL_PX, MAX_THUMBNAIL_PX)
        )
    }

    /**
     * Keyed on [coil3.Uri], **not** `android.net.Uri`. Coil runs mappers before fetchers, and
     * `AndroidUriMapper` has already converted the platform Uri by this point — a factory
     * declared over `android.net.Uri` is simply never called.
     */
    class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(
            data: coil3.Uri,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            if (data.scheme != ContentResolver.SCHEME_CONTENT) return null
            if (data.authority != MediaStore.AUTHORITY) return null
            if (!options.size.isThumbnailScale()) return null
            return MediaThumbnailFetcher(options.context, data.toAndroidUri(), options)
        }
    }

    private companion object {
        /**
         * Above this, assume the caller wants a real image (the fullscreen viewer) and leave it
         * to Coil's normal decoder. Comfortably larger than any grid cell, even at 1 column on
         * a tablet.
         */
        const val MAX_THUMBNAIL_PX = 512

        /** MediaStore rejects zero/negative sizes. */
        const val MIN_THUMBNAIL_PX = 32

        /** Used when a dimension is unconstrained; still thumbnail-scale. */
        const val FALLBACK_PX = 256
    }
}

private fun Dimension.pxOrNull(): Int? = (this as? Dimension.Pixels)?.px

/**
 * True when both dimensions are known and small enough to be a thumbnail. An unbounded or
 * `ORIGINAL` size means "give me the real thing", so it is deliberately excluded.
 */
private fun coil3.size.Size.isThumbnailScale(): Boolean {
    val w = width.pxOrNull() ?: return false
    val h = height.pxOrNull() ?: return false
    return w in 1..512 && h in 1..512
}
