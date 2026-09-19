package com.onegallery.app.ui.grid

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.onegallery.app.domain.Album
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.domain.toAlbums
import com.onegallery.app.ui.theme.DarkBackground
import com.onegallery.app.ui.theme.DarkSurface
import com.onegallery.app.ui.theme.DarkTextPrimary
import com.onegallery.app.ui.theme.DarkTextSecondary
import com.onegallery.app.ui.theme.OneUIBlue

const val TAB_PICTURES = 0
const val TAB_ALBUMS = 1
const val TAB_SEARCH = 2

/**
 * Browsing state ([selectedTab], [openedAlbumId], [columnCount]) is **hoisted deliberately**.
 * The viewer replaces this screen in the composition rather than stacking on top of it, so any
 * state owned here would be discarded the moment a photo is opened — `rememberSaveable` does
 * not survive its composable leaving the tree. Keeping it in the caller is what makes "back
 * from the viewer returns to the album you were in" work.
 *
 * @param onItemClick receives the album (bucket) id the item was opened from, or `null` when
 *   opened from the flat Pictures grid. The caller uses it to scope the viewer's pager to the
 *   same set the user was looking at, so swiping inside an album stays inside that album.
 */
@Composable
fun GalleryGridScreen(
    mediaItems: List<MediaItem>,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    openedAlbumId: String?,
    onOpenAlbum: (String?) -> Unit,
    columnCount: Int,
    onColumnCountChange: (Int) -> Unit,
    picturesGridState: LazyGridState,
    albumGridState: LazyGridState,
    restoreToItemId: Long?,
    onRestoreHandled: () -> Unit,
    onItemClick: (albumId: String?, index: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Derived from the list already in memory — no second MediaStore query.
    val albums = remember(mediaItems) { mediaItems.toAlbums() }
    val openedAlbum = remember(albums, openedAlbumId) {
        albums.firstOrNull { it.id == openedAlbumId }
    }
    val albumItems = remember(mediaItems, openedAlbumId) {
        openedAlbumId?.let { id -> mediaItems.filter { it.bucketId == id } }.orEmpty()
    }

    // Inside an album, back returns to the folder list rather than leaving the screen.
    BackHandler(enabled = openedAlbum != null) { onOpenAlbum(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                GalleryTab(
                    selected = selectedTab == TAB_PICTURES,
                    icon = Icons.Rounded.Image,
                    label = "Pictures"
                ) {
                    onTabChange(TAB_PICTURES)
                    onOpenAlbum(null)
                }
                GalleryTab(
                    selected = selectedTab == TAB_ALBUMS,
                    icon = Icons.Rounded.Folder,
                    label = "Albums"
                ) {
                    // Re-tapping Albums while inside a folder pops back to the folder list.
                    if (selectedTab == TAB_ALBUMS) onOpenAlbum(null)
                    onTabChange(TAB_ALBUMS)
                }
                GalleryTab(
                    selected = selectedTab == TAB_SEARCH,
                    icon = Icons.Rounded.Search,
                    label = "Search"
                ) {
                    onTabChange(TAB_SEARCH)
                    onOpenAlbum(null)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                selectedTab == TAB_ALBUMS && openedAlbum != null -> {
                    OneUIHeader(
                        title = openedAlbum.name,
                        subtitle = itemCountLabel(albumItems.size),
                        onBack = { onOpenAlbum(null) }
                    )
                    MediaGrid(
                        mediaItems = albumItems,
                        columnCount = columnCount,
                        onColumnCountChange = onColumnCountChange,
                        gridState = albumGridState,
                        restoreToItemId = restoreToItemId,
                        onRestoreHandled = onRestoreHandled,
                        onItemClick = { index -> onItemClick(openedAlbum.id, index) }
                    )
                }

                selectedTab == TAB_ALBUMS -> {
                    OneUIHeader(
                        title = "Albums",
                        subtitle = albumCountLabel(albums.size, mediaItems.size)
                    )
                    AlbumGrid(albums = albums, onAlbumClick = { onOpenAlbum(it.id) })
                }

                selectedTab == TAB_SEARCH -> {
                    OneUIHeader(title = "Search", subtitle = "Not implemented yet")
                    EmptyState("Search hasn't been built yet.")
                }

                else -> {
                    OneUIHeader(
                        title = "Pictures",
                        subtitle = itemCountLabel(mediaItems.size)
                    )
                    MediaGrid(
                        mediaItems = mediaItems,
                        columnCount = columnCount,
                        onColumnCountChange = onColumnCountChange,
                        gridState = picturesGridState,
                        restoreToItemId = restoreToItemId,
                        onRestoreHandled = onRestoreHandled,
                        onItemClick = { index -> onItemClick(null, index) }
                    )
                }
            }
        }
    }
}

private fun itemCountLabel(count: Int): String =
    if (count == 1) "1 item" else "$count items"

private fun albumCountLabel(albumCount: Int, itemCount: Int): String {
    val albumsPart = if (albumCount == 1) "1 album" else "$albumCount albums"
    return "$albumsPart · ${itemCountLabel(itemCount)}"
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.GalleryTab(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color.White,
            selectedTextColor = OneUIBlue,
            indicatorColor = OneUIBlue
        )
    )
}

