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
    val relativePath: String,
    /** Date of the most recent item, used for "recently updated" ordering. */
    val newestDate: Long = 0L,
    /** Date of the oldest item, so "oldest first" means the album you started longest ago. */
    val oldestDate: Long = 0L,
    /** Sum of every item's file size, for ordering by disk footprint. */
    val totalBytes: Long = 0L,
    val videoCount: Int = 0
) {
    val photoCount: Int get() = count - videoCount
}

/** Ordering options offered on the Albums tab. */
enum class AlbumSort(val label: String) {
    NAME_ASC("Name (A–Z)"),
    NAME_DESC("Name (Z–A)"),
    NEWEST_FIRST("Newest first"),
    OLDEST_FIRST("Oldest first"),
    MOST_ITEMS("Most items"),
    FEWEST_ITEMS("Fewest items"),
    LARGEST("Largest on disk"),
    SMALLEST("Smallest on disk");

    companion object {
        val DEFAULT = NEWEST_FIRST
    }
}

/**
 * Names are compared case-insensitively so "DCIM" and "dcim" sort together rather than all
 * capitalised folders being grouped ahead of lowercase ones by their char codes.
 */
fun List<Album>.sortedBy(sort: AlbumSort): List<Album> = when (sort) {
    AlbumSort.NAME_ASC -> sortedBy { it.name.lowercase() }
    AlbumSort.NAME_DESC -> sortedByDescending { it.name.lowercase() }
    AlbumSort.NEWEST_FIRST -> sortedByDescending { it.newestDate }
    AlbumSort.OLDEST_FIRST -> sortedBy { it.oldestDate }
    AlbumSort.MOST_ITEMS -> sortedByDescending { it.count }
    AlbumSort.FEWEST_ITEMS -> sortedBy { it.count }
    AlbumSort.LARGEST -> sortedByDescending { it.totalBytes }
    AlbumSort.SMALLEST -> sortedBy { it.totalBytes }
}

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
            Album(
                id = bucketId,
                name = newest.bucketName,
                coverUri = newest.uri,
                count = itemsInBucket.size,
                relativePath = newest.path.substringBeforeLast('/', ""),
                newestDate = newest.dateTaken,
                oldestDate = itemsInBucket.last().dateTaken,
                totalBytes = itemsInBucket.sumOf { it.size },
                videoCount = itemsInBucket.count { it.isVideo }
            )
        }
        .sortedBy(AlbumSort.DEFAULT)

data class DateGroupedMedia(
    val dateHeader: String,
    val timestamp: Long,
    val items: List<MediaItem>
)
