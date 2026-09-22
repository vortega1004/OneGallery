package com.onegallery.app.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Random access to a video's frames by index, decoded in runs and kept in memory.
 *
 * **Why not just seek the player.** An exact `ExoPlayer.seekTo` restarts decoding at the sync
 * sample before the target and replays every frame in between; on a two-second GOP a
 * single-frame step can mean decoding sixty frames. Scrubbing mode does not change that — its
 * DEFAULT already sets every flag it has (disabled audio/metadata tracks, raised codec
 * operating rate, skipped `MediaCodec` flush, dynamic scheduling, decode-only) and the
 * remaining cost is the re-decode itself. Stepping one frame at a time is the pathological
 * case for that path: it pays the whole GOP again for each frame.
 *
 * [MediaMetadataRetriever.getFramesAtIndex] decodes *consecutive* frames in one pass, so the
 * keyframe is paid once for a whole run rather than once per frame. Walking forward then costs
 * one decode per frame amortised, and — the part the player cannot offer at all — a frame
 * already decoded can be shown again instantly, so reversing direction is free.
 *
 * Frames are scaled down to the display area and stored as RGB_565 on the way in. A 4K frame is
 * 33 MB at full size and ARGB_8888; a run of them would be hundreds of megabytes for pixels no
 * screen can show.
 *
 * Not thread-safe by itself — every decode is serialised on [mutex] because one
 * `MediaMetadataRetriever` cannot service concurrent calls.
 */
class VideoFrameCache private constructor(
    private val retriever: MediaMetadataRetriever,
    /** Exact, from the container's metadata rather than duration × rate. */
    val frameCount: Int,
    /** Implied by the container: frames over duration. Constant-rate footage only, like the rest. */
    val frameRate: Float,
    private val maxWidth: Int,
    private val maxHeight: Int,
    /**
     * Frames per decode call, sized to the source.
     *
     * [MediaMetadataRetriever.getFramesAtIndex] hands back the whole run as a list, so every
     * frame in it exists at full resolution at the same moment. At 4K that is 33 MB apiece and
     * a run of eight would be a quarter of a gigabyte — an out-of-memory kill on exactly the
     * footage a phone actually shoots. Big frames therefore get short runs.
     */
    private val runLength: Int
) : AutoCloseable {

    private val mutex = Mutex()
    private var closed = false

    /**
     * Most recently used frames, by index.
     *
     * Evicted bitmaps are dropped, never recycled: one of them is probably on screen, held by
     * the composable that asked for it. Letting the collector take them is correct and costs
     * nothing here.
     */
    private val frames = object : LinkedHashMap<Int, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>) =
            size > maxCachedFrames
    }
    private var maxCachedFrames = MIN_CACHED_FRAMES

    val lastIndex: Int get() = frameCount - 1

    /** Already decoded, so it can be shown this frame without suspending. */
    fun cached(index: Int): Bitmap? = synchronized(frames) { frames[index] }

    /**
     * The frame at [index], decoding a run around it if it is not already in memory.
     *
     * [direction] says which way the user is walking, so the run is laid down ahead of them
     * rather than behind: stepping back decodes a run *ending* at [index].
     */
    suspend fun frameAt(index: Int, direction: Int): Bitmap? {
        cached(index)?.let { return it }
        return decodeRun(index, direction)?.let { cached(index) }
    }

    /**
     * Fills the next hole ahead of [index], if one is close enough to be walked into soon.
     *
     * Looking only at the very next frame would start the decode at the moment the user reaches
     * the end of what is cached — which is precisely the stall it exists to prevent. Looking
     * [PREFETCH_MARGIN] frames ahead starts it while they still have frames in hand.
     *
     * The run begins at the first *uncached* index rather than at the lookahead point, so the
     * cached span stays contiguous and no frame is skipped over.
     */
    suspend fun prefetch(index: Int, direction: Int) {
        val step = if (direction >= 0) 1 else -1
        var here = index
        repeat(PREFETCH_MARGIN) {
            val next = here + step
            if (next < 0 || next > lastIndex) return
            if (cached(next) == null) {
                decodeRun(next, direction)
                return
            }
            here = next
        }
    }

    private suspend fun decodeRun(index: Int, direction: Int): Unit? = withContext(Dispatchers.IO) {
        if (index < 0 || index > lastIndex) return@withContext null
        mutex.withLock {
            if (closed) return@withLock null
            // Someone else may have decoded it while this call waited for the lock
            if (cached(index) != null) return@withLock Unit
            coroutineContext.ensureActive()

            val start = if (direction >= 0) index else max(0, index - runLength + 1)
            val count = min(runLength, frameCount - start)
            // RGB_565 is a request, not a promise — a decoder may hand back ARGB_8888 anyway —
            // but where it is honoured it halves what the run costs while it is in flight.
            val params = MediaMetadataRetriever.BitmapParams().apply {
                preferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = runCatching { retriever.getFramesAtIndex(start, count, params) }
                .getOrNull() ?: return@withLock null

            decoded.forEachIndexed { offset, bitmap ->
                if (bitmap == null) return@forEachIndexed
                val fitted = fit(bitmap)
                if (fitted !== bitmap) bitmap.recycle()
                synchronized(frames) {
                    if (maxCachedFrames == MIN_CACHED_FRAMES) maxCachedFrames = budgetFor(fitted)
                    frames[start + offset] = fitted
                }
            }
            if (cached(index) != null) Unit else null
        }
    }

    /** Scaled to the display area and flattened to RGB_565; the caller recycles the original. */
    private fun fit(source: Bitmap): Bitmap {
        val scale = min(maxWidth.toFloat() / source.width, maxHeight.toFloat() / source.height)
            .coerceAtMost(1f)
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(source, null, Rect(0, 0, width, height), FILTER)
        return out
    }

    /** How many of these fit in [CACHE_BUDGET_BYTES], within reason. */
    private fun budgetFor(frame: Bitmap): Int =
        (CACHE_BUDGET_BYTES / max(1, frame.byteCount)).coerceIn(MIN_CACHED_FRAMES, MAX_CACHED_FRAMES)

    /** Where the player has to be told to go to show [index]. */
    fun positionMsFor(index: Int): Long = framePositionMs(index.coerceIn(0, lastIndex), frameRate)

    /** Which frame [positionMs] is showing. */
    fun indexFor(positionMs: Long): Int = frameIndexAt(positionMs, frameRate, lastIndex)

    /** The frame whose timestamp is closest to [positionMs], for snapping an arbitrary seek. */
    fun nearestIndexFor(positionMs: Long): Int =
        nearestFrameIndexAt(positionMs, frameRate, lastIndex)

    override fun close() {
        synchronized(frames) { frames.clear() }
        closed = true
        runCatching { retriever.release() }
    }

    companion object {
        /**
         * Opens [uri], or returns null when its frames cannot be addressed by index.
         *
         * The frame-index calls are not supported for every container and codec, and they
         * report it by throwing or by handing back nothing, so this decodes frame zero as a
         * proof of life rather than assuming. A null here means the caller keeps using the
         * player's own seeking.
         */
        suspend fun open(
            context: Context,
            uri: Uri,
            maxWidth: Int,
            maxHeight: Int
        ): VideoFrameCache? = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val count = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toIntOrNull() ?: 0
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val sourceWidth = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val sourceHeight = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                if (count < 2 || durationMs <= 0L) {
                    retriever.release()
                    return@withContext null
                }
                val probe = retriever.getFrameAtIndex(0)
                if (probe == null) {
                    retriever.release()
                    return@withContext null
                }
                probe.recycle()
                val sourceBytes = max(1, sourceWidth * sourceHeight * 4)
                VideoFrameCache(
                    retriever = retriever,
                    frameCount = count,
                    frameRate = count * 1000f / durationMs,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    runLength = (RUN_BUDGET_BYTES / sourceBytes).coerceIn(MIN_RUN, MAX_RUN)
                )
            } catch (t: Throwable) {
                runCatching { retriever.release() }
                null
            }
        }
    }
}