@Composable
private fun AlbumGrid(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit
) {
    if (albums.isEmpty()) {
        EmptyState("No folders found.")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items = albums, key = { it.id }) { album ->
            AlbumCell(album = album, onClick = { onAlbumClick(album) })
        }
    }
}

@Composable
private fun AlbumCell(
    album: Album,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1F2125))
        ) {
            AsyncImage(
                model = album.coverUri,
                contentDescription = album.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.name,
            color = DarkTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${album.count}",
            color = DarkTextSecondary,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = message, color = DarkTextSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun MediaGrid(
    mediaItems: List<MediaItem>,
    columnCount: Int,
    onColumnCountChange: (Int) -> Unit,
    gridState: LazyGridState,
    restoreToItemId: Long?,
    onRestoreHandled: () -> Unit,
    onItemClick: (Int) -> Unit
) {
    if (mediaItems.isEmpty()) {
        EmptyState("Nothing here.")
        return
    }

    // Returning from the viewer: put the item the user was looking at back on screen. Only
    // scrolls when it is actually off-screen, so simply backing out of a photo you can still
    // see leaves the scroll position untouched rather than jolting it.
    LaunchedEffect(restoreToItemId, mediaItems, columnCount) {
        val targetId = restoreToItemId ?: return@LaunchedEffect
        val index = mediaItems.indexOfFirst { it.id == targetId }
        if (index >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            // Land a couple of rows above the item so it has context instead of being pinned
            // under the header.
            gridState.scrollToItem((index - columnCount * 2).coerceAtLeast(0))
        }
        onRestoreHandled()
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columnCount),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(columnCount) {
                // Runs in the Initial pass so a two-finger pinch is claimed before the grid's
                // own scrolling sees (and consumes) it. Zoom deltas arrive per event (each
                // ~1.0), so they are accumulated across the gesture.
                awaitEachGesture {
                    var cumulativeZoom = 1f
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            cumulativeZoom *= event.calculateZoom()
                            if (cumulativeZoom > 1.25f) {
                                if (columnCount > 1) onColumnCountChange(columnCount - 1)
                                cumulativeZoom = 1f
                            } else if (cumulativeZoom < 0.8f) {
                                if (columnCount < 5) onColumnCountChange(columnCount + 1)
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

@Composable
private fun OneUIHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (onBack != null) 8.dp else 24.dp,
                end = 24.dp,
                top = 20.dp,
                bottom = 20.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back to albums",
                    tint = DarkTextPrimary
                )
            }
            Spacer(modifier = Modifier.size(4.dp))
        }
        Column {
            Text(
                text = title,
                color = DarkTextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = DarkTextSecondary,
                fontSize = 14.sp
            )
        }
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
