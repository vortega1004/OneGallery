package com.onegallery.app.ui.video

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A video's frames by index, from a decoder this class drives itself, kept in memory around the
 * frame being looked at.
 *
 * **Why its own decoder.** The first version asked `MediaMetadataRetriever.getFramesAtIndex` for
 * runs of eight frames. Every call starts again from the keyframe before the run, which is fine
 * on camera footage (a keyframe a second) and ruinous on a clip with one keyframe for its whole
 * length — which is what ffmpeg writes by default, and so what ComfyUI produces. Measured on a
 * 241-frame clip: each run near the end took three seconds, because each one re-decoded the
 * entire clip to get there. A decoder that is simply left where it stopped pays for each frame
 * once: stepping forward costs one decode a frame however far the keyframe is, and a step back
 * past what is in memory costs one pass that refills a whole window, not one pass per run.
 *
 * **How frames get out.** The decoder renders to a `SurfaceTexture`; each wanted frame is drawn
 * through GL into an off-screen buffer at the size the screen will show it and read back into a
 * bitmap. Scaling on the way in is what makes a window of 4K frames affordable, and going
 * through a surface is the one output path every hardware decoder supports.
 *
 * **Which frame is which.** Decoded frames are matched to an index by their presentation time,
 * through [timeline] — never counted, because a decoder returns frames in display order while
 * the file holds them in decode order.
 *
 * Clips short enough to fit the memory budget whole are decoded whole in the background (see
 * [prefetch]); after that every step, in either direction, anywhere in the clip, is a lookup.
 *
 * All decoding runs on one thread that owns the codec and the GL context.
 */
