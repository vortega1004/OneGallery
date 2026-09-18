package com.onegallery.app.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.ui.filmstrip.FilmStripInfinityViewer
import com.onegallery.app.ui.video.VideoPlayerView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    mediaItems: List<MediaItem>,
    initialIndex: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (mediaItems.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, mediaItems.size - 1),
        pageCount = { mediaItems.size }
    )
    val coroutineScope = rememberCoroutineScope()
    var isOverlayVisible by remember { mutableStateOf(true) }
    var showDetailsSheet by remember { mutableStateOf(false) }
    val detailsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val currentItem = mediaItems[pagerState.currentPage]

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Fullscreen Pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { index -> mediaItems[index].id }
        ) { pageIndex ->
            val item = mediaItems[pageIndex]

            if (item.isVideo) {
                VideoPlayerView(
                    mediaItem = item,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Zoomable image viewer
                ZoomableImageView(
                    mediaItem = item,
                    onTap = { isOverlayVisible = !isOverlayVisible }
                )
            }
        }

        // Top Overlay Bar (Samsung style date/time header + action icons)
        AnimatedVisibility(
            visible = isOverlayVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(top = 40.dp, bottom = 12.dp, start = 8.dp, end = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }

                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            val formattedDate = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                                .format(Date(currentItem.dateTaken))
                            val formattedTime = SimpleDateFormat("h:mm a", Locale.getDefault())
                                .format(Date(currentItem.dateTaken))

                            Text(
                                text = formattedDate,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = formattedTime,
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showDetailsSheet = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = "Details",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { /* Set wallpaper / Rename */ }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Bottom Controls Container: FilmStrip Scrubber + Action Bar
        AnimatedVisibility(
            visible = isOverlayVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
            ) {
                // Samsung Filmstrip Infinity Scrubber
                FilmStripInfinityViewer(
                    mediaItems = mediaItems,
                    currentIndex = pagerState.currentPage,
                    isViewerScrolling = pagerState.isScrollInProgress,
                    onItemSelected = { targetIndex ->
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(targetIndex)
                        }
                    }
                )

                // One UI Bottom Action Bar: Share, Edit, Favorite, Delete
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { /* Share intent */ }) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = "Share",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { /* Edit photo */ }) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { /* Toggle favorite */ }) {
                        Icon(
                            imageVector = if (currentItem.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (currentItem.isFavorite) Color.Red else Color.White
                        )
                    }
                    IconButton(onClick = { /* Delete confirmation */ }) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Delete",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // Swipe-up / Info Sheet
        if (showDetailsSheet) {
            MediaDetailsSheet(
                mediaItem = currentItem,
                sheetState = detailsSheetState,
                onDismiss = { showDetailsSheet = false }
            )
        }
    }
}

@Composable
private fun ZoomableImageView(
    mediaItem: MediaItem,
    onTap: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        scale = if (scale > 1.2f) 1f else 2.5f
                        offsetX = 0f
                        offsetY = 0f
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = mediaItem.uri,
            contentDescription = mediaItem.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
    }
}
