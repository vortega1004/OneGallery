package com.onegallery.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
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
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaStoreRepository(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * Paths already handed to the scanner in this process. A row the scanner decides to keep is
     * not re-reported on every re-query — the scanner has the final word, and asking it again
     * each time a photo arrives would be noise.
     */
    private val scanRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Paths found on disk since the last full sweep — see [reconcileMissingFiles]. */
    private val seenOnDisk: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var lastFullSweepMs = 0L

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
            val items = queryMediaItems()
            emit(items)
            // After emitting, so the grid never waits on it
            reconcileMissingFiles(items)
            // Bursts of changes (camera burst, sync, our own snapshot insert) collapse into one re-query
            delay(CHANGE_BURST_WINDOW_MS)
        }
        .flowOn(Dispatchers.IO)

    /**
     * Asks MediaStore to re-check any row whose file is no longer where the row says it is.
     *
     * MediaStore can hold a row for a file that has gone — most visibly after another app moves
     * a batch of files, where an occasional original is left behind as a row pointing at an
     * empty spot in the old folder. Anything that reads the filesystem (a file manager) or keeps
     * its own index (Google Photos) never sees it; a gallery that trusts MediaStore shows a
     * phantom in the folder the file left. Reproduced on the emulator by inserting a row for a
     * path that does not exist: it appeared as its own album.
     *
     * The check here only *nominates*. It never hides anything on its own say-so, because a
     * `File.exists()` that comes back false for a file that is really there — an access quirk,
     * a volume mid-mount — would make a real photo vanish. The scanner has full access and the
     * final word: for a path that is really empty it deletes the row, which notifies the
     * observer above and the phantom leaves with the next query. It repairs MediaStore itself,
     * so every other app that reads it is corrected too.
     */
    private fun reconcileMissingFiles(items: List<MediaItem>) {
        // One stat per file is cheap; one per file per re-query is not, on a library of thousands
        // where a single snapshot causes three re-queries. Paths seen to exist are trusted until
        // the next full sweep, which still catches a file that another app removed later.
        val now = SystemClock.elapsedRealtime()
        if (now - lastFullSweepMs >= FULL_SWEEP_INTERVAL_MS) {
            lastFullSweepMs = now
            seenOnDisk.clear()
        }
        val missing = items.asSequence()
            .map { it.path }
            .filter { it.isNotBlank() && it !in scanRequested && seenOnDisk.add(it) && !File(it).exists() }
            .toList()
        if (missing.isEmpty()) return
        scanRequested.addAll(missing)
        MediaScannerConnection.scanFile(context, missing.toTypedArray(), null, null)
    }

    /**
     * The whole library (or one bucket), read in pages keyed on `_id`.
     *
     * **Why not one query.** A cursor from another process is delivered in windows of about
     * 2 MB, and each window after the first is filled by running the query *again* and skipping
     * to a row number. If a row is inserted between two fills, every row after it moves down
     * one: the row on the boundary is read twice and the last row is never read at all — while
     * the row count, taken on the first fill, still looks right. Rows come back grouped by media
     * type, so the row that goes missing is the newest video, and the insert that does it is
     * this app's own snapshot. The video being watched dropped out of the library for one
     * refresh and the viewer moved on to its neighbour. It needs a library big enough to span
     * two windows (a few thousand items, fewer with long file names), which is why it only ever
     * happened on a real phone.
     *
     * A page is far smaller than a window, so each one is a single consistent read, and asking
     * for "ids after the last one seen" instead of "rows from position n" means a change made
     * between two pages cannot shift anything: a new row is either picked up or left for the
     * next refresh, which the observer is already about to trigger.
     */
    fun queryMediaItems(bucketIdFilter: String? = null): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        var afterId = -1L
        while (true) {
            val read = queryPage(afterId, bucketIdFilter, items)
            if (read < PAGE_SIZE) break
            afterId = items.last().id
        }

        // DATE_TAKEN is NULL for screenshots, downloads and many videos, so a SQL sort would push
        // those to the end. Sort on the resolved date (which falls back to DATE_ADDED) instead.
        // The id settles ties — copies of one file share both dates — so two refreshes can never
        // order the same items differently.
        items.sortWith(
            compareByDescending<MediaItem> { it.dateTaken }
                .thenByDescending { it.dateAdded }
                .thenBy { it.id }
        )

        return items
    }

    /** Appends up to [PAGE_SIZE] rows with `_id` above [afterId] to [into]; returns how many. */
    private fun queryPage(afterId: Long, bucketIdFilter: String?, into: MutableList<MediaItem>): Int {
        val selection = buildString {
            append("(")
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}")
            append(" OR ")
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}")
            append(")")
            append(" AND ${MediaStore.Files.FileColumns._ID} > ?")
            if (bucketIdFilter != null) {
                append(" AND ${MediaStore.Files.FileColumns.BUCKET_ID} = ?")
            }
        }
        val selectionArgs = listOfNotNull(afterId.toString(), bucketIdFilter).toTypedArray()
        val sortOrder = "${MediaStore.Files.FileColumns._ID} ASC"
        val queryUri = MediaStore.Files.getContentUri("external")

        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            contentResolver.query(
                queryUri,
                MEDIA_PROJECTION,
                Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, PAGE_SIZE)
                },
                null
            )
        } else {
            // Android 10 has no limit argument but still accepts one on the end of the sort
            contentResolver.query(queryUri, MEDIA_PROJECTION, selection, selectionArgs, "$sortOrder LIMIT $PAGE_SIZE")
        }

        var read = 0
        cursor?.use {
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
            val relativePathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val volumeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.VOLUME_NAME)

            while (cursor.moveToNext()) {
                read++
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

                into.add(
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
                        bucketName = bucketName,
                        relativePath = cursor.getString(relativePathCol) ?: "",
                        volumeName = cursor.getString(volumeCol) ?: ""
                    )
                )
            }
        }
        return read
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

        /**
         * Rows per query. Has to stay well inside one cursor window (about 2 MB) even when every
         * path and name is as long as the filesystem allows — roughly 2 KB a row at the extreme.
         */
        const val PAGE_SIZE = 500

        /** How often every path is re-checked on disk, rather than only the new ones. */
        const val FULL_SWEEP_INTERVAL_MS = 60_000L

        val MEDIA_PROJECTION = arrayOf(
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
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.VOLUME_NAME
        )

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