/**
 * Timestamp of frame [index], rounded **down** to whole milliseconds.
 *
 * Matches [frameStepPositionMs]: an exact seek shows the first frame at or after the position
 * asked for, so rounding up would overshoot onto the next frame.
 */
internal fun framePositionMs(index: Int, frameRate: Float): Long =
    floor(index * 1000.0 / frameRate).toLong()

/**
 * Which frame covers [positionMs].
 *
 * Rounds down, with the +1 that undoes [framePositionMs]'s rounding, so the two round-trip:
 * `frameIndexAt(framePositionMs(n)) == n`.
 */
internal fun frameIndexAt(positionMs: Long, frameRate: Float, lastIndex: Int): Int =
    floor((positionMs + 1) * frameRate / 1000.0).toInt().coerceIn(0, lastIndex)

/**
 * The frame whose timestamp is *closest* to [positionMs], for snapping an arbitrary seek.
 *
 * [frameIndexAt] answers "which frame covers this instant" and deliberately rounds down. That
 * is the wrong question for a position the user dragged to: an exact seek leaves the player
 * showing the frame at or **after** the request, so reading that position back with
 * [frameIndexAt] names the frame before the one on screen — and a walk started there spends its
 * first tap arriving at the frame the user was already looking at. Snapping the seek itself to
 * this index removes the ambiguity rather than trying to track how the position was arrived at.
 */
internal fun nearestFrameIndexAt(positionMs: Long, frameRate: Float, lastIndex: Int): Int =
    (positionMs * frameRate / 1000.0).roundToInt().coerceIn(0, lastIndex)

private val FILTER = Paint(Paint.FILTER_BITMAP_FLAG)

/**
 * How much a single decode call may hold in full-resolution frames at once.
 *
 * The whole run has to finish before any of it can be shown, so the run length also trades the
 * first step's latency against how many steps come free afterwards. At 1080p this works out at
 * the [MAX_RUN] ceiling — roughly a quarter-second of footage, enough to amortise the keyframe
 * — and at 4K it falls to a pair, which is the most that fits without risking the process.
 */
private const val RUN_BUDGET_BYTES = 64 * 1024 * 1024
private const val MIN_RUN = 2
private const val MAX_RUN = 8

/** How far ahead of the user a hole in the cache is worth filling before they reach it. */
private const val PREFETCH_MARGIN = 4

private const val CACHE_BUDGET_BYTES = 40 * 1024 * 1024
private const val MIN_CACHED_FRAMES = 8
private const val MAX_CACHED_FRAMES = 48
