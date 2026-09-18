package com.onegallery.app.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.TextureView
import com.onegallery.app.data.MediaStoreRepository
import com.onegallery.app.domain.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Replicates Samsung Gallery's CaptureDelegate dual-engine snapshot mechanism:
 * 1. Immediate UI path: Grab rendered frame from TextureView (0ms lag, exact display state)
 * 2. High-res hardware path: MediaMetadataRetriever (full resolution native video frame)
 * 3. Background asynchronous MediaStore writer preserving EXIF & video origin metadata.
 */
class VideoSnapshotManager(
    private val context: Context,
    private val repository: MediaStoreRepository
) {

    data class SnapshotResult(
        val bitmap: Bitmap,
        val savedUri: Uri?,
        val timestampMs: Long
    )

    /**
     * Executes the instant snapshot pipeline:
     * - The TextureView frame (when available) only drives the instant UI feedback
     * - The file that gets saved is always the native-resolution frame; the screen-sized
     *   TextureView bitmap is used for saving only if the high-res extraction fails
     * - [onThumbnail] receives a small preview bitmap, never the full-size frame
     */
    suspend fun captureCurrentFrame(
        textureView: TextureView?,
        videoItem: MediaItem,
        currentPositionMs: Long,
        onThumbnail: (Bitmap) -> Unit
    ): SnapshotResult? {
        val positionUs = currentPositionMs * 1000L

        // Fast path: Immediate TextureView screen bitmap
        val instantBitmap: Bitmap? = textureView?.bitmap

        if (instantBitmap != null) {
            val thumbnail = withContext(Dispatchers.Default) { instantBitmap.toThumbnail() }
            withContext(Dispatchers.Main) { onThumbnail(thumbnail) }
        }

        // High-res path: native uncompressed resolution frame
        val highResBitmap = withContext(Dispatchers.IO) {
            extractHighResFrame(videoItem.uri, positionUs)
        }

        val finalBitmap = highResBitmap ?: instantBitmap ?: return null

        // No instant frame was available, so the UI is still waiting for its thumbnail
        if (instantBitmap == null) {
            val thumbnail = withContext(Dispatchers.Default) { finalBitmap.toThumbnail() }
            withContext(Dispatchers.Main) { onThumbnail(thumbnail) }
        }

        // Commit to MediaStore in background
        val savedUri = repository.saveVideoStillSnapshot(
            bitmap = finalBitmap,
            sourceVideo = videoItem,
            playbackPositionMs = currentPositionMs
        )

        return SnapshotResult(
            bitmap = finalBitmap,
            savedUri = savedUri,
            timestampMs = currentPositionMs
        )
    }

    private fun Bitmap.toThumbnail(): Bitmap {
        val longestSide = max(width, height)
        if (longestSide <= THUMBNAIL_MAX_PX) return this
        val ratio = THUMBNAIL_MAX_PX.toFloat() / longestSide
        return Bitmap.createScaledBitmap(
            this,
            (width * ratio).roundToInt().coerceAtLeast(1),
            (height * ratio).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun extractHighResFrame(uri: Uri, positionUs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            // Extract closest sync/exact frame
            retriever.getFrameAtTime(
                positionUs,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
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
        const val THUMBNAIL_MAX_PX = 256
    }
}
