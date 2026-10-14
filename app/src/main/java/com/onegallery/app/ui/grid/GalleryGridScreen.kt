package com.onegallery.app.ui.grid

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.onegallery.app.domain.AlbumSort
import com.onegallery.app.domain.MediaSort
import com.onegallery.app.domain.forAlbum
import com.onegallery.app.domain.sortedBy
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.domain.toAlbums
import com.onegallery.app.ui.theme.DarkBackground
import com.onegallery.app.ui.theme.DarkSurface
import com.onegallery.app.ui.theme.DarkTextPrimary
import com.onegallery.app.ui.theme.DarkTextSecondary
import com.onegallery.app.ui.theme.OneUIBlue
import com.onegallery.app.ui.gif.MAX_GIF_FRAMES
import com.onegallery.app.ui.gif.selectionBetween

/**
 * The album list, and the contents of whichever album is open.
 *
 * There are no tabs. The app opens on its albums and that is the only way in: the flat
 * "Pictures" grid and the unbuilt "Search" tab were removed at the owner's request, and with
 * them the bottom bar — which now appears only while selecting, as the selection's action bar.
 *
 * Browsing state ([openedAlbumId], [columnCount]) is **hoisted deliberately**.
 * The viewer replaces this screen in the composition rather than stacking on top of it, so any
 * state owned here would be discarded the moment a photo is opened — `rememberSaveable` does
 * not survive its composable leaving the tree. Keeping it in the caller is what makes "back
 * from the viewer returns to the album you were in" work.
 *
 * @param onItemClick receives the album (bucket) id the item was opened from. The caller uses
 *   it to scope the viewer's pager to the same set the user was looking at, so swiping inside
 *   an album stays inside that album.
 */
