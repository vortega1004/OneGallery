package com.onegallery.app.ui.video

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.onegallery.app.ui.common.rememberThumbnailRequest
import com.onegallery.app.ui.common.swallowTaps
import com.onegallery.app.data.MediaStoreRepository
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.ui.theme.OneUIBlue
import com.onegallery.app.ui.theme.ShutterFlashColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Replicates Samsung Video Viewer with 1-Tap Instant Frame Capture:
 * - Direct floating shutter button (no edit mode needed!), bottom-right for the right thumb
 * - Frame-accurate stepping: a jog strip with the previous/next-frame buttons on its ends
 * - "Ghost" controls (outline, barely-there fill) clustered lower-right; centre stays clear
 * - Instant frame capture with zero playback disruption
 * - Shutter flash feedback + animated thumbnail fly-in
 *
 * The controls share their visibility with the host screen's overlays ([controlsVisible]) and are
 * laid out inside [controlsPadding], for a host that draws its own chrome *over* this page. When
 * the host instead reserves room (insetting the page above its bottom bar, as the viewer does)
 * that padding is zero. Playback only runs while [isActivePage] is true and the app is in the
 * foreground.
 *
 * Sound is controlled by the host ([isMuted]) rather than per player, so the choice carries
 * from one video to the next while the user pages through the viewer. Videos autoplay, so the
 * host is expected to start muted.
 *
 * [onFrameWalkChange] tells the host when the user starts walking frames and when they go back
 * to playing. Someone stepping frame by frame is studying the picture, so the host takes its own
 * chrome off it for as long as that lasts.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerView(
    mediaItem: MediaItem,
    modifier: Modifier = Modifier,
    isActivePage: Boolean = true,
    controlsVisible: Boolean = true,
    onControlsVisibleChange: (Boolean) -> Unit = {},
    controlsPadding: PaddingValues = PaddingValues(0.dp),
    isMuted: Boolean = true,
    onMutedChange: (Boolean) -> Unit = {},
    onFrameWalkChange: (Boolean) -> Unit = {},
    onSnapshotTaken: ((Bitmap) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { MediaStoreRepository(context.applicationContext) }
    val snapshotManager = remember { VideoSnapshotManager(context.applicationContext, repository) }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(mediaItem.durationMs) }
    // Reported by the decoder rather than assumed. Only the detail line reads this; frame
    // stepping asks the player directly, because it needs the rate at the moment it steps.
    var frameRate by remember(mediaItem.uri) { mutableFloatStateOf(0f) }
    // Non-null while the user drags the scrubber, so the thumb follows the finger, not the ticker
    var seekPositionMs by remember { mutableStateOf<Float?>(null) }
    var resumeOnStart by remember { mutableStateOf(false) }
    // The SurfaceView is black until the decoder delivers a frame; a poster covers that gap
    var firstFrameRendered by remember(mediaItem.uri) { mutableStateOf(false) }

    // Flash & thumbnail drop state
    val flashAlpha = remember { Animatable(0f) }
    var capturedThumb by remember { mutableStateOf<Bitmap?>(null) }
    var showCapturedBadge by remember { mutableStateOf(false) }
    var captureCount by remember { mutableIntStateOf(0) }

    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    // Bumped on every seek the user drives (frame steps, jog strip, seek bar). Scrubbing mode is
    // switched on for the burst and off again once they stop — see the effect below.
    var scrubTick by remember { mutableIntStateOf(0) }

    // Measured, not assumed: the capture badge has to clear the control cluster, and the cluster
    // changes height with the phone's width (the step row wraps nothing, but the bars differ).
    var controlsHeightPx by remember { mutableIntStateOf(0) }

    // The area the picture occupies. Decoded stills are scaled to this and no further: a 4K
    // frame is 33 MB of pixels the screen cannot show, and the cache holds a run of them.
    var playerSizePx by remember { mutableStateOf(IntSize.Zero) }

    // Frame-stepping state. While [stepIndex] is non-null the page is showing a decoded still
    // from [frameCache] rather than the player's own output, and every step is a lookup into
    // frames that are already in memory — see VideoFrameCache for why the player cannot do this.
    var frameCache by remember(mediaItem.uri) { mutableStateOf<VideoFrameCache?>(null) }
    var frameCacheChecked by remember(mediaItem.uri) { mutableStateOf(false) }
    var frameCacheOpened by remember(mediaItem.uri) { mutableStateOf(false) }
    var stepIndex by remember(mediaItem.uri) { mutableStateOf<Int?>(null) }
    var stepBitmap by remember(mediaItem.uri) { mutableStateOf<Bitmap?>(null) }
    // Which frame [stepBitmap] actually is. When it trails [stepIndex] the picture has not
    // caught up yet, and the readout says so — "is it lagging, or is the next frame identical?"
    // is the one question a frame-by-frame walk must never leave open.
    var stepBitmapIndex by remember(mediaItem.uri) { mutableStateOf<Int?>(null) }
    // Kept so a fast walk cancels the decode it has already outrun instead of queueing behind it
    var stepJob by remember(mediaItem.uri) { mutableStateOf<Job?>(null) }
    // Prefetching is deliberately *not* cancelled by the next step. It was, at first, and that
    // made it useless: during the fast walk it is meant to smooth, every tap killed the decode
    // that would have fed the tap after it, so each run boundary stalled anyway.
    var prefetchJob by remember(mediaItem.uri) { mutableStateOf<Job?>(null) }
    // Steps taken while the cache is still opening. Opening is asynchronous, so without this
    // the tap that triggers it would seek the player *and* the open would separately decide
    // where the walk began — one tap, two moves, and they disagree. They count from the frame
    // on screen, which does not move while the player is paused.
    var queuedSteps by remember(mediaItem.uri) { mutableIntStateOf(0) }
    // The frame the player was quietly moved to underneath the still, once the user stopped
    // tapping. When it matches the frame being left, pressing play needs no seek at all.
    var parkedIndex by remember(mediaItem.uri) { mutableStateOf<Int?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val handover = remember(mediaItem.uri) {
        StillHandover(mainHandler) {
            // Only if no new walk began while the player was catching up
            if (stepIndex == null) {
                stepBitmap = null
                stepBitmapIndex = null
            }
        }
    }

    // Where the next frame step counts from, or null to count from wherever the player is.
    //
    // Steps must not measure from exoPlayer.currentPosition during a burst. Held buttons and jog
    // drags fire far faster than a seek completes, and while they do the reported position does
    // not reliably reflect the seek already in flight — so every step in the burst measures from
    // the same frame and ten taps move one frame. Anchoring the target here makes a burst add up
    // exactly: ten taps are ten frames, whether or not the decoder has caught up yet.
    var stepAnchorMs by remember(mediaItem.uri) { mutableStateOf<Long?>(null) }

    // Media time of the frame the player last sent to the screen, in microseconds; -1 before the
    // first one. This — not `currentPosition` — is "the frame the user is looking at". The clock
    // runs a little ahead of or behind the picture, and reading it as a frame number put the
    // first step of a walk on the wrong frame: pause on frame 227, press next, get 226.
    val lastFrameUs = remember(mediaItem.uri) { AtomicLong(-1L) }

    val exoPlayer = remember(mediaItem.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(mediaItem.uri))
            // Defaults only — the point is what they leave alone. ScrubbingModeParameters.DEFAULT
            // sets no fractional seek tolerance, so seeks stay frame-exact; what it does change is
            // keeping the codec warm between them (see the stepping comment below).
            setScrubbingModeParameters(ScrubbingModeParameters.DEFAULT)
            // prepare() is deferred until this page is the active one (see below). Scrubbing the
            // filmstrip composes every video page it passes; preparing each of them spun up a
            // decoder per page for nothing.
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_ONE
            // Called on the playback thread as each frame is released to the surface, with
            // its media time (the renderer's stream offset already removed — checked in the
            // 1.8.0 bytecode). This, not a timer, is what says the player has caught up.
            setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
                lastFrameUs.set(presentationTimeUs)
                handover.onFrameReleased(presentationTimeUs, releaseTimeNs)
            }
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        durationMs = duration
                        frameRate = videoFormat?.frameRate ?: 0f
                    }
                }

                // Some codecs publish the format after STATE_READY, so take the later word for it
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    videoFormat?.frameRate?.let { frameRate = it }
                }

                override fun onRenderedFirstFrame() {
                    firstFrameRendered = true
                }
            })
        }
    }

    // Only the settled pager page plays; neighbours composed during a swipe stay paused.
    // The short wait matters while scrubbing the filmstrip: every page it passes is "settled"
    // for a frame or two, and without the wait each video on the way would start playing.
    LaunchedEffect(exoPlayer, isActivePage) {
        if (isActivePage) {
            delay(ACTIVATION_DELAY_MS)
            if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
            // Scrubbing mode silences the audio track, so it must not survive into playback
            // that the user did not start from the play button (paging back onto a video
            // they were mid-scrub on, for instance)
            if (exoPlayer.isScrubbingModeEnabled) exoPlayer.setScrubbingModeEnabled(false)
            exoPlayer.play()
        } else {
            exoPlayer.pause()
            // A still left over from a walk would cover the video when this page comes back
            stepJob?.cancel()
            prefetchJob?.cancel()
            handover.cancel()
            stepIndex = null
            parkedIndex = null
            stepBitmap = null
            stepBitmapIndex = null
        }
    }

    LaunchedEffect(exoPlayer, isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    // Never keep playing (or looping audio) from the background
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        resumeOnStart = exoPlayer.playWhenReady
        exoPlayer.pause()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (resumeOnStart && isActivePage) exoPlayer.play()
        resumeOnStart = false
    }

    // Ticking position updater
    LaunchedEffect(exoPlayer, isPlaying) {
        // Playback invalidates the step anchor: the player is now the authority on position again
        if (isPlaying) stepAnchorMs = null
        currentPositionMs = exoPlayer.currentPosition
        while (isPlaying) {
            delay(100L)
            currentPositionMs = exoPlayer.currentPosition
        }
    }

    // Auto-hide controls after 3 seconds
    val isSeeking = seekPositionMs != null
    LaunchedEffect(controlsVisible, isPlaying, isActivePage, isSeeking) {
        if (controlsVisible && isPlaying && isActivePage && !isSeeking) {
            delay(3000L)
            onControlsVisibleChange(false)
        }
    }

    // Scrubbing mode costs something to leave on (it disables the audio track and runs the video
    // codec hot), so it is dropped once the user has stopped hunting for a frame.
    LaunchedEffect(scrubTick) {
        if (scrubTick == 0) return@LaunchedEffect
        delay(SCRUBBING_IDLE_MS)
        if (exoPlayer.isScrubbingModeEnabled) exoPlayer.setScrubbingModeEnabled(false)
    }

    // Each capture restarts the badge timer, so a quick second capture isn't hidden early
    LaunchedEffect(captureCount) {
        if (captureCount > 0) {
            showCapturedBadge = true
            delay(2500L)
            showCapturedBadge = false
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            handover.cancel()
            exoPlayer.release()
        }
    }

    // Park the player under the still once the user stops tapping. The walk itself never
    // touches the player; this is the one seek it owes, taken while nobody is waiting on it,
    // so that play afterwards starts at once instead of starting with a seek.
    LaunchedEffect(stepIndex, frameCache) {
        val index = stepIndex ?: return@LaunchedEffect
        val cache = frameCache ?: return@LaunchedEffect
        delay(PARK_IDLE_MS)
        if (parkedIndex != index) {
            exoPlayer.seekTo(cache.positionMsFor(index))
            parkedIndex = index
        }
    }

    // Tens of megabytes of decoded frames, and a retriever holding the file open.
    // The instance is captured rather than read inside onDispose: by the time disposal runs,
    // `frameCache` is already the value that replaced it, and the old one would never close.
    val cacheToRelease = frameCache
    DisposableEffect(cacheToRelease) {
        onDispose { cacheToRelease?.close() }
    }

    /** Where the frame on screen sits in the video, falling back to the clock before one is drawn. */
    val shownPositionMs: () -> Long = {
        val us = lastFrameUs.get()
        if (us >= 0L) us / 1000 else exoPlayer.currentPosition
    }

    /** Which frame is on screen, for starting a walk from it. */
    val shownFrameIndex: (VideoFrameCache) -> Int = { cache ->
        val us = lastFrameUs.get()
        if (us >= 0L) cache.nearestIndexFor(us / 1000) else cache.indexFor(exoPlayer.currentPosition)
    }

    /**
     * Puts the player in scrubbing mode for a burst of user-driven seeks.
     *
     * An exact seek normally restarts decoding at the keyframe before the target and replays
     * every frame in between — on a two-second GOP a single-frame step can mean decoding sixty
     * frames, and holding the step button queues one of those per repeat. Scrubbing mode keeps
     * the codec primed across consecutive seeks (it may skip the MediaCodec flush entirely, marks
     * intermediate frames decode-only and raises the operating rate) while leaving the seek
     * itself exact.
     */
    val beginScrub: () -> Unit = {
        if (!exoPlayer.isScrubbingModeEnabled) exoPlayer.setScrubbingModeEnabled(true)
        scrubTick++
    }

    /**
     * Leaves frame-stepping: the player is put where the stepped-to frame is and takes the
     * picture back.
     *
     * The still is held for a moment after the seek. Dropping it the instant playback is asked
     * for would show the frame the player was parked on before the walk, because the seek it
     * was just given has not been rendered yet.
     */
    val leaveStepping: (Boolean) -> Unit = { keepStillBriefly ->
        val cache = frameCache
        val index = stepIndex
        stepJob?.cancel()
        stepJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        var handoverFromUs = 0L
        if (cache != null && index != null) {
            val position = cache.positionMsFor(index)
            currentPositionMs = position
            handoverFromUs = position * 1000
            if (parkedIndex != index) exoPlayer.seekTo(position)
        }
        parkedIndex = null
        stepIndex = null
        if (keepStillBriefly) {
            handover.begin(handoverFromUs, exoPlayer)
        } else {
            handover.cancel()
            stepBitmap = null
            stepBitmapIndex = null
        }
    }

    /**
     * Frame-accurate stepping (buttons and jog strip). Always pauses: single frames are only
     * meaningful on a still picture.
     *
     * Two paths. With a [VideoFrameCache] the walk happens entirely in decoded frames — the
     * index moves immediately, the picture follows as soon as its bitmap is in hand, and the
     * player is not touched until the user leaves stepping. Without one (a container whose
     * frames cannot be addressed by index) it falls back to seeking the player, which is
     * correct but pays a keyframe re-decode per frame.
     */
    val seekStepFrames: (Long, Int) -> Unit = { fromMs, frames ->
        beginScrub()
        val target = frameStepPositionMs(
            currentMs = fromMs,
            frames = frames,
            frameRate = exoPlayer.videoFormat?.frameRate ?: -1f,
            durationMs = durationMs
        )
        stepAnchorMs = target
        exoPlayer.seekTo(target)
        // The position ticker only runs during playback
        currentPositionMs = target
    }

    val stepFrames: (Int) -> Unit = { frames ->
        exoPlayer.pause()
        onFrameWalkChange(true)
        val cache = frameCache
        if (cache == null && !frameCacheOpened) {
            // The cache is on its way. Bank the step rather than seeking for it — the open will
            // apply it, and nothing else touches the position in the meantime.
            queuedSteps += frames
        } else if (cache != null) {
            val from = stepIndex ?: shownFrameIndex(cache)
            val target = (from + frames).coerceIn(0, cache.lastIndex)
            stepIndex = target
            // The number is the whole point of the readout: it must be true the moment the
            // button is released, whether or not the picture has caught up yet.
            currentPositionMs = cache.positionMsFor(target)
            cache.cached(target)?.let { stepBitmap = it; stepBitmapIndex = target }
            stepJob?.cancel()
            stepJob = coroutineScope.launch {
                val decoded = cache.frameAt(target, frames)
                if (decoded == null) {
                    // The probe said this file could be addressed by frame and it cannot after
                    // all. Hand the walk back to the player rather than leaving the readout
                    // moving over a picture that is standing still.
                    if (stepIndex == target) {
                        stepIndex = null
                        stepBitmap = null
                        stepBitmapIndex = null
                        frameCache = null
                        exoPlayer.seekTo(cache.positionMsFor(target))
                    }
                } else {
                    if (stepIndex == target) {
                        stepBitmap = decoded
                        stepBitmapIndex = target
                    }
                    if (prefetchJob?.isActive != true) {
                        prefetchJob = coroutineScope.launch { cache.prefetch(target, frames) }
                    }
                }
            }
        } else {
            seekStepFrames(stepAnchorMs ?: shownPositionMs(), frames)
        }
    }

    /**
     * Opens the frame cache the first time the user asks for a frame, not when the page is
     * composed.
     *
     * Scrubbing the filmstrip composes every video page it passes; opening a retriever and
     * decoding a probe frame for each of them would cost more than the feature saves.
     */
    val beginStepping: () -> Unit = {
        if (!frameCacheChecked) {
            frameCacheChecked = true
            coroutineScope.launch {
                val opened = VideoFrameCache.open(
                    context = context,
                    uri = mediaItem.uri,
                    maxWidth = maxOf(playerSizePx.width, 1),
                    maxHeight = maxOf(playerSizePx.height, 1)
                )
                val steps = queuedSteps
                queuedSteps = 0
                frameCache = opened
                frameCacheOpened = true
                if (opened != null) {
                    val index = (shownFrameIndex(opened) + steps).coerceIn(0, opened.lastIndex)
                    stepIndex = index
                    currentPositionMs = opened.positionMsFor(index)
                    val decoded = opened.frameAt(index, if (steps < 0) -1 else 1)
                    if (stepIndex == index) {
                        stepBitmap = decoded
                        stepBitmapIndex = index
                    }
                    prefetchJob = coroutineScope.launch {
                        opened.prefetch(index, if (steps < 0) -1 else 1)
                    }
                } else if (steps != 0) {
                    // No frame-index access on this file: the banked steps are owed either way
                    seekStepFrames(shownPositionMs(), steps)
                }
            }
        }
    }

    /**
     * Leaves scrubbing mode at once, rather than waiting out [SCRUBBING_IDLE_MS].
     *
     * Enabling scrubbing mode suppresses playback (`PLAYBACK_SUPPRESSION_REASON_SCRUBBING`)
     * without clearing `playWhenReady`, so a seek-bar drag taken while playing would otherwise
     * sit silent for the whole idle window before resuming. Frame stepping keeps the timer —
     * it is already paused, and staying warm is the point between taps.
     */
    val endScrub: () -> Unit = {
        if (exoPlayer.isScrubbingModeEnabled) exoPlayer.setScrubbingModeEnabled(false)
    }

    val resumePlayback: () -> Unit = {
        if (exoPlayer.isScrubbingModeEnabled) exoPlayer.setScrubbingModeEnabled(false)
        leaveStepping(true)
        exoPlayer.play()
        onFrameWalkChange(false)
    }

    val captureFrame: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        coroutineScope.launch {
            // Fire shutter flash animation
            launch {
                flashAlpha.snapTo(0.8f)
                flashAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                )
            }

            // Capture frame immediately
            snapshotManager.captureCurrentFrame(
                textureView = textureViewRef,
                videoItem = mediaItem,
                // While stepping, the player is still parked where the walk began; the frame
                // the user is looking at — and means to capture — is the stepped-to one.
                // Otherwise it is the frame the player last drew, which the clock only
                // approximates.
                currentPositionMs = stepIndex?.let { frameCache?.positionMsFor(it) }
                    ?: shownPositionMs()
            ) { thumbnail ->
                capturedThumb = thumbnail
                captureCount++
                onSnapshotTaken?.invoke(thumbnail)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { playerSizePx = it }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onControlsVisibleChange(!controlsVisible)
            }
    ) {
        // Video Surface using PlayerView with TextureView for instant screen-buffer capture
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    player = exoPlayer

                    // Find the underlying TextureView for frame extraction
                    post {
                        val tv = findTextureView(this)
                        textureViewRef = tv
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Poster frame until the first decoded frame is on screen. Thumbnail-sized, so it is
        // served from MediaStore's cache (usually already in memory from the grid/filmstrip).
        if (!firstFrameRendered) {
            AsyncImage(
                model = rememberThumbnailRequest(mediaItem.uri),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // The frame being stepped to, decoded ahead of time and drawn over the player's own
        // output. The player is left parked where the walk began until the user leaves
        // stepping, because moving it is the expensive thing this whole path exists to avoid.
        stepBitmap?.let { bitmap ->
            Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Shutter White Flash Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(flashAlpha.value)
                .background(ShutterFlashColor)
        )

        // Captured Thumbnail Drop Badge (Bottom-Left Preview)
        AnimatedVisibility(
            visible = showCapturedBadge && capturedThumb != null,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = 24.dp,
                    bottom = controlsPadding.calculateBottomPadding() +
                        with(density) { controlsHeightPx.toDp() } + 24.dp
                )
        ) {
            capturedThumb?.let { bmp ->
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .shadow(12.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    Image(
                        bitmap = remember(bmp) { bmp.asImageBitmap() },
                        contentDescription = "Captured Frame",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Ghost control cluster.
        //
        // Two rows and nothing above them: every dp this cluster is tall is a dp of picture it
        // covers, and someone walking frames is looking at the picture. The file name lives in
        // the host's top bar. The frame-hunting controls are one pill — jog strip with a step
        // button on each end — sitting under the right thumb, the shutter in the corner beside
        // it, and the two numbers worth knowing on the far left where no thumb is pressing. A
        // tap anywhere on the video is not bound to play/pause — that toggles the controls.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = controlsPadding.calculateBottomPadding() + 12.dp
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { controlsHeightPx = it.height }
                    // A tap that lands in the cluster but misses a control does nothing at all.
                    // The page toggles its chrome on tap, so the gaps between these buttons used
                    // to dismiss the very controls the user was reaching for.
                    .swallowTaps(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Walking frame by frame, the frame number replaces the clip's summary and
                    // is shown brighter: it is the only way to tell "the next frame" from "the
                    // same frame, still decoding". It updates the instant the button is
                    // released, ahead of the picture.
                    val walkedIndex = stepIndex
                    val cacheForStats = frameCache
                    val walking = walkedIndex != null && cacheForStats != null
                    val readout: Pair<String, String>? =
                        if (walkedIndex != null && cacheForStats != null) {
                            // One-based: nobody counts the first frame as frame zero. The
                            // ellipsis means the number has moved and the picture has not yet.
                            val catchingUp = stepBitmapIndex != walkedIndex
                            "frame" to "${walkedIndex + 1} / ${cacheForStats.frameCount}" +
                                if (catchingUp) " …" else ""
                        } else {
                            formatClipStats(durationMs, frameRate, cacheForStats?.frameCount ?: 0)
                        }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    ) {
                        if (readout != null) {
                            Text(
                                text = readout.first,
                                color = GhostContentDim,
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                style = GhostTextStyle
                            )
                            Text(
                                text = readout.second,
                                color = if (walking) GhostContent else GhostContentDim,
                                fontSize = if (walking) 13.sp else 10.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                style = GhostTextStyle
                            )
                        }
                    }

                    // Precision scrub: distance = frames, regardless of how long the video is.
                    // Weighted rather than fixed, so it narrows on a small phone instead of
                    // pushing the shutter off the edge.
                    FrameJogBar(
                        onScrubStart = {
                            exoPlayer.pause()
                            beginStepping()
                            // Only the fallback path seeks, and only it needs a warm codec
                            if (frameCache == null) beginScrub()
                        },
                        onStepFrames = { frames ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            stepFrames(frames)
                        },
                        onStepButton = { direction ->
                            beginStepping()
                            stepFrames(direction)
                        },
                        modifier = Modifier.weight(JOG_BAR_WEIGHT)
                    )
                    // 1-tap frame capture, in the corner the right thumb rests on
                    GhostIconButton(
                        icon = Icons.Rounded.Camera,
                        contentDescription = "Capture Frame",
                        onClick = captureFrame,
                        size = 56.dp,
                        iconSize = 28.dp,
                        emphasized = true
                    )
                }

                // Playback bar: time | seek | duration | play/pause | mute.
                // Play moved off the far left to this end of the bar, inboard of mute.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x33000000), RoundedCornerShape(16.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    val maxPositionMs = durationMs.toFloat().coerceAtLeast(1f)
                    val shownPositionMs = seekPositionMs?.toLong() ?: currentPositionMs

                    // One decimal, always. Switching precision between playing and paused made
                    // the label change width and shoved the seek bar sideways on every pause.
                    Text(
                        text = formatTime(shownPositionMs),
                        color = GhostContent,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        style = GhostTextStyle,
                        modifier = Modifier.padding(start = 6.dp)
                    )

                    Slider(
                        value = (seekPositionMs ?: currentPositionMs.toFloat()).coerceIn(0f, maxPositionMs),
                        onValueChange = { newPos ->
                            // Dragging the bar abandons any frame walk outright: it is about to
                            // seek the player anyway, so there is nothing to hand back.
                            val leavingWalk = stepIndex != null
                            if (leavingWalk) {
                                stepJob?.cancel()
                                stepIndex = null
                                parkedIndex = null
                                // The walk is over, so the host's chrome may come back
                                onFrameWalkChange(false)
                            }
                            // Same warm-codec path the fallback frame steps use: a drag is
                            // dozens of exact seeks in a row
                            beginScrub()
                            seekPositionMs = newPos
                            // Stepping after a drag continues from where the drag left off
                            stepAnchorMs = newPos.toLong()
                            exoPlayer.seekTo(newPos.toLong())
                            // Keep the still until the drag's own first frame is drawn, or
                            // the frame the player was parked on flashes up first — the
                            // same race as pressing play. Armed after the seek is queued.
                            if (leavingWalk) handover.begin(0L, exoPlayer)
                        },
                        onValueChangeFinished = {
                            seekPositionMs?.let { dragged ->
                                // Land on a frame boundary where the boundaries are known, so a
                                // walk started right after a drag begins on the frame that is
                                // actually on screen rather than the one before it.
                                val cache = frameCache
                                val settledMs = if (cache != null) {
                                    cache.positionMsFor(cache.nearestIndexFor(dragged.toLong()))
                                } else {
                                    dragged.toLong()
                                }
                                currentPositionMs = settledMs
                                if (settledMs != dragged.toLong()) exoPlayer.seekTo(settledMs)
                            }
                            seekPositionMs = null
                            endScrub()
                        },
                        valueRange = 0f..maxPositionMs,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xCCFFFFFF),
                            inactiveTrackColor = Color(0x40FFFFFF)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )

                    Text(
                        text = formatTime(durationMs),
                        color = GhostContent,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        style = GhostTextStyle
                    )

                    // The most-used control in the cluster, and the one reported missed
                    GhostIconButton(
                        icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        // Pausing is the tell that frames are about to be walked: getting the
                        // cache open now is what makes the first step feel like the rest.
                        onClick = {
                            if (isPlaying) {
                                exoPlayer.pause()
                                beginStepping()
                            } else {
                                resumePlayback()
                            }
                        },
                        size = 48.dp,
                        iconSize = 26.dp
                    )

                    GhostIconButton(
                        icon = if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        onClick = { onMutedChange(!isMuted) },
                        size = 40.dp,
                        iconSize = 20.dp
                    )
                }
            }
        }
    }
}

