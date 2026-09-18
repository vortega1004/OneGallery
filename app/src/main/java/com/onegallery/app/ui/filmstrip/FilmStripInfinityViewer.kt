package com.onegallery.app.ui.filmstrip

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.onegallery.app.domain.MediaItem
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Replicates Samsung Gallery's signature FilmStrip3 UI component:
 * - Center-pinned horizontal thumbnail strip
 * - Midpoint magnification lens effect (thumbnails scale up as they approach center)
 * - 1:1 bidirectional lock with fullscreen image pager
 * - Center lens bracket with video indicator badge
 *
 * Sync model: whoever the user is touching leads.
 * - Pager leads: [pagerPosition] (page + offset fraction) is mirrored onto the strip every frame.
 * - Strip leads: any strip scroll we did not start ourselves is the user scrubbing (drag, fling
 *   or thumbnail tap), and each new center item is reported through [onItemSelected].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilmStripInfinityViewer(
    mediaItems: List<MediaItem>,
    currentIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pagerPosition: () -> Float = { currentIndex.toFloat() }
) {
    if (mediaItems.isEmpty()) return

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceIn(0, mediaItems.lastIndex)
    )
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val itemWidth = 44.dp
    val itemHeight = 60.dp
    val itemSpacing = 4.dp
    val expandedScale = 1.25f
    val itemStridePx = with(density) { (itemWidth + itemSpacing).toPx() }
    val magnifyRangePx = with(density) { 120.dp.toPx() }

    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // These effects outlive a single composition, so they must not capture stale parameters
    val latestOnItemSelected by rememberUpdatedState(onItemSelected)
    val latestPagerPosition by rememberUpdatedState(pagerPosition)
    val latestCurrentIndex by rememberUpdatedState(currentIndex)
    val latestLastIndex by rememberUpdatedState(mediaItems.lastIndex)

    var isSyncingFromPager by remember { mutableStateOf(false) }

    // Pager -> strip: mirror the pager's fractional position so the strip tracks the swipe 1:1
    LaunchedEffect(listState, itemStridePx) {
        snapshotFlow { latestPagerPosition() }
            .collect { position ->
                // A scroll that isn't ours is the user scrubbing the strip; the strip leads then
                if (listState.isScrollInProgress) return@collect

                val clamped = position.coerceIn(0f, latestLastIndex.toFloat())
                val index = floor(clamped).toInt()
                val offsetPx = ((clamped - index) * itemStridePx).roundToInt()
                isSyncingFromPager = true
                try {
                    listState.scrollToItem(index, offsetPx)
                } finally {
                    isSyncingFromPager = false
                }
            }
    }

    // Detect center item during user scrub on the filmstrip
    val centerItemIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val centerOffset = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo.minByOrNull { item ->
                abs((item.offset + item.size / 2) - centerOffset)
            }?.index ?: -1
        }
    }

    // Strip -> pager: report every center item while the user scrubs, including the final resting
    // item once the scroll (or its snap fling) has ended
    LaunchedEffect(listState) {
        var userScrolling = false
        snapshotFlow { listState.isScrollInProgress to centerItemIndex }
            .collect { (scrolling, centerIndex) ->
                if (scrolling && !isSyncingFromPager) userScrolling = true
                if (userScrolling && centerIndex >= 0 && centerIndex != latestCurrentIndex) {
                    latestOnItemSelected(centerIndex)
                }
                if (!scrolling) userScrolling = false
            }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        // Calculate center padding so that item 0 and item N-1 can rest dead center
        val centerPadding = ((maxWidth - itemWidth) / 2).coerceAtLeast(0.dp)

        LazyRow(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(horizontal = centerPadding),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(
                items = mediaItems,
                key = { _, item -> item.id }
            ) { index, item ->
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(itemHeight)
                        // Magnification based on distance from center. Read in the layer block so
                        // scrolling only re-draws the thumbnails instead of recomposing them.
                        .graphicsLayer {
                            val layoutInfo = listState.layoutInfo
                            val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                            val magnification = if (itemInfo != null) {
                                val viewportCenter =
                                    (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                                val itemCenter = itemInfo.offset + itemInfo.size / 2f
                                val distance = abs(itemCenter - viewportCenter)
                                val fraction = 1f - (distance / magnifyRangePx).coerceIn(0f, 1f)
                                1f + (expandedScale - 1f) * fraction
                            } else {
                                1f
                            }
                            scaleX = magnification
                            scaleY = magnification
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.DarkGray)
                        .clickable {
                            // Animating the strip counts as a user scrub, so the pager follows it
                            coroutineScope.launch {
                                listState.animateScrollToItem(index)
                            }
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
