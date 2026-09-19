package com.onegallery.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.onegallery.app.data.MediaThumbnailFetcher

/**
 * Supplies the app-wide Coil [ImageLoader].
 *
 * Exists for two reasons, both about scroll performance on a real library:
 *
 * 1. [MediaThumbnailFetcher] serves small requests from MediaStore's cached thumbnails instead
 *    of decoding full-size camera JPEGs.
 * 2. A larger memory cache. Coil's default is 20% of available heap; the grid and filmstrip
 *    show the same items repeatedly, and re-entering the grid after closing the viewer used to
 *    re-decode everything that had been evicted.
 */
class OneGalleryApplication : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(MediaThumbnailFetcher.Factory(), coil3.Uri::class)
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(this, 0.30)
                    .build()
            }
            // Crossfade animates every cell as it resolves, which reads as jitter during a
            // fast scrub rather than polish.
            .crossfade(false)
            .build()
}
