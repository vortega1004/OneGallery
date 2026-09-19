package com.onegallery.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.onegallery.app.domain.Album
import com.onegallery.app.domain.DateGroupedMedia
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.domain.MediaType
import com.onegallery.app.domain.toAlbums
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaStoreRepository(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    fun observeMediaItems(): Flow<List<MediaItem>> = callbackFlow<Unit> {
        // The observer only signals; the query itself runs downstream on Dispatchers.IO
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }

        // Register observers for both images and video
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )

        // Initial emission
        trySend(Unit)

        awaitClose {
            contentResolver.unregisterContentObserver(observer)
        }
    }
        .conflate()
        .transform<Unit, List<MediaItem>> {
            emit(queryMediaItems())
            // Bursts of changes (camera burst, sync, our own snapshot insert) collapse into one re-query
            delay(CHANGE_BURST_WINDOW_MS)
        }
        .flowOn(Dispatchers.IO)

    fun queryMediaItems(bucketIdFilter: String? = null): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.ORIENTATION,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
        )

        val selection = buildString {
            append("(")
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}")
            append(" OR ")
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}")
            append(")")
            if (bucketIdFilter != null) {
                append(" AND ${MediaStore.Files.FileColumns.BUCKET_ID} = ?")
            }
        }

        val selectionArgs = if (bucketIdFilter != null) arrayOf(bucketIdFilter) else null

        val queryUri = MediaStore.Files.getContentUri("external")

        contentResolver.query(
            queryUri,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val mediaTypeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
            val orientationCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.ORIENTATION)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(dataCol) ?: ""
                val displayName = cursor.getString(nameCol) ?: "Media_$id"
                val mimeType = cursor.getString(mimeCol) ?: "image/jpeg"
                val rawMediaType = cursor.getInt(mediaTypeCol)
                val dateTaken = cursor.getLong(dateTakenCol)
                val dateAdded = cursor.getLong(dateAddedCol)
                val size = cursor.getLong(sizeCol)
                val width = cursor.getInt(widthCol)
                val height = cursor.getInt(heightCol)
                val duration = cursor.getLong(durationCol)
                val orientation = cursor.getInt(orientationCol)
                val bucketId = cursor.getString(bucketIdCol) ?: "unknown"
                val bucketName = cursor.getString(bucketNameCol) ?: "Internal"

                val isVideo = rawMediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val isGif = mimeType.equals("image/gif", ignoreCase = true)

                val mediaType = when {
                    isVideo -> MediaType.VIDEO
                    isGif -> MediaType.GIF
                    else -> MediaType.IMAGE
                }

                val contentUri = if (isVideo) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }

                items.add(
                    MediaItem(
                        id = id,
                        uri = contentUri,
                        path = path,
                        displayName = displayName,
                        mimeType = mimeType,
                        mediaType = mediaType,
                        dateTaken = if (dateTaken > 0) dateTaken else dateAdded * 1000,
                        dateAdded = dateAdded,
                        size = size,
                        width = width,
                        height = height,
                        durationMs = duration,
                        orientation = orientation,
                        bucketId = bucketId,
                        bucketName = bucketName
                    )
                )
            }
        }

        // DATE_TAKEN is NULL for screenshots, downloads and many videos, so a SQL sort would push
        // those to the end. Sort on the resolved date (which falls back to DATE_ADDED) instead.
        items.sortWith(
            compareByDescending<MediaItem> { it.dateTaken }.thenByDescending { it.dateAdded }
        )

        return items
    }

    /**
     * Builds a standalone item for a Uri handed to us by another app (ACTION_VIEW),
     * which may not be part of MediaStore at all.
     */
    fun mediaItemFromUri(uri: Uri, mimeTypeHint: String?): MediaItem? {
        val mimeType = mimeTypeHint?.takeUnless { it.endsWith("/*") }
            ?: contentResolver.getType(uri)
            ?: return null
        val mediaType = when {
            mimeType.startsWith("video/") -> MediaType.VIDEO
            mimeType.equals("image/gif", ignoreCase = true) -> MediaType.GIF
            mimeType.startsWith("image/") -> MediaType.IMAGE
            else -> return null
        }

        var displayName = uri.lastPathSegment ?: "Media"
        var size = 0L
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameCol >= 0 && !cursor.isNull(nameCol)) displayName = cursor.getString(nameCol)
                    if (sizeCol >= 0 && !cursor.isNull(sizeCol)) size = cursor.getLong(sizeCol)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val now = System.currentTimeMillis()
        return MediaItem(
            id = -1L,
            uri = uri,
            path = "",
            displayName = displayName,
            mimeType = mimeType,
            mediaType = mediaType,
            dateTaken = now,
            dateAdded = now / 1000,
            size = size,
            width = 0,
            height = 0,
            bucketId = "external",
            bucketName = "Shared"
        )
    }

    /**
     * Standalone album query, for callers that do not already hold the media list.
     *
     * The UI does **not** use this — GalleryGridScreen derives albums from the list the
     * ViewModel already holds, via [toAlbums], avoiding a second full-library query on a
     * 3k-item device. Grouping logic lives in [toAlbums] so both paths stay in agreement.
     */
    suspend fun queryAlbums(): List<Album> = withContext(Dispatchers.IO) {
        queryMediaItems().toAlbums()
    }

    suspend fun queryGroupedByDate(): List<DateGroupedMedia> = withContext(Dispatchers.IO) {
        val items = queryMediaItems()
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        val todayFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val currentDay = todayFormat.format(Date())

        items.groupBy { item ->
            val itemDay = todayFormat.format(Date(item.dateTaken))
            when (itemDay) {
                currentDay -> "Today"
                else -> dateFormat.format(Date(item.dateTaken))
            }
        }.map { (header, list) ->
            DateGroupedMedia(
                dateHeader = header,
                timestamp = list.first().dateTaken,
                items = list
            )
        }
    }

    /**
     * Samsung Gallery "SaveVideoCaptureCmd" replication:
     * Saves captured video still bitmap into MediaStore with original video EXIF context.
     */
    suspend fun saveVideoStillSnapshot(
        bitmap: Bitmap,
        sourceVideo: MediaItem,
        playbackPositionMs: Long
    ): Uri? = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Snapshot_${timeStamp}_${playbackPositionMs}ms.jpg"
        val relativeSubDir = "${Environment.DIRECTORY_PICTURES}/OneGallery_Captures"

        // Date the still like the moment it shows, so it sorts next to its source video
        val frameTimeMs = if (sourceVideo.dateTaken > 0) {
            sourceVideo.dateTaken + playbackPositionMs
        } else {
            System.currentTimeMillis()
        }

        insertJpeg(
            fileName = fileName,
            relativeDir = relativeSubDir,
            dateTakenMs = frameTimeMs,
            bitmap = bitmap,
            quality = 98
        ) { uri ->
            writeSnapshotExif(uri, sourceVideo, playbackPositionMs, frameTimeMs)
        }
    }

    /**
     * Saves an edited photo as a **new** file in `Pictures/OneGallery_Edits`. The original is
     * never touched: overwriting another app's file needs a per-file user consent dialog under
     * scoped storage, and a copy is also what makes the edit undoable.
     *
     * Dated like the original so the copy sorts next to it rather than at the top of the grid.
     */
    suspend fun saveEditedImage(
        bitmap: Bitmap,
        source: MediaItem
    ): Uri? = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseName = source.displayName.substringBeforeLast('.').ifEmpty { "Photo" }
        val dateTakenMs = if (source.dateTaken > 0) source.dateTaken else System.currentTimeMillis()

        insertJpeg(
            fileName = "${baseName}_edited_$timeStamp.jpg",
            relativeDir = "${Environment.DIRECTORY_PICTURES}/OneGallery_Edits",
            dateTakenMs = dateTakenMs,
            bitmap = bitmap,
            quality = 95
        ) { uri ->
            writeEditedExif(uri, source, dateTakenMs)
        }
    }

    /**
     * One MediaStore insert transaction: pending row -> JPEG bytes -> EXIF -> publish.
     * EXIF is written while the row is still pending so observers see one finished file, and a
     * failure anywhere deletes the row instead of leaving an orphaned pending entry.
     */
    private fun insertJpeg(
        fileName: String,
        relativeDir: String,
        dateTakenMs: Long,
        bitmap: Bitmap,
        quality: Int,
        writeExif: (Uri) -> Unit
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMs)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativeDir)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        var uri: Uri? = null
        return try {
            val inserted = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            uri = inserted

            val outputStream = contentResolver.openOutputStream(inserted)
                ?: throw IOException("Unable to open $inserted for writing")
            outputStream.use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) {
                    throw IOException("JPEG encoding failed")
                }
            }

            writeExif(inserted)

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(inserted, values, null, null)

            inserted
        } catch (e: Exception) {
            e.printStackTrace()
            // Don't leave an orphaned pending row behind
            uri?.let { failed ->
                try {
                    contentResolver.delete(failed, null, null)
                } catch (_: Exception) {}
            }
            null
        }
    }

    /**
     * Carries the original's camera metadata over to the edited copy. Orientation is
     * deliberately left out: the pixels were already rotated upright before editing.
     */
    private fun writeEditedExif(uri: Uri, source: MediaItem, dateTakenMs: Long) {
        try {
            val sourceExif = try {
                contentResolver.openInputStream(source.uri)?.use { ExifInterface(it) }
            } catch (_: Exception) {
                null
            }

            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val destExif = ExifInterface(pfd.fileDescriptor)
                sourceExif?.let { original ->
                    COPIED_EXIF_TAGS.forEach { tag ->
                        original.getAttribute(tag)?.let { destExif.setAttribute(tag, it) }
                    }
                    original.latLong?.let { (latitude, longitude) ->
                        destExif.setLatLong(latitude, longitude)
                    }
                }
                if (destExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
                    val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(dateTakenMs))
                    destExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
                }
                destExif.setAttribute(ExifInterface.TAG_SOFTWARE, "OneGallery")
                destExif.saveAttributes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeSnapshotExif(
        uri: Uri,
        sourceVideo: MediaItem,
        playbackPositionMs: Long,
        frameTimeMs: Long
    ) {
        try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val destExif = ExifInterface(pfd.fileDescriptor)
                val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(frameTimeMs))
                destExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
                destExif.setAttribute(ExifInterface.TAG_DATETIME, exifDate)
                destExif.setAttribute(
                    ExifInterface.TAG_USER_COMMENT,
                    "Captured from video: ${sourceVideo.displayName} at ${playbackPositionMs}ms"
                )
                readVideoLocation(sourceVideo.uri)?.let { (latitude, longitude) ->
                    destExif.setLatLong(latitude, longitude)
                }
                destExif.saveAttributes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Reads the ISO-6709 location string ("+37.4219-122.0840/") recorded in the video container. */
    private fun readVideoLocation(videoUri: Uri): Pair<Double, Double>? {
        val retriever = MediaMetadataRetriever()
        return try {
            // MediaStore redacts location unless the original file is requested (ACCESS_MEDIA_LOCATION)
            try {
                retriever.setDataSource(context, MediaStore.setRequireOriginal(videoUri))
            } catch (_: Exception) {
                retriever.setDataSource(context, videoUri)
            }
            val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                ?: return null
            val match = ISO_6709_PATTERN.find(location) ?: return null
            val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
            val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
            latitude to longitude
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    private companion object {
        const val CHANGE_BURST_WINDOW_MS = 300L

        val COPIED_EXIF_TAGS = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE
        )
        val ISO_6709_PATTERN = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""")
    }
}
