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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerView(
    mediaItem: MediaItem,
    modifier: Modifier = Modifier,
    onSnapshotTaken: ((Bitmap) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { MediaStoreRepository(context) }
    val snapshotManager = remember { VideoSnapshotManager(context, repository) }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(mediaItem.durationMs) }
    var controlsVisible by remember { mutableStateOf(true) }

    // Flash & thumbnail drop state
    val flashAlpha = remember { Animatable(0f) }
    var capturedThumb by remember { mutableStateOf<Bitmap?>(null) }
    var showCapturedBadge by remember { mutableStateOf(false) }

    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    val exoPlayer = remember(mediaItem.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(mediaItem.uri))
            prepare()
            playWhenReady = true
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
            })
        }
    }

    // Ticking position updater
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPositionMs = exoPlayer.currentPosition
            delay(100L)
        }
    }

    // Auto-hide controls after 3 seconds
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3000L)
            controlsVisible = false
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
                controlsVisible = !controlsVisible
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
                .padding(top = 48.dp, start = 20.dp)
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
                            val result = snapshotManager.captureCurrentFrame(
                                textureView = textureViewRef,
                                videoItem = mediaItem,
                                currentPositionMs = exoPlayer.currentPosition
                            ) { instantBitmap ->
                                capturedThumb = instantBitmap
                                showCapturedBadge = true
                                onSnapshotTaken?.invoke(instantBitmap)
                            }

                            // Keep badge visible for 2.5 seconds
                            delay(2500L)
                            showCapturedBadge = false
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
                .padding(start = 24.dp, bottom = 100.dp)
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
                        bitmap = bmp.asImageBitmap(),
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
                .padding(start = 24.dp, end = 24.dp, bottom = 20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x88000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = formatMs(currentPositionMs),
                    color = Color.White,
                    fontSize = 12.sp
                )

                Slider(
                    value = currentPositionMs.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
                    onValueChange = { newPos ->
                        exoPlayer.seekTo(newPos.toLong())
                    },
                    valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
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
            }
        }
    }
}

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
