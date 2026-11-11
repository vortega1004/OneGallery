package com.onegallery.app.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.onegallery.app.data.MediaStoreRepository
import com.onegallery.app.domain.MediaItem
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Saves the frame on screen as a photo, in two steps:
 * 1. Straight away: a small copy of what is on screen, for the capture badge.
 * 2. In the background: the same frame decoded again at the video's full resolution, written
 *    to MediaStore with the video's date and location.
 *
 * The frame is named by its exact presentation time, in microseconds. It used to be named in
 * whole milliseconds, which is coarser than a frame at high rates and — when the time had been
 * worked out from an assumed frame rate — could name the frame next to the one being looked at.
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
     * - [instantFrame] is whatever is on screen already (the stepped-to still, or a copy of the
     *   player's surface). It only drives the instant feedback, and is saved only if the
     *   full-resolution decode fails.
     * - [onThumbnail] receives a small preview bitmap, never the full-size frame.
     */
    suspend fun captureCurrentFrame(
        instantFrame: Bitmap?,
        videoItem: MediaItem,
        framePositionUs: Long,
        onThumbnail: (Bitmap) -> Unit
    ): SnapshotResult? {
        val positionUs = framePositionUs
        val currentPositionMs = framePositionUs / 1000L
        val instantBitmap: Bitmap? = instantFrame

        if (instantBitmap != null) {
            val thumbnail = withContext(Dispatchers.Default) { instantBitmap.toThumbnail() }
            withContext(Dispatchers.Main) { onThumbnail(thumbnail) }
        }

        // High-res path: native uncompressed resolution frame
        val highResBitmap = withContext(Dispatchers.IO) {
            extractHighResFrame(videoItem.uri, positionUs)
                // A file the phone's own decoders refuse: FFmpeg can still read the frame
                ?: extractWithFfmpeg(videoItem.uri, currentPositionMs)
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

    private fun extractWithFfmpeg(uri: Uri, positionMs: Long): Bitmap? = runCatching {
        val info = MediaInfoBuilder().from(context, uri).build() ?: return null
        try {
            if (info.supportsFrameLoading) info.getFrameAt(positionMs) else null
        } finally {
            info.release()
        }
    }.getOrNull()

    private companion object {
        const val THUMBNAIL_MAX_PX = 256
    }
}
