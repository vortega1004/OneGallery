package com.onegallery.app.data

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext

/** Something sitting in the trash, waiting to be restored or to expire. */
data class TrashedItem(
    val id: Long,
    val uri: Uri,
    /** The name the file had before it was trashed. */
    val displayName: String,
    val isVideo: Boolean,
    /** When Android will delete it for good, in milliseconds since the epoch; 0 if unknown. */
    val expiresAtMs: Long,
    val size: Long,
    val durationMs: Long,
    /** The album it came from, and goes back to on restore. */
    val albumName: String
)

/**
 * The trash: Android's own, not a folder of this app's.
 *
 * MediaStore has had a trash since Android 11. Trashing an item hides it from every gallery,
 * keeps the file where it is under a `.trashed-…` name, and deletes it by itself thirty days
 * later; restoring puts it back exactly where and as it was. Using it rather than moving files
 * into a private folder means nothing can be lost by uninstalling this app, the item keeps its
 * id and dates, and the same trash shows up in Files and in Google Photos.
 *
 * **Consent.** With *All files access* the app flips the flag itself. Without it Android asks
 * the user once per batch: launch [trashRequest] or [deleteRequest] and the system does the work
 * when they agree — there is nothing further to call.
 */
class MediaTrash(context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    /** Android 10 has no trash; everything here needs Android 11. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** One system dialog that trashes ([trash] true) or restores every one of [uris]. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun trashRequest(uris: List<Uri>, trash: Boolean): PendingIntent =
        MediaStore.createTrashRequest(resolver, uris, trash)

    /** One system dialog that deletes every one of [uris] for good. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun deleteRequest(uris: List<Uri>): PendingIntent =
        MediaStore.createDeleteRequest(resolver, uris)

    /**
     * Trashes or restores [uris] directly; returns how many changed. Only for an app with All
     * files access ([canMoveAnywhere]) — anyone else is refused, and must use [trashRequest].
     */
    suspend fun setTrashed(uris: List<Uri>, trashed: Boolean): Int = withContext(Dispatchers.IO) {
        if (!supported) return@withContext 0
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0)
        }
        uris.count { uri ->
            runCatching { resolver.update(uri, values, includeTrashed()) == 1 }.getOrDefault(false)
        }
    }

    /** Deletes [uris] for good; returns how many went. All files access only, like [setTrashed]. */
    suspend fun deleteForever(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        if (!supported) return@withContext 0
        uris.count { uri ->
            runCatching { resolver.delete(uri, includeTrashed()) == 1 }.getOrDefault(false)
        }
    }

    /** The trash, soonest to expire first, re-read whenever MediaStore changes. */
    fun observeTrashed(): Flow<List<TrashedItem>> = callbackFlow<Unit> {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
        .conflate()
        .transform {
            emit(queryTrashed())
            delay(CHANGE_BURST_WINDOW_MS)
        }
        .flowOn(Dispatchers.IO)

    private fun queryTrashed(): List<TrashedItem> {
        if (!supported) return emptyList()
        val items = ArrayList<TrashedItem>()
        val query = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}" +
                    " OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            )
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_EXPIRES} ASC, ${MediaStore.MediaColumns._ID} ASC"
            )
        }
        runCatching {
            resolver.query(MediaStore.Files.getContentUri("external"), PROJECTION, query, null)
        }.getOrNull()?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val expiresCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val isVideo = cursor.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                items += TrashedItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = originalName(cursor.getString(nameCol) ?: "Media_$id"),
                    isVideo = isVideo,
                    expiresAtMs = cursor.getLong(expiresCol) * 1000,
                    size = cursor.getLong(sizeCol),
                    durationMs = cursor.getLong(durationCol),
                    albumName = cursor.getString(bucketCol) ?: ""
                )
            }
        }
        return items
    }

    /** A single-item uri does not match a trashed row unless the caller says trash is included. */
    private fun includeTrashed(): Bundle = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }

    private companion object {
        const val CHANGE_BURST_WINDOW_MS = 300L

        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        )
    }
}

/**
 * The name a file had before it was trashed. Android renames a trashed file to
 * `.trashed-<expiry in seconds>-<original name>` and reports that as its display name.
 */
internal fun originalName(trashedName: String): String =
    TRASHED_PREFIX.replaceFirst(trashedName, "").ifEmpty { trashedName }

private val TRASHED_PREFIX = Regex("""^\.trashed-\d+-""")

/**
 * "29 days", "1 day", "today" — how long until Android deletes a trashed item, as of [nowMs].
 * Rounded up: an item with an hour left still has "1 day", because "0 days" reads as gone.
 */
internal fun expiryLabel(expiresAtMs: Long, nowMs: Long): String {
    if (expiresAtMs <= 0L) return ""
    val remaining = expiresAtMs - nowMs
    if (remaining <= 0L) return "today"
    val days = (remaining + DAY_MS - 1) / DAY_MS
    return if (days == 1L) "1 day" else "$days days"
}

private const val DAY_MS = 24L * 60 * 60 * 1000
