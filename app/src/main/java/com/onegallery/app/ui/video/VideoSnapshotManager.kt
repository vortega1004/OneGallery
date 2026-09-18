package com.onegallery.app.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.TextureView
import com.onegallery.app.data.MediaStoreRepository
import com.onegallery.app.domain.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
     * - Grabs the fastest available valid bitmap from TextureView or MediaMetadataRetriever
     * - Passes bitmap immediately to UI callback (for shutter flash & thumbnail drop badge)
     * - Commits to MediaStore asynchronously without blocking video playback
     */
    suspend fun captureCurrentFrame(
        textureView: TextureView?,
        videoItem: MediaItem,
        currentPositionMs: Long,
        onInstantBitmap: (Bitmap) -> Unit
    ): SnapshotResult? = coroutineScope {
        val positionUs = currentPositionMs * 1000L

        // Fast path: Immediate TextureView screen bitmap
        val instantBitmap: Bitmap? = textureView?.bitmap

        if (instantBitmap != null) {
            onInstantBitmap(instantBitmap)
        }

        // Parallel task: Try to extract native uncompressed resolution frame
        val highResDeferred = async(Dispatchers.IO) {
            extractHighResFrame(videoItem.uri, positionUs)
        }

        // Wait with a short 250ms threshold; if high-res completes, use it; otherwise fallback to instant
        val highResBitmap = withTimeoutOrNull(300L) {
            highResDeferred.await()
        }

        val finalBitmap = highResBitmap ?: instantBitmap ?: highResDeferred.await()

        if (finalBitmap == null) return@coroutineScope null

        // If high-res wasn't delivered to UI yet and was selected, notify UI
        if (instantBitmap == null) {
            withContext(Dispatchers.Main) {
                onInstantBitmap(finalBitmap)
            }
        }

        // Commit to MediaStore in background
        val savedUri = repository.saveVideoStillSnapshot(
            bitmap = finalBitmap,
            sourceVideo = videoItem,
            playbackPositionMs = currentPositionMs
        )

        SnapshotResult(
            bitmap = finalBitmap,
            savedUri = savedUri,
            timestampMs = currentPositionMs
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
}
