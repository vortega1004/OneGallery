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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.onegallery.app.ui.common.rememberThumbnailRequest
import com.onegallery.app.data.MediaStoreRepository
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.ui.theme.OneUIBlue
import com.onegallery.app.ui.theme.ShutterFlashColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Replicates Samsung Video Viewer with 1-Tap Instant Frame Capture:
 * - Direct floating shutter button in the top-left (no edit mode needed!)
 * - Instant frame capture with zero playback disruption
 * - Shutter flash feedback + animated thumbnail fly-in
 *
 * The controls share their visibility with the host screen's overlays ([controlsVisible]) and are
 * laid out inside [controlsPadding] so the host's top/bottom bars never cover them. Playback only
 * runs while [isActivePage] is true and the app is in the foreground.
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

    val exoPlayer = remember(mediaItem.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(mediaItem.uri))
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

        // Floating 1-Tap Capture Button (Samsung Top-Left Shutter)
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = controlsPadding.calculateTopPadding() + 12.dp, start = 20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x99000000))
                    .clickable {
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
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Camera,
                    contentDescription = "Capture Frame",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Capture",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

        // Captured Thumbnail Drop Badge (Bottom-Left Preview)
        AnimatedVisibility(
            visible = showCapturedBadge && capturedThumb != null,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = controlsPadding.calculateBottomPadding() + 88.dp)
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

        // Play/Pause Center Button
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.play()
                    }
                },
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0x66000000), CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Bottom Playback Scrubber Bar
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = controlsPadding.calculateBottomPadding() + 12.dp
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x88000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                val maxPositionMs = durationMs.toFloat().coerceAtLeast(1f)

                Text(
                    text = formatMs(seekPositionMs?.toLong() ?: currentPositionMs),
                    color = Color.White,
                    fontSize = 12.sp
                )

                Slider(
                    value = (seekPositionMs ?: currentPositionMs.toFloat()).coerceIn(0f, maxPositionMs),
                    onValueChange = { newPos ->
                        seekPositionMs = newPos
                        exoPlayer.seekTo(newPos.toLong())
                    },
                    onValueChangeFinished = {
                        seekPositionMs?.let { currentPositionMs = it.toLong() }
                        seekPositionMs = null
                    },
                    valueRange = 0f..maxPositionMs,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = OneUIBlue,
                        inactiveTrackColor = Color.Gray
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )

                Text(
                    text = formatMs(durationMs),
                    color = Color.LightGray,
                    fontSize = 12.sp
                )

                IconButton(
                    onClick = { onMutedChange(!isMuted) },
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isMuted) {
                            Icons.Rounded.VolumeOff
                        } else {
                            Icons.Rounded.VolumeUp
                        },
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private const val ACTIVATION_DELAY_MS = 150L

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
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
