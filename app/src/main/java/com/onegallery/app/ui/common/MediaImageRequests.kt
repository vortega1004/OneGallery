package com.onegallery.app.ui.common

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Small enough that `MediaThumbnailFetcher` serves the request from MediaStore's cached
 * thumbnails (its cut-off is 512 px) instead of decoding the original file.
 */
private const val THUMBNAIL_REQUEST_PX = 512

/**
 * A thumbnail-sized request for [uri], for full-screen surfaces that want a cheap stand-in
 * (video poster, photo placeholder) rather than the real decode. Grid and filmstrip cells do
 * not need this — their layout size is already thumbnail-scale.
 */
@Composable
fun rememberThumbnailRequest(uri: Uri): ImageRequest {
    val context = LocalContext.current
    return remember(uri) {
        ImageRequest.Builder(context)
            .data(uri)
            .size(THUMBNAIL_REQUEST_PX)
            .build()
    }
}

/**
 * The full-resolution request for the viewer. While it decodes, whatever is already in the
 * memory cache for this Uri (normally the grid/filmstrip thumbnail) is shown as the
 * placeholder, so opening or swiping to a photo never flashes black.
 *
 * Relies on Coil keying un-transformed requests by `uri.toString()`. If that ever stops
 * matching, the only consequence is a missing placeholder — never a wrong image.
 */
@Composable
fun rememberFullSizeRequest(uri: Uri): ImageRequest {
    val context = LocalContext.current
    return remember(uri) {
        ImageRequest.Builder(context)
            .data(uri)
            .placeholderMemoryCacheKey(uri.toString())
            .crossfade(true)
            .build()
    }
}
