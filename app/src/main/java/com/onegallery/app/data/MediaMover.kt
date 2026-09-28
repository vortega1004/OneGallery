package com.onegallery.app.data

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.domain.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A folder media can be moved into: MediaStore's `RELATIVE_PATH` on one volume. */
data class MoveDestination(
    /** "DCIM/Camera/" — always with a trailing slash, never a leading one. */
    val relativePath: String,
    val volumeName: String,
    /** For messages: the album's display name. */
    val label: String
)

data class MoveResult(
    val moved: Int,
    /** Moved, but under a new name because the destination already had one. Counted in [moved]. */
    val renamed: Int,
    /** Already in the destination; nothing to do. */
    val alreadyThere: Int,
    /** Refused by MediaStore or by the rules below — see [MediaMover.move]. */
    val failed: Int
)

/**
 * Moves media between folders by rewriting MediaStore's `RELATIVE_PATH`.
 *
 * This is a real move, not copy-and-delete: MediaStore renames the file on disk to match the new
 * path and keeps the same row, so the item keeps its id, its dates and anything else that points
 * at it. Other apps see it in the new folder the moment the update returns.
 *
 * **Consent.** Under scoped storage an app may only modify files it created. For everything else
 * the caller must first launch [writeRequest] — one system dialog for the whole batch — and only
 * call [move] once the user has allowed it. An app granted *media management* (Settings →
 * Special app access) gets the same request approved without the dialog; the manifest declares
 * `MANAGE_MEDIA` so the option exists.
 */
class MediaMover(context: Context) {

    private val resolver = context.contentResolver

    /** One system consent dialog covering every item in [items]. */
    fun writeRequest(items: List<MediaItem>): PendingIntent =
        MediaStore.createWriteRequest(resolver, items.map { it.uri })

    /**
     * Moves [items] into [destination]. Call only after [writeRequest] has been approved.
     *
     * Items are handled one at a time and a failure never stops the batch: a move is not
     * transactional in MediaStore, and half-reporting a partial batch would be worse than
     * finishing the rest and counting what did not go.
     */
    suspend fun move(items: List<MediaItem>, destination: MoveDestination): MoveResult =
        withContext(Dispatchers.IO) {
            val target = normalizeRelativePath(destination.relativePath)
            // Names already in the destination, so a clash becomes "name (1).jpg" rather than a
            // refused update. Case-insensitive: shared storage is casefolded on current Android,
            // so "IMG.JPG" and "img.jpg" are the same file there.
            val taken = namesIn(target, destination.volumeName).toMutableSet()

            var moved = 0
            var renamed = 0
            var alreadyThere = 0
            var failed = 0
            for (item in items) {
                if (normalizeRelativePath(item.relativePath).equals(target, ignoreCase = true) &&
                    item.volumeName == destination.volumeName
                ) {
                    alreadyThere++
                    continue
                }
                if (item.volumeName != destination.volumeName ||
                    !destinationAllows(item.mediaType, target)
                ) {
                    failed++
                    continue
                }
                val name = uniqueName(item.displayName, taken)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, target)
                    if (name != item.displayName) put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                }
                val ok = runCatching { resolver.update(item.uri, values, null, null) == 1 }
                    .getOrDefault(false)
                if (ok) {
                    moved++
                    if (name != item.displayName) renamed++
                    taken += name.lowercase()
                } else {
                    failed++
                }
            }
            MoveResult(moved, renamed, alreadyThere, failed)
        }

    private fun namesIn(relativePath: String, volumeName: String): Set<String> {
        val names = mutableSetOf<String>()
        runCatching {
            resolver.query(
                MediaStore.Files.getContentUri(volumeName),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let { names += it.lowercase() }
            }
        }
        return names
    }
}

/**
 * MediaStore's own rule for which top-level folders a type may live under.
 *
 * MediaProvider refuses a `RELATIVE_PATH` outside these — a photo cannot be moved into Movies/,
 * a video cannot go into Music/ — and says so only by throwing. Checking first lets the album
 * picker grey those folders out instead of accepting a move that is certain to fail.
 */
internal fun destinationAllows(mediaType: MediaType, relativePath: String): Boolean {
    val primary = normalizeRelativePath(relativePath).substringBefore('/')
    val allowed = if (mediaType == MediaType.VIDEO) VIDEO_PRIMARY_DIRS else IMAGE_PRIMARY_DIRS
    return allowed.any { it.equals(primary, ignoreCase = true) }
}

private val IMAGE_PRIMARY_DIRS = listOf("DCIM", "Pictures")
private val VIDEO_PRIMARY_DIRS = listOf("DCIM", "Movies", "Pictures")

/** "DCIM/Camera" and "/DCIM/Camera/" both become "DCIM/Camera/", MediaStore's own form. */
internal fun normalizeRelativePath(path: String): String {
    val trimmed = path.trim().trim('/')
    return if (trimmed.isEmpty()) "" else "$trimmed/"
}

/**
 * [name], or "name (1).ext", "name (2).ext"… — the first that is not in [taken].
 *
 * [taken] holds lower-cased names. Only the last dot starts the extension, so "clip.final.mp4"
 * becomes "clip.final (1).mp4"; a name with no extension simply gets the suffix.
 */
internal fun uniqueName(name: String, taken: Set<String>): String {
    if (name.lowercase() !in taken) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val base = name.substring(0, dot)
    val extension = name.substring(dot)
    var n = 1
    while (true) {
        val candidate = "$base ($n)$extension"
        if (candidate.lowercase() !in taken) return candidate
        n++
    }
}

/**
 * Where a new album the user names goes: Pictures/<name>/, which both photos and videos are
 * allowed under. Null if nothing usable is left once path separators and the characters shared
 * storage rejects are stripped.
 */
internal fun newAlbumPath(name: String): String? {
    val cleaned = name.trim()
        .replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "")
        .trim('.', ' ')
    return if (cleaned.isEmpty()) null else "Pictures/$cleaned/"
}
