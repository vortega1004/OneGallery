package com.onegallery.app.ui.filmstrip

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.onegallery.app.domain.MediaItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Replicates Samsung Gallery's signature FilmStrip3 UI component:
 * - Center-pinned horizontal thumbnail strip
 * - Midpoint magnification lens effect (thumbnails scale up as they approach center)
 * - 1:1 bidirectional lock with fullscreen image pager
 * - Rounded active border with video indicator badge
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilmStripInfinityViewer(
    mediaItems: List<MediaItem>,
    currentIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isViewerScrolling: Boolean = false
) {
    if (mediaItems.isEmpty()) return

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    val screenWidth = configuration.screenWidthDp.dp
    val itemWidth = 44.dp
    val itemHeight = 60.dp
    val expandedScale = 1.25f

    // Calculate center padding so that item 0 and item N-1 can rest dead center
    val centerPadding = (screenWidth / 2) - (itemWidth / 2)

    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // Synchronize when the main pager changes page externally
    LaunchedEffect(currentIndex) {
        if (!listState.isScrollInProgress) {
            listState.animateScrollToItem(
                index = currentIndex,
                scrollOffset = 0
            )
        }
    }

    // Detect center item during user scrub on the filmstrip
    val centerItemIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) currentIndex
            else {
                val centerOffset = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                visibleItems.minByOrNull { item ->
                    abs((item.offset + item.size / 2) - centerOffset)
                }?.index ?: currentIndex
            }
        }
    }

    // Notify parent pager when user scrubs the filmstrip
    LaunchedEffect(listState) {
        snapshotFlow { centerItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (listState.isScrollInProgress && !isViewerScrolling) {
                    onItemSelected(index)
                }
            }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        LazyRow(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(horizontal = centerPadding),
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(
                items = mediaItems,
                key = { _, item -> item.id }
            ) { index, item ->
                val isSelected = index == currentIndex

                // Magnification calculation based on distance from center
                val layoutInfo = listState.layoutInfo
                val itemInfo = layoutInfo.visibleItemsInfo.find { it.index == index }
                val scale = if (itemInfo != null) {
                    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                    val itemCenter = itemInfo.offset + itemInfo.size / 2f
                    val distance = abs(itemCenter - viewportCenter)
                    val maxDistance = with(density) { 120.dp.toPx() }
                    val fraction = (1f - (distance / maxDistance).coerceIn(0f, 1f))
                    1f + (expandedScale - 1f) * fraction
                } else {
                    if (isSelected) expandedScale else 1f
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .width(itemWidth)
                        .height(itemHeight)
                        .scale(scale)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.DarkGray)
                        .then(
                            if (isSelected) {
                                Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                            } else {
                                Modifier
                            }
                        )
                        .clickable {
                            coroutineScope.launch {
                                listState.animateScrollToItem(index)
                            }
                            onItemSelected(index)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Video icon indicator overlay
                    if (item.isVideo) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x40000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Center guide marker (subtle Samsung-style active lens bracket)
        Box(
            modifier = Modifier
                .width(itemWidth * expandedScale + 4.dp)
                .height(itemHeight * expandedScale + 4.dp)
                .border(
                    width = 2.5.dp,
                    color = Color.White,
                    shape = RoundedCornerShape(10.dp)
                )
        )
    }
}