class FrameServer private constructor(
    private val extractor: MediaExtractor,
    private val format: MediaFormat,
    val timeline: VideoTimeline,
    private val outWidth: Int,
    private val outHeight: Int,
    /** How many frames may be held at once. */
    private val capacity: Int
) : AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "frame-server") }
    private val dispatcher = executor.asCoroutineDispatcher()

    private val frames = HashMap<Int, Bitmap>()

    /** The frame the user is on: what eviction protects and what a pass has to stay near. */
    @Volatile private var focus = 0
    @Volatile private var closed = false

    /** The indices the pass in flight will deliver, or null between passes. */
    @Volatile private var passRange: IntRange? = null

    /** Bumped whenever a frame arrives or a pass ends, so a waiter can look again. */
    private val progress = MutableStateFlow(0)

    // --- Decode thread only -----------------------------------------------------------------
    private var codec: MediaCodec? = null
    private var reader: FrameReader? = null
    /** Index of the frame the codec last returned; the next one out is the one after it. */
    private var lastOutputIndex = -1
    private var inputEnded = false
    /** Nothing more will come out without a seek: fresh codec, or it has reached the end. */
    private var needsRestart = true
    /** Whether anything has been queued since the codec was started or last flushed. */
    private var codecHasInput = false
    /** Whether the codec has answered at all since it was started — see [restartAt]. */
    private var codecHasOutput = false
    private var failed = false

    val lastIndex: Int get() = timeline.lastIndex
    val frameCount: Int get() = timeline.frameCount

    /** Whether the whole clip fits in memory, so it is worth decoding all of it once. */
    private val holdsWholeClip: Boolean get() = timeline.frameCount <= capacity

    /** Already decoded, so it can be shown this frame without suspending. */
    fun cached(index: Int): Bitmap? = synchronized(frames) { frames[index] }

    /**
     * The frame at [index], decoding to it if it is not in memory. Null means this file cannot
     * be decoded here after all and the caller should stop asking.
     *
     * [direction] says which way the user is walking, so what is kept alongside it lies ahead
     * of them: a step back keeps a window of the frames *before* [index], because reaching it
     * meant decoding all of those anyway and the next steps back will want them.
     */
    suspend fun frameAt(index: Int, direction: Int): Bitmap? {
        val target = index.coerceIn(0, lastIndex)
        focus = target
        cached(target)?.let { return it }

        // A pass already on its way to this frame will deliver it sooner than queueing behind it
        if (passRange?.contains(target) == true) {
            progress.first { cached(target) != null || passRange?.contains(target) != true }
            cached(target)?.let { return it }
        }

        val range = if (direction >= 0) {
            target..min(lastIndex, target + FORWARD_RUN)
        } else {
            max(0, target - backRun)..target
        }
        return withContext(dispatcher) {
            if (cached(target) == null) {
                decodePass(range, keepBehind = BACK_KEEP_ON_FORWARD, stayNearFocus = true)
            }
            cached(target)
        }
    }

    /**
     * Gets ahead of the user: fills the whole clip when it fits, otherwise the next hole in the
     * direction they are walking once they are close to it.
     */
    suspend fun prefetch(index: Int, direction: Int) {
        if (holdsWholeClip) {
            val firstMissing = (0..lastIndex).firstOrNull { cached(it) == null } ?: return
            withContext(dispatcher) {
                coroutineContext.ensureActive()
                decodePass(firstMissing..lastIndex, keepBehind = 0, stayNearFocus = false)
            }
            return
        }
        val step = if (direction >= 0) 1 else -1
        var here = index
        repeat(PREFETCH_MARGIN) {
            val next = here + step
            if (next < 0 || next > lastIndex) return
            if (cached(next) == null) {
                val range = if (step > 0) next..min(lastIndex, next + FORWARD_RUN)
                else max(0, next - backRun)..next
                withContext(dispatcher) {
                    coroutineContext.ensureActive()
                    if (cached(next) == null) decodePass(range, keepBehind = 0, stayNearFocus = true)
                }
                return
            }
            here = next
        }
    }

    /** How many frames a step back refills at once: most of the budget, behind the user. */
    private val backRun: Int get() = (capacity * 2 / 3).coerceIn(MIN_BACK_RUN, MAX_BACK_RUN)

    /**
     * Decodes forward until every frame in [range] is in memory.
     *
     * Carries on from where the decoder already is when that is behind [range] with no keyframe
     * in between; otherwise restarts at the keyframe before it. A restart decodes everything
     * from that keyframe up to [range] anyway, so up to [keepBehind] of the frames just before
     * [range] are kept from it — a step back straight after is then free. With [stayNearFocus]
     * the pass gives up once the user has moved well outside it — someone who dragged the seek
     * bar elsewhere should not wait for frames they have left.
     */
    private fun decodePass(wanted: IntRange, keepBehind: Int, stayNearFocus: Boolean) {
        if (failed || closed) return
        var range = wanted
        passRange = range
        try {
            var codec = ensureCodec()
            val reader = reader ?: error("No frame reader")
            val keyframe = timeline.keyframeAtOrBefore(range.first)
            val canContinue = !needsRestart && lastOutputIndex < range.first &&
                keyframe <= lastOutputIndex + 1
            if (!canContinue) {
                codec = restartAt(codec, keyframe)
                range = max(keyframe, range.first - keepBehind)..range.last
                passRange = range
            }

            val info = MediaCodec.BufferInfo()
            var idleLoops = 0
            while (!closed) {
                if (stayNearFocus) {
                    val at = focus
                    if (at < range.first - ABANDON_SLACK || at > range.last + ABANDON_SLACK) break
                }
                val fed = feed(codec)
                val out = codec.dequeueOutputBuffer(info, if (fed) 0L else OUTPUT_WAIT_US)
                if (out < 0) {
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) codecHasOutput = true
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER && !fed && ++idleLoops > MAX_IDLE_LOOPS) {
                        // A decoder that takes input and never answers: treat the file as one
                        // this path cannot handle rather than hanging the walk forever
                        error("Decoder stalled")
                    }
                    continue
                }
                idleLoops = 0
                codecHasOutput = true
                val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                var index = -1
                if (info.size > 0) {
                    val nearest = timeline.nearestIndexUs(info.presentationTimeUs)
                    // Frames from before the track's start carry times that match nothing
                    if (abs(timeline.timeUs(nearest) - info.presentationTimeUs) <= TIME_MATCH_US) {
                        index = nearest
                    }
                }
                val keep = index >= 0 && index in range && cached(index) == null
                codec.releaseOutputBuffer(out, keep)
                if (keep) {
                    reader.read()?.let { store(index, it) }
                }
                if (index >= 0) lastOutputIndex = index
                if (ended) {
                    needsRestart = true
                    break
                }
                if (index >= range.last) break
            }
        } catch (t: Throwable) {
            // Not fatal — the caller falls back to seeking the player — but worth a line in
            // the log: it is the only trace of *why* this file could not be walked by frame
            Log.w(TAG, "Frame decode failed at ${range.first}..${range.last}", t)
            failed = true
            releaseDecoder()
        } finally {
            passRange = null
            progress.value++
        }
    }

    /** Queues as much input as the codec will take right now; false if it took none. */
    private fun feed(codec: MediaCodec): Boolean {
        var fedAny = false
        repeat(MAX_INPUTS_PER_LOOP) {
            if (inputEnded) return fedAny
            val slot = codec.dequeueInputBuffer(0L)
            if (slot < 0) return fedAny
            val buffer = codec.getInputBuffer(slot) ?: return fedAny
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                codec.queueInputBuffer(slot, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEnded = true
            } else {
                codec.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0)
                extractor.advance()
            }
            codecHasInput = true
            fedAny = true
        }
        return fedAny
    }

    /**
     * Points the decoder at [keyframe] and returns the codec to carry on with.
     *
     * A codec is given its setup data (the stream's parameter sets) when it starts, and a flush
     * before it has produced anything throws that data away with everything else — the codec
     * then swallows input for ever and returns nothing. So a codec that has not answered yet is
     * not flushed: untouched, it needs nothing; fed but silent, it is replaced.
     */
    private fun restartAt(current: MediaCodec, keyframe: Int): MediaCodec {
        extractor.seekTo(timeline.timeUs(keyframe), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val codec = when {
            !codecHasInput -> current
            codecHasOutput -> current.also { it.flush() }
            else -> {
                runCatching { current.stop() }
                runCatching { current.release() }
                this.codec = null
                ensureCodec()
            }
        }
        codecHasInput = false
        inputEnded = false
        needsRestart = false
        lastOutputIndex = keyframe - 1
        return codec
    }

    private fun ensureCodec(): MediaCodec {
        codec?.let { return it }
        val newReader = reader ?: FrameReader(outWidth, outHeight)
        reader = newReader
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("No mime type")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // HDR footage, shown on the same surface as everything else: ask for it tone-mapped
            format.setInteger(
                MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            )
        }
        val created = MediaCodec.createDecoderByType(mime)
        try {
            created.configure(format, newReader.surface, null, 0)
            created.start()
        } catch (t: Throwable) {
            runCatching { created.release() }
            throw t
        }
        codec = created
        codecHasInput = false
        codecHasOutput = false
        return created
    }

    private fun store(index: Int, bitmap: Bitmap) {
        synchronized(frames) {
            frames[index] = bitmap
            // Evict whatever is farthest from the user. Dropped, never recycled: a bitmap
            // handed out may still be on screen.
            while (frames.size > capacity) {
                val at = focus
                val farthest = frames.keys.maxByOrNull { abs(it - at) } ?: break
                if (farthest == index && frames.size == 1) break
                frames.remove(farthest)
            }
        }
        progress.value++
    }

    private fun releaseDecoder() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { reader?.release() }
        reader = null
    }

    override fun close() {
        if (closed) return
        closed = true
        // On the decode thread, behind any pass in flight (which stops at its next frame): the
        // GL context belongs to that thread and cannot be released from another
        executor.execute {
            releaseDecoder()
            runCatching { extractor.release() }
            synchronized(frames) { frames.clear() }
        }
        executor.shutdown()
        progress.value++
    }

    companion object {
        /**
         * Reads when every frame of [uri] is shown. Null when the file has no video track that
         * can be read this way, or is too long to be worth indexing.
         *
         * Walks the whole track, so it costs time in proportion to the file — nothing for a
         * five-second clip, a second or two for a long recording — and runs off the main thread.
         */
        suspend fun readTimeline(context: Context, uri: Uri): VideoTimeline? =
            withContext(Dispatchers.IO) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, uri, null)
                    val track = videoTrackOf(extractor) ?: return@withContext null
                    extractor.selectTrack(track)
                    var times = LongArray(1024)
                    var keys = BooleanArray(1024)
                    var count = 0
                    while (extractor.sampleTrackIndex >= 0) {
                        if (count == times.size) {
                            if (count >= MAX_INDEXED_FRAMES) return@withContext null
                            times = times.copyOf(count * 2)
                            keys = keys.copyOf(count * 2)
                        }
                        times[count] = extractor.sampleTime
                        keys[count] = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        count++
                        if (count and 0xFF == 0) coroutineContext.ensureActive()
                        if (!extractor.advance()) break
                    }
                    if (count == 0) return@withContext null
                    VideoTimeline.fromSamples(times.copyOf(count), keys.copyOf(count))
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    null
                } finally {
                    runCatching { extractor.release() }
                }
            }

        /**
         * Opens [uri] for frame-by-frame access, with frames scaled to fit [maxWidth] x
         * [maxHeight]. Null when it has no decodable video track; the decoder itself is only
         * started by the first [frameAt], which reports a file it cannot handle by returning
         * null.
         */
        suspend fun open(
            context: Context,
            uri: Uri,
            timeline: VideoTimeline,
            maxWidth: Int,
            maxHeight: Int
        ): FrameServer? = withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val track = videoTrackOf(extractor) ?: run {
                    extractor.release()
                    return@withContext null
                }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val sourceWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                val sourceHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    format.getInteger(MediaFormat.KEY_ROTATION)
                } else 0
                // The decoder turns the picture upright on its way to the surface
                val sideways = rotation == 90 || rotation == 270
                val shownWidth = if (sideways) sourceHeight else sourceWidth
                val shownHeight = if (sideways) sourceWidth else sourceHeight
                val scale = min(
                    maxWidth.toFloat() / shownWidth,
                    maxHeight.toFloat() / shownHeight
                ).coerceAtMost(1f)
                val outWidth = max(2, (shownWidth * scale).roundToInt())
                val outHeight = max(2, (shownHeight * scale).roundToInt())
                val frameBytes = outWidth.toLong() * outHeight * 4
                val capacity = (memoryBudget(context) / frameBytes).toInt()
                    .coerceIn(MIN_CAPACITY, MAX_CAPACITY)
                // Cancelled while opening (the page was left): nobody will receive this, so
                // nobody would close it
                coroutineContext.ensureActive()
                FrameServer(extractor, format, timeline, outWidth, outHeight, capacity)
            } catch (t: Throwable) {
                runCatching { extractor.release() }
                if (t is kotlinx.coroutines.CancellationException) throw t
                null
            }
        }

        private fun videoTrackOf(extractor: MediaExtractor): Int? =
            (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            }

        /**
         * Bytes of decoded frames one video may hold: a fortieth of the device's memory, within
         * reason. Bitmap pixels live outside the Java heap, so this is about being a good
         * neighbour, not about the heap limit — and it is all released when the page is left.
         */
        private fun memoryBudget(context: Context): Long {
            val info = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.getMemoryInfo(info)
            return (info.totalMem / 40).coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)
        }

        private const val TAG = "FrameServer"

        private const val MIN_BUDGET_BYTES = 96L * 1024 * 1024
        private const val MAX_BUDGET_BYTES = 384L * 1024 * 1024
        private const val MIN_CAPACITY = 12
        private const val MAX_CAPACITY = 600

        /** Frames kept ahead of a forward step, so the next few taps are already in hand. */
        private const val FORWARD_RUN = 8

        /**
         * Frames kept behind a forward step when the decoder had to start over to reach it.
         * They were decoded on the way regardless; keeping a few makes an immediate step back
         * free without holding the wanted frame up behind a long row of read-backs.
         */
        private const val BACK_KEEP_ON_FORWARD = 8
        private const val MIN_BACK_RUN = 8
        private const val MAX_BACK_RUN = 60

        /** How close to the edge of what is in memory before the next stretch is fetched. */
        private const val PREFETCH_MARGIN = 6

        /** How far outside a pass the user may wander before the pass is not worth finishing. */
        private const val ABANDON_SLACK = 12

        private const val MAX_INPUTS_PER_LOOP = 4
        private const val OUTPUT_WAIT_US = 5_000L
        /** Three seconds of a decoder returning nothing at all. */
        private const val MAX_IDLE_LOOPS = 600
        private const val TIME_MATCH_US = 1_000L

        /** Roughly an hour at 60 fps. Past that, indexing the file costs more than it is worth. */
        private const val MAX_INDEXED_FRAMES = 262_144
    }
}