@Composable
fun GalleryGridScreen(
    mediaItems: List<MediaItem>,
    openedAlbumId: String?,
    onOpenAlbum: (String?) -> Unit,
    columnCount: Int,
    onColumnCountChange: (Int) -> Unit,
    albumGridState: LazyGridState,
    restoreToItemId: Long?,
    onRestoreHandled: () -> Unit,
    albumListGridState: LazyGridState,
    albumSort: AlbumSort,
    onAlbumSortChange: (AlbumSort) -> Unit,
    mediaSort: MediaSort,
    onMediaSortChange: (MediaSort) -> Unit,
    onItemClick: (albumId: String, index: Int) -> Unit,
    selectionMode: Boolean,
    selectedImageIds: Set<Long>,
    onSelectionModeChange: (Boolean) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    onCreateGif: (List<MediaItem>) -> Unit,
    onMove: (List<MediaItem>) -> Unit,
    modifier: Modifier = Modifier
) {
    // Derived from the list already in memory — no second MediaStore query.
    val albums = remember(mediaItems, albumSort) { mediaItems.toAlbums().sortedBy(albumSort) }
    val openedAlbum = remember(albums, openedAlbumId) {
        albums.firstOrNull { it.id == openedAlbumId }
    }
    // Everything is selectable: selection serves moving as well as GIFs. The GIF creator takes
    // the stills out of it and ignores the rest.
    val selectableItems = remember(mediaItems, openedAlbumId, mediaSort) {
        mediaItems.forAlbum(openedAlbumId).sortedBy(mediaSort)
    }
    val selectedItems = selectableItems.filter { it.id in selectedImageIds }
    val gifFrames = selectedItems.filter { it.canBeGifFrame }
    // No ceiling on the selection itself — moving 800 photos is a reasonable thing to do. The
    // GIF limit is enforced where a GIF is made: its button, the creator and the renderer.
    val changeSelection: (Set<Long>) -> Unit = onSelectionChange
    val clearSelection = { onSelectionChange(emptySet()); onSelectionModeChange(false) }
    LaunchedEffect(selectableItems) {
        val available = selectableItems.mapTo(hashSetOf()) { it.id }
        if (selectedImageIds.any { it !in available }) onSelectionChange(selectedImageIds.intersect(available))
    }

    // Inside an album, back returns to the folder list rather than leaving the screen.
    BackHandler(enabled = openedAlbum != null) { onOpenAlbum(null) }
    BackHandler(enabled = selectionMode) { clearSelection() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        bottomBar = {
            if (selectionMode) {
                NavigationBar(containerColor = DarkSurface) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(enabled = selectedItems.size == 2, onClick = {
                            changeSelection(selectionBetween(selectableItems.map { it.id }, selectedImageIds))
                        }) { Text("Select between") }
                        TextButton(enabled = selectedItems.isNotEmpty(), onClick = { onMove(selectedItems) }) {
                            Text(if (selectedItems.isEmpty()) "Move" else "Move (${selectedItems.size})")
                        }
                        TextButton(
                            enabled = gifFrames.size in 2..MAX_GIF_FRAMES,
                            onClick = { onCreateGif(gifFrames) }
                        ) {
                            Text(
                                if (gifFrames.size > MAX_GIF_FRAMES) "GIF: $MAX_GIF_FRAMES max"
                                else "GIF (${gifFrames.size})"
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // Drilling into / out of an album slides in the direction of travel. contentKey keeps a
        // live MediaStore update (which changes the Album object's counts) from being mistaken
        // for a navigation and re-running the transition.
        AnimatedContent(
            targetState = GridDestination(album = openedAlbum),
            contentKey = { it.key },
            transitionSpec = {
                val direction = when {
                    targetState.album != null && initialState.album == null -> 1
                    targetState.album == null && initialState.album != null -> -1
                    else -> 0
                }
                (fadeIn(tween(DESTINATION_ENTER_MS)) +
                    slideInHorizontally(tween(DESTINATION_ENTER_MS)) { direction * it / 8 })
                    .togetherWith(
                        fadeOut(tween(DESTINATION_EXIT_MS)) +
                            slideOutHorizontally(tween(DESTINATION_EXIT_MS)) { -direction * it / 8 }
                    )
            },
            label = "grid-destination",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { destination ->
            val album = destination.album

            Column(modifier = Modifier.fillMaxSize()) {
                when {
                    album != null -> {
                        // Built from the *destination's* album, not openedAlbumId: this block
                        // stays composed while it animates out, by which time openedAlbumId is
                        // already null and the grid would flash empty.
                        // Same expression the viewer uses to build its pager list — see
                        // MediaItem.forAlbum.
                        val albumItems = remember(mediaItems, album.id, mediaSort) {
                            mediaItems.forAlbum(album.id).sortedBy(mediaSort)
                        }
                        if (selectionMode) SelectionHeader(selectedItems.size, clearSelection) else OneUIHeader(
                            title = album.name,
                            subtitle = itemCountLabel(albumItems.size),
                            onBack = { onOpenAlbum(null) },
                            trailing = {
                                IconButton(onClick = { onSelectionModeChange(true) }) {
                                    Icon(Icons.Rounded.Checklist, "Select items", tint = DarkTextPrimary)
                                }
                                SortMenu(
                                    current = mediaSort,
                                    options = MediaSort.entries,
                                    label = { it.label },
                                    contentDescription = "Sort photos and videos",
                                    onSelect = onMediaSortChange
                                )
                            }
                        )
                        MediaGrid(
                            mediaItems = albumItems,
                            columnCount = columnCount,
                            onColumnCountChange = onColumnCountChange,
                            gridState = albumGridState,
                            restoreToItemId = restoreToItemId,
                            onRestoreHandled = onRestoreHandled,
                            selectionMode = selectionMode,
                            selectedIds = selectedImageIds,
                            onSelectionChange = changeSelection,
                            onSelectionStart = { onSelectionModeChange(true) },
                            onItemClick = { index -> onItemClick(album.id, index) }
                        )
                    }

                    else -> {
                        OneUIHeader(
                            title = "Albums",
                            subtitle = albumCountLabel(albums.size, mediaItems.size),
                            trailing = {
                                SortMenu(
                                    current = albumSort,
                                    options = AlbumSort.entries,
                                    label = { it.label },
                                    contentDescription = "Sort albums",
                                    onSelect = onAlbumSortChange
                                )
                            }
                        )
                        AlbumGrid(
                            albums = albums,
                            gridState = albumListGridState,
                            onAlbumClick = { onOpenAlbum(it.id) }
                        )
                    }
                }
            }
        }
    }
}

/** Where the grid screen is: inside [album], or on the album list when it is null. */
private data class GridDestination(val album: Album?) {
    val key: String get() = if (album != null) "album:${album.id}" else "albums"
}

private const val DESTINATION_ENTER_MS = 200
private const val DESTINATION_EXIT_MS = 140

private fun itemCountLabel(count: Int): String =
    if (count == 1) "1 item" else "$count items"

private fun albumCountLabel(albumCount: Int, itemCount: Int): String {
    val albumsPart = if (albumCount == 1) "1 album" else "$albumCount albums"
    return "$albumsPart · ${itemCountLabel(itemCount)}"
}

@Composable
private fun AlbumGrid(
    albums: List<Album>,
    gridState: LazyGridState,
    onAlbumClick: (Album) -> Unit
) {
    if (albums.isEmpty()) {
        EmptyState("No folders found.")
        return
    }

    LazyVerticalGrid(
        state = gridState,
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
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onSelectionStart: () -> Unit,
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

    val latestColumnCount by rememberUpdatedState(columnCount)
    val latestOnColumnCountChange by rememberUpdatedState(onColumnCountChange)
    val dragSelection = imageDragSelection(gridState, mediaItems, selectedIds, onSelectionChange,
        onSelectionStart)

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columnCount),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxSize()
            .then(dragSelection)
            .pointerInput(Unit) {
                // Runs in the Initial pass so a two-finger pinch is claimed before the grid's
                // own scrolling sees (and consumes) it. Zoom deltas arrive per event (each
                // ~1.0), so they are accumulated across the gesture.
                //
                // Keyed on Unit, reading the column count through rememberUpdatedState: keying
                // on columnCount restarted this block on the first step of a pinch, and the
                // restarted block waits for a fresh finger-down — so the rest of that pinch
                // was no longer consumed and leaked into the grid as a two-finger scroll.
                awaitEachGesture {
                    var cumulativeZoom = 1f
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            cumulativeZoom *= event.calculateZoom()
                            if (cumulativeZoom > 1.25f) {
                                if (latestColumnCount > 1) latestOnColumnCountChange(latestColumnCount - 1)
                                cumulativeZoom = 1f
                            } else if (cumulativeZoom < 0.8f) {
                                if (latestColumnCount < 5) latestOnColumnCountChange(latestColumnCount + 1)
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
                onClick = {
                    if (selectionMode) {
                        onSelectionChange(
                            if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                        )
                    } else onItemClick(index)
                },
                selectionMode = selectionMode,
                selected = item.id in selectedIds,
                // Cells glide to their new slot when the column count or sort changes, and
                // fade when MediaStore adds/removes an item, instead of the grid snapping.
                modifier = Modifier.animateItem()
            )
        }
    }
}

@Composable
private fun SelectionHeader(count: Int, onClose: () -> Unit) {
    OneUIHeader("$count selected", "Hold & drag, or select two endpoints and tap Select between", trailing = {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Cancel selection", tint = Color.White) }
    })
}

@Composable
private fun OneUIHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
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
        Column(modifier = Modifier.weight(1f)) {
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
        trailing?.invoke()
    }
}

@Composable
private fun <T> SortMenu(
    current: T,
    options: List<T>,
    label: (T) -> String,
    contentDescription: String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Sort,
                contentDescription = contentDescription,
                tint = DarkTextPrimary
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = DarkSurface
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    text = {
                        Text(
                            text = label(option),
                            color = if (option == current) OneUIBlue else DarkTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (option == current) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    trailingIcon = {
                        // Reserve the slot even when unselected so labels stay aligned
                        if (option == current) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = OneUIBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.size(18.dp))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MediaGridCell(
    mediaItem: MediaItem,
    onClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
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
        if (selectionMode) {
            Box(Modifier.fillMaxSize().border(if (selected) 3.dp else 1.dp,
                if (selected) OneUIBlue else Color(0x55FFFFFF)))
            Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                if (selected) "Selected" else "Not selected",
                tint = if (selected) OneUIBlue else Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp)).size(24.dp))
        }

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
