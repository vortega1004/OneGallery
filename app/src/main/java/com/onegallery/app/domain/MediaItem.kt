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

data class DateGroupedMedia(
    val dateHeader: String,
    val timestamp: Long,
    val items: List<MediaItem>
)