/**
 * Turns frames a decoder renders to [surface] into bitmaps of [width] x [height].
 *
 * An off-screen GL context with the decoder's surface bound as an external texture: each frame
 * is drawn to a pixel buffer at the output size and read back. Created, used and released on
 * the one thread that decodes.
 */
private class FrameReader(private val width: Int, private val height: Int) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val texture: Int
    private val program: Int
    private val surfaceTexture: SurfaceTexture
    val surface: Surface

    private val callbackThread = HandlerThread("frame-server-frames").apply { start() }
    private val frameLock = Object()
    private var frameAvailable = false

    private val transform = FloatArray(16)
    private val pixels: ByteBuffer =
        ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL did not initialise" }
        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val found = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, found, 0) &&
                found[0] > 0
        ) { "No EGL config" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "No EGL context" }
        eglSurface = EGL14.eglCreatePbufferSurface(
            display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "No EGL surface" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "EGL not current" }

        program = buildProgram()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(texture)
        // The callback must not arrive on the decode thread: that thread is the one waiting
        surfaceTexture.setOnFrameAvailableListener(
            {
                synchronized(frameLock) {
                    frameAvailable = true
                    frameLock.notifyAll()
                }
            },
            Handler(callbackThread.looper)
        )
        surface = Surface(surfaceTexture)
    }

    /** The frame the decoder has just been told to render, or null if it never arrived. */
    fun read(): Bitmap? {
        synchronized(frameLock) {
            val deadline = System.nanoTime() + FRAME_WAIT_MS * 1_000_000L
            while (!frameAvailable) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) return null
                frameLock.wait(remainingMs)
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(transform)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, transform, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)

        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        QUAD.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(position)
        QUAD.position(2)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        pixels.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        pixels.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(pixels)
        bitmap.setHasAlpha(false)
        return bitmap
    }

    fun release() {
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        callbackThread.quitSafely()
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "Shader link failed: ${GLES20.glGetProgramInfoLog(id)}" }
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(id)}" }
        return id
    }

    private companion object {
        const val FRAME_WAIT_MS = 2_000L

        /**
         * x, y, u, v. The texture's v runs the other way from the picture's rows on purpose:
         * `glReadPixels` hands rows back bottom-first, so the frame is drawn upside down and
         * comes out of the read the right way up, with no flip in software.
         */
        val QUAD: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(
                    floatArrayOf(
                        -1f, -1f, 0f, 1f,
                        1f, -1f, 1f, 1f,
                        -1f, 1f, 0f, 0f,
                        1f, 1f, 1f, 0f
                    )
                )
                position(0)
            }

        const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
