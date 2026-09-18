package com.onegallery.app.domain

import android.net.Uri

enum class MediaType {
    IMAGE,
    VIDEO,
    GIF,
    MOTION_PHOTO
}

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val path: String,
    val displayName: String,
    val mimeType: String,
    val mediaType: MediaType,
    val dateTaken: Long,
    val dateAdded: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long = 0L,
    val orientation: Int = 0,
    val bucketId: String,
    val bucketName: String,
    val isFavorite: Boolean = false
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 1f

    val isVideo: Boolean
        get() = mediaType == MediaType.VIDEO

    val formattedDuration: String
        get() {
            if (durationMs <= 0) return ""
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }
}

data class Album(
    val id: String,
    val name: String,
    val coverUri: Uri,
    val count: Int,
    val relativePath: String
)

/**
 * Groups an already-loaded media list into device folders (MediaStore buckets).
 *
 * Derives from the list the ViewModel already holds rather than issuing a second MediaStore
 * query — on a real library (3k+ items) a re-query is the single most expensive thing the UI
 * can do. Pure function, so it is cheap to `remember` and trivial to unit test.
 *
 * Expects [this] sorted newest-first (as `MediaStoreRepository` returns it), which makes the
 * first item of each group both the album cover and its recency key.
 */
fun List<MediaItem>.toAlbums(): List<Album> =
    groupBy { it.bucketId }
        .map { (bucketId, itemsInBucket) ->
            val newest = itemsInBucket.first()
            newest.dateTaken to Album(
                id = bucketId,
                name = newest.bucketName,
                coverUri = newest.uri,
                count = itemsInBucket.size,
                relativePath = newest.path.substringBeforeLast('/', "")
            )
        }
        .sortedByDescending { (newestDate, _) -> newestDate }
        .map { (_, album) -> album }

data class DateGroupedMedia(
    val dateHeader: String,
    val timestamp: Long,
    val items: List<MediaItem>
)
