package com.onegallery.app.ui.video

import android.graphics.Bitmap
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.onegallery.app.ui.common.rememberThumbnailRequest
import com.onegallery.app.data.MediaStoreRepository
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.ui.theme.OneUIBlue
import com.onegallery.app.ui.theme.ShutterFlashColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Replicates Samsung Video Viewer with 1-Tap Instant Frame Capture:
 * - Direct floating shutter button (no edit mode needed!), bottom-right for the right thumb
 * - Frame-accurate stepping: jog strip + previous/next-frame buttons
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

    // Where the next frame step counts from, or null to count from wherever the player is.
    //
    // Steps must not measure from exoPlayer.currentPosition during a burst. Held buttons and jog
    // drags fire far faster than a seek completes, and while they do the reported position does
    // not reliably reflect the seek already in flight — so every step in the burst measures from
    // the same frame and ten taps move one frame. Anchoring the target here makes a burst add up
    // exactly: ten taps are ten frames, whether or not the decoder has caught up yet.
    var stepAnchorMs by remember(mediaItem.uri) { mutableStateOf<Long?>(null) }

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
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        durationMs = duration
                    }
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
            exoPlayer.release()
        }
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

    // Frame-accurate stepping (buttons and jog strip). Always pauses: single frames are only
    // meaningful on a still picture. ExoPlayer's default seek mode is already exact.
    val stepFrames: (Int) -> Unit = { frames ->
        exoPlayer.pause()
        beginScrub()
        val target = frameStepPositionMs(
            currentMs = stepAnchorMs ?: exoPlayer.currentPosition,
            frames = frames,
            frameRate = exoPlayer.videoFormat?.frameRate ?: -1f,
            durationMs = durationMs
        )
        stepAnchorMs = target
        exoPlayer.seekTo(target)
        // The position ticker only runs during playback
        currentPositionMs = target
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
        exoPlayer.play()
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
                currentPositionMs = exoPlayer.currentPosition
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
        // Two rows, not three: the jog strip shares a row with the frame-step buttons and the
        // shutter rather than sitting above them, because every dp this cluster is tall is a dp
        // of picture it covers. The frame-hunting controls stay bunched at the right where a
        // right thumb reaches, with the shutter in the corner itself; the jog strip takes the
        // leftover width. A tap anywhere on the video is not bound to play/pause — that toggles
        // the controls.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
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
                    .onSizeChanged { controlsHeightPx = it.height },
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Precision scrub: distance = frames, regardless of how long the video is.
                    // Given the leftover width, so it shrinks on a narrow phone instead of
                    // pushing the buttons off the edge.
                    FrameJogStrip(
                        onScrubStart = {
                            exoPlayer.pause()
                            beginScrub()
                        },
                        onStepFrames = { frames ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            stepFrames(frames)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                    )
                    GhostRepeatButton(
                        icon = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous frame",
                        onStep = { stepFrames(-1) }
                    )
                    GhostRepeatButton(
                        icon = Icons.Rounded.SkipNext,
                        contentDescription = "Next frame",
                        onStep = { stepFrames(1) }
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
                            // Same warm-codec path the frame steps use: a drag is dozens of
                            // exact seeks in a row
                            beginScrub()
                            seekPositionMs = newPos
                            // Stepping after a drag continues from where the drag left off
                            stepAnchorMs = newPos.toLong()
                            exoPlayer.seekTo(newPos.toLong())
                        },
                        onValueChangeFinished = {
                            seekPositionMs?.let { currentPositionMs = it.toLong() }
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

                    GhostIconButton(
                        icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        onClick = { if (isPlaying) exoPlayer.pause() else resumePlayback() },
                        size = 44.dp,
                        iconSize = 24.dp
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
