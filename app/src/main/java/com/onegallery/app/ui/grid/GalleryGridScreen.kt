package com.onegallery.app.ui.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.ui.theme.DarkBackground
import com.onegallery.app.ui.theme.DarkSurface
import com.onegallery.app.ui.theme.DarkTextPrimary
import com.onegallery.app.ui.theme.DarkTextSecondary
import com.onegallery.app.ui.theme.OneUIBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GalleryGridScreen(
    mediaItems: List<MediaItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var columnCount by remember { mutableIntStateOf(3) }
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Rounded.Image, contentDescription = "Pictures") },
                    label = { Text("Pictures") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = OneUIBlue,
                        indicatorColor = OneUIBlue
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Rounded.Folder, contentDescription = "Albums") },
                    label = { Text("Albums") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = OneUIBlue,
                        indicatorColor = OneUIBlue
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Rounded.Search, contentDescription = "Search") },
                    label = { Text("Search") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = OneUIBlue,
                        indicatorColor = OneUIBlue
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // One UI Large Header
            OneUIHeader(
                title = "Pictures",
                subtitle = "${mediaItems.size} items"
            )

            // Responsive pinch-to-zoom Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnCount),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Runs in the Initial pass so a two-finger pinch is claimed before the
                        // grid's own scrolling sees (and consumes) it. Zoom deltas arrive per
                        // event (each ~1.0), so they are accumulated across the gesture.
                        awaitEachGesture {
                            var cumulativeZoom = 1f
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } >= 2) {
                                    cumulativeZoom *= event.calculateZoom()
                                    if (cumulativeZoom > 1.25f) {
                                        if (columnCount > 1) columnCount--
                                        cumulativeZoom = 1f
                                    } else if (cumulativeZoom < 0.8f) {
                                        if (columnCount < 5) columnCount++
                                        cumulativeZoom = 1f
                                    }
                                    event.changes.forEach {
                                        if (it.positionChanged()) it.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            ) {
                itemsIndexed(
                    items = mediaItems,
                    key = { _, item -> item.id }
                ) { index, item ->
                    MediaGridCell(
                        mediaItem = item,
                        onClick = { onItemClick(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OneUIHeader(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Text(
            text = title,
            color = DarkTextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = DarkTextSecondary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun MediaGridCell(
    mediaItem: MediaItem,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(0xFF1F2125))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = mediaItem.uri,
            contentDescription = mediaItem.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Video Badge & Duration
        if (mediaItem.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = mediaItem.formattedDuration,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        }
    }
}
