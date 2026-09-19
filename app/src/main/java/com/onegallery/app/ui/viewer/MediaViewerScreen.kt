package com.onegallery.app.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
/**
 * @param onVisibleItemChange reports the id of the item currently on screen, so the caller can
 *   restore the grid to it on exit. Reported continuously rather than on back, because back can
 *   arrive as a gesture, a system key or the toolbar button.
 */
fun MediaViewerScreen(
    mediaItems: List<MediaItem>,
    initialIndex: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onVisibleItemChange: (Long) -> Unit = {}
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

    // The pager re-clamps its page only on the next measure pass, so when the library shrinks
    // (file deleted elsewhere) currentPage can briefly point past the end of the new list.
    val currentPage = pagerState.currentPage.coerceIn(0, mediaItems.lastIndex)
    val currentItem = mediaItems[currentPage]

    // Keep the caller told which item is showing, so backing out can land the grid here even
    // if the user paged or scrubbed far from where they entered.
    LaunchedEffect(currentItem.id) { onVisibleItemChange(currentItem.id) }

    // Measured overlay heights, handed to the video page so its controls sit clear of the bars
    val density = LocalDensity.current
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val videoControlsPadding = with(density) {
        PaddingValues(top = topBarHeightPx.toDp(), bottom = bottomBarHeightPx.toDp())
    }

    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

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
            val isActivePage = pagerState.settledPage == pageIndex

            if (item.isVideo) {
                // Video controls share the viewer's overlay visibility, so one tap hides/shows both
                VideoPlayerView(
                    mediaItem = item,
                    modifier = Modifier.fillMaxSize(),
                    isActivePage = isActivePage,
                    controlsVisible = isOverlayVisible,
                    onControlsVisibleChange = { isOverlayVisible = it },
                    controlsPadding = videoControlsPadding
                )
            } else {
                // Zoomable image viewer
                ZoomableImageView(
                    mediaItem = item,
                    isActivePage = isActivePage,
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
                    .onSizeChanged { topBarHeightPx = it.height }
                    .background(Color(0x99000000))
                    .statusBarsPadding()
                    .padding(top = 4.dp, bottom = 12.dp, start = 8.dp, end = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }

                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            val formattedDate = dateFormat.format(Date(currentItem.dateTaken))
                            val formattedTime = timeFormat.format(Date(currentItem.dateTaken))

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
                    .onSizeChanged { bottomBarHeightPx = it.height }
                    .background(Color(0xCC000000))
                    .navigationBarsPadding()
            ) {
                // Samsung Filmstrip Infinity Scrubber
                FilmStripInfinityViewer(
                    mediaItems = mediaItems,
                    currentIndex = currentPage,
                    pagerPosition = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
                    onItemSelected = { targetIndex ->
                        // Snap, don't animate: the strip reports every thumbnail it passes while
                        // scrubbing, and the pager has to keep up with it 1:1
                        coroutineScope.launch {
                            pagerState.scrollToPage(targetIndex)
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
    isActivePage: Boolean,
    onTap: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Swiping away from a zoomed photo shouldn't leave it zoomed when the user comes back
    LaunchedEffect(isActivePage) {
        if (!isActivePage) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        }
    }

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
                // detectTransformGestures consumes every drag past touch slop, which starves the
                // enclosing HorizontalPager of its swipe. This loop only claims the gesture when
                // it really zooms or pans the image; a one-finger drag at 1x, or a push past the
                // horizontal edge of a zoomed image, stays unconsumed so the pager can page.
                awaitEachGesture {
                    var accumulatedZoom = 1f
                    var accumulatedPan = Offset.Zero
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop

                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (!pastTouchSlop) {
                                accumulatedZoom *= zoomChange
                                accumulatedPan += panChange
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1 - accumulatedZoom) * centroidSize
                                if (zoomMotion > touchSlop || accumulatedPan.getDistance() > touchSlop) {
                                    pastTouchSlop = true
                                }
                            }

                            if (pastTouchSlop) {
                                val isMultiTouch = event.changes.count { it.pressed } > 1
                                val newScale = (scale * zoomChange).coerceIn(1f, 4f)
                                val maxOffsetX = (newScale - 1f) * size.width / 2f
                                val maxOffsetY = (newScale - 1f) * size.height / 2f
                                val newOffsetX = (offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                val newOffsetY = (offsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)

                                val pushesPastHorizontalEdge = newOffsetX == offsetX &&
                                    abs(panChange.x) > abs(panChange.y)
                                val movesImage = newScale != scale ||
                                    newOffsetX != offsetX ||
                                    newOffsetY != offsetY

                                if (isMultiTouch || (movesImage && !pushesPastHorizontalEdge)) {
                                    scale = newScale
                                    offsetX = newOffsetX
                                    offsetY = newOffsetY
                                    event.changes.forEach {
                                        if (it.positionChanged()) it.consume()
                                    }
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
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