/** Soft shadow instead of a backing pill, so time labels stay legible over bright footage. */
private val GhostTextStyle = TextStyle(
    shadow = Shadow(color = Color(0xCC000000), offset = Offset(0f, 1f), blurRadius = 4f)
)

private const val ACTIVATION_DELAY_MS = 150L

/** How long after the last user-driven seek the player leaves scrubbing mode. */
private const val SCRUBBING_IDLE_MS = 900L

/** How long the user has to stop tapping before the player is parked under the still. */
private const val PARK_IDLE_MS = 400L

/** The jog bar's share of the frame row against the readout's 1: wide enough to drag along. */
private const val JOG_BAR_WEIGHT = 2.4f

/**
 * Hands the picture from the stepped-to still back to the player — but only once the player
 * has actually drawn a frame at or past where the walk ended.
 *
 * A timer cannot do this. The seek that precedes playback takes as long as it takes: 150 ms was
 * plenty on the emulator and nowhere near enough for 4K on a phone, and for however long it
 * ran over, dropping the still uncovered what the player's surface was still holding — the
 * frame the walk *started* from, before playback jumped forward to the right one.
 *
 * [onFrameReleased] is fed by ExoPlayer's `VideoFrameMetadataListener`, on the playback thread,
 * as each frame goes to the surface. The still is dropped once that frame is on screen: after
 * its release time, plus a vsync so the surface has composited it. [TIMEOUT_MS] is the
 * backstop — a still must never outlive a player that, for whatever reason, draws nothing.
 *
 * Every [begin] bumps a generation, so a release or a timeout belonging to an earlier handover
 * cannot drop a still that a newer walk has put up since.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class StillHandover(
    private val main: Handler,
    private val dropStill: () -> Unit
) {
    /** Media time the player must reach, in microseconds; -1 when nothing is pending. */
    private val targetUs = AtomicLong(-1L)
    private val generation = AtomicInteger(0)
    /** Whether frames count yet — see [begin]. */
    private val armed = AtomicBoolean(false)

    /**
     * Keep the still until the player draws a frame at or past [fromUs] — one drawn *after*
     * everything already queued on [player], the seek included, has been processed.
     *
     * "At or past" alone is not enough. A walk that stepped back from where the player was
     * parked ends *behind* the frame the player last drew, so that stale frame already
     * satisfies it: stepping 103 → 100 and pressing play used to show 103 before playing from
     * 100. ExoPlayer works through its messages in order on the playback thread, so a message
     * sent now, and delivered on that same thread, runs only once the seek has been handled and
     * the decoder flushed. It arms the handover; nothing released before it counts.
     */
    fun begin(fromUs: Long, player: ExoPlayer) {
        val current = generation.incrementAndGet()
        armed.set(false)
        targetUs.set(fromUs.coerceAtLeast(0L))
        player.createMessage { _, _ -> if (generation.get() == current) armed.set(true) }
            .setLooper(player.playbackLooper)
            .send()
        main.postDelayed({ finish(current) }, TIMEOUT_MS)
    }

    fun cancel() {
        generation.incrementAndGet()
        armed.set(false)
        targetUs.set(-1L)
    }

    /** Playback thread. */
    fun onFrameReleased(presentationTimeUs: Long, releaseTimeNs: Long) {
        if (!armed.get()) return
        val target = targetUs.get()
        if (target < 0L || presentationTimeUs < target) return
        if (!targetUs.compareAndSet(target, -1L)) return
        val current = generation.get()
        val untilShownMs = ((releaseTimeNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
        main.postDelayed({ finish(current) }, untilShownMs + ONE_VSYNC_MS)
    }

    private fun finish(expected: Int) {
        if (generation.get() != expected) return
        armed.set(false)
        targetUs.set(-1L)
        // Retire this generation so the timeout, or a late release, cannot fire it again
        generation.incrementAndGet()
        dropStill()
    }

    private companion object {
        const val TIMEOUT_MS = 2_500L
        const val ONE_VSYNC_MS = 17L
    }
}

/**
 * m:ss.t — the only clock format in the player.
 *
 * One fixed format, because swapping between seconds and milliseconds changed the label's width
 * and dragged the seek bar sideways on every pause.
 *
 * Tenths are for reading, not for counting: a frame is 33.3 ms at 30 fps, so consecutive frames
 * often share a tenth. This also renders [currentPositionMs], which a frame step sets to the
 * position it *asked* for — it is not a report of what the decoder rendered. Neither this nor
 * the value behind it can be used as evidence of a frame count.
 */
private fun formatTime(ms: Long): String {
    val clamped = ms.coerceAtLeast(0)
    return String.format(
        Locale.US,
        "%d:%02d.%d",
        clamped / 60_000,
        (clamped / 1000) % 60,
        (clamped % 1000) / 100
    )
}

/**
 * "29.97 fps" over "1,847 frames", or null when the decoder has not reported a frame rate.
 *
 * [exactFrameCount] comes from the container's own metadata and is preferred whenever it is
 * available. Failing that the count is duration x rate: exact for constant-rate footage, an
 * estimate for anything variable, which is the same assumption [frameStepPositionMs] makes when
 * it steps. Nothing is shown at all rather than a plausible-looking number from a default rate.
 */
private fun formatClipStats(durationMs: Long, frameRate: Float, exactFrameCount: Int): Pair<String, String>? {
    if (frameRate <= 0f || durationMs <= 0L) return null
    val rate = if (abs(frameRate - frameRate.roundToInt()) < 0.01f) frameRate.roundToInt().toString()
    else String.format(Locale.US, "%.2f", frameRate)
    val frames =
        if (exactFrameCount > 0) exactFrameCount.toLong()
        else (durationMs / 1000.0 * frameRate).roundToLong()
    return "$rate fps" to String.format(Locale.US, "%,d frames", frames)
}

private fun findTextureView(viewGroup: ViewGroup): TextureView? {
    for (i in 0 until viewGroup.childCount) {
        val child = viewGroup.getChildAt(i)
        if (child is TextureView) return child
        if (child is ViewGroup) {
            val found = findTextureView(child)
            if (found != null) return found
        }
    }
    return null
}
