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
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.onegallery.app.domain.AlbumEntry
import com.onegallery.app.domain.AlbumLayout
import com.onegallery.app.domain.AlbumSort
import com.onegallery.app.domain.arrangeAlbums
import com.onegallery.app.domain.arrangedBy
import com.onegallery.app.domain.grouping
import com.onegallery.app.domain.removingFromGroup
import com.onegallery.app.domain.renamingGroup
import com.onegallery.app.domain.ungrouping
import com.onegallery.app.domain.withGroupOrder
import com.onegallery.app.domain.withOrder
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
    albumLayout: AlbumLayout?,
    onAlbumLayoutChange: (AlbumLayout) -> Unit,
    /** The order the album list starts in, the first time, before the user has arranged it. */
    initialAlbumSort: AlbumSort,
    openedGroupId: String?,
    onOpenGroup: (String?) -> Unit,
    groupGridState: LazyGridState,
    mediaSort: MediaSort,
    onMediaSortChange: (MediaSort) -> Unit,
    onItemClick: (albumId: String, index: Int) -> Unit,
    selectionMode: Boolean,
    selectedImageIds: Set<Long>,
    onSelectionModeChange: (Boolean) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    onCreateGif: (List<MediaItem>) -> Unit,
    onMove: (List<MediaItem>) -> Unit,
    onTrash: (List<MediaItem>) -> Unit,
    onOpenTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Derived from the list already in memory — no second MediaStore query.
    val albums = remember(mediaItems) { mediaItems.toAlbums() }
    val openedAlbum = remember(albums, openedAlbumId) {
        albums.firstOrNull { it.id == openedAlbumId }
    }

    // --- The album list, as the user arranged it ------------------------------------------
    // Until an arrangement exists the list shows in the old sort order, and that order is
    // then saved as the first arrangement — from which point only the user changes it.
    val layout = albumLayout ?: AlbumLayout()
    val unarranged = layout.order.isEmpty() && layout.groups.isEmpty()
    val entries: List<AlbumEntry> = remember(albums, layout, initialAlbumSort) {
        if (unarranged) albums.sortedBy(initialAlbumSort).map { AlbumEntry.Single(it) }
        else arrangeAlbums(albums, layout)
    }
    LaunchedEffect(albumLayout, albums.isNotEmpty()) {
        if (albumLayout != null && unarranged && albums.isNotEmpty()) {
            onAlbumLayoutChange(layout.withOrder(entries.map { it.key }))
        }
    }
    val openedGroup = remember(entries, openedGroupId) {
        entries.firstOrNull { it is AlbumEntry.Group && it.id == openedGroupId } as? AlbumEntry.Group
    }
    // The group was taken apart, or its last album left, while it was open
    LaunchedEffect(openedGroup == null, openedGroupId) {
        if (openedGroupId != null && openedGroup == null && albumLayout != null && albums.isNotEmpty()) {
            onOpenGroup(null)
        }
    }

    // Ticking albums, to group them or take them out of a group. Tile keys, not ids.
    var albumSelecting by rememberSaveable { mutableStateOf(false) }
    var selectedAlbumKeys by rememberSaveable { mutableStateOf(listOf<String>()) }
    val clearAlbumSelection = { albumSelecting = false; selectedAlbumKeys = emptyList() }
    val toggleAlbumSelected: (String) -> Unit = { key ->
        selectedAlbumKeys = if (key in selectedAlbumKeys) selectedAlbumKeys - key else selectedAlbumKeys + key
    }
    // null = no dialog; otherwise what the name is for
    var namingGroup by remember { mutableStateOf<GroupNaming?>(null) }
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

    // Back climbs one level: album -> its group (if it was opened from one) -> the album list.
    // Later handlers win, so the most specific is declared last.
    BackHandler(enabled = openedGroup != null && openedAlbum == null) { onOpenGroup(null) }
    BackHandler(enabled = openedAlbum != null) { onOpenAlbum(null) }
    BackHandler(enabled = selectionMode) { clearSelection() }
    BackHandler(enabled = albumSelecting) { clearAlbumSelection() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        bottomBar = {
            if (albumSelecting && openedAlbum == null) {
                NavigationBar(containerColor = DarkSurface) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val inGroup = openedGroup
                        if (inGroup != null) {
                            TextButton(enabled = selectedAlbumKeys.isNotEmpty(), onClick = {
                                onAlbumLayoutChange(
                                    layout.removingFromGroup(
                                        groupId = inGroup.id,
                                        albumIds = selectedAlbumKeys.map { it.removePrefix("a:") }.toSet(),
                                        visibleOrder = entries.map { it.key }
                                    )
                                )
                                clearAlbumSelection()
                            }) {
                                Text(
                                    if (selectedAlbumKeys.isEmpty()) "Take out of group"
                                    else "Take out of group (${selectedAlbumKeys.size})"
                                )
                            }
                        } else {
                            val groupsPicked = selectedAlbumKeys.filter { it.startsWith("g:") }
                            TextButton(enabled = selectedAlbumKeys.size >= 2, onClick = {
                                // Adding to an existing group keeps its name unless changed
                                val existing = entries.firstOrNull { it.key in groupsPicked } as? AlbumEntry.Group
                                namingGroup = GroupNaming.New(selectedAlbumKeys.toSet(), existing?.name.orEmpty())
                            }) {
                                Text(if (selectedAlbumKeys.size < 2) "Group" else "Group (${selectedAlbumKeys.size})")
                            }
                            TextButton(enabled = groupsPicked.isNotEmpty(), onClick = {
                                onAlbumLayoutChange(
                                    layout.ungrouping(
                                        groupsPicked.map { it.removePrefix("g:") }.toSet(),
                                        entries.map { it.key }
                                    )
                                )
                                clearAlbumSelection()
                            }) { Text("Ungroup") }
                        }
                    }
                }
            } else if (selectionMode) {
                NavigationBar(containerColor = DarkSurface) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(enabled = selectedItems.size == 2, onClick = {
                            changeSelection(selectionBetween(selectableItems.map { it.id }, selectedImageIds))
                        }) { Text("Between") }
                        TextButton(enabled = selectedItems.isNotEmpty(), onClick = { onTrash(selectedItems) }) {
                            Text(if (selectedItems.isEmpty()) "Trash" else "Trash (${selectedItems.size})")
                        }
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
            targetState = GridDestination(album = openedAlbum, group = openedGroup),
            contentKey = { it.key },
            transitionSpec = {
                val direction = (targetState.depth - initialState.depth).coerceIn(-1, 1)
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
            val group = destination.group

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

                    group != null -> {
                        // The albums of one group. The tiles come from the live list, not from
                        // the destination, so a reorder or a removal shows at once; the
                        // destination's copy is what is drawn while this animates out.
                        val shown = (openedGroup ?: group).albums.map { AlbumEntry.Single(it) }
                        if (albumSelecting) {
                            SelectionHeader(selectedAlbumKeys.size, "Tap albums to take them out of this group", clearAlbumSelection)
                        } else {
                            OneUIHeader(
                                title = group.name,
                                subtitle = albumCountLabel(shown.size, shown.sumOf { it.album.count }),
                                onBack = { onOpenGroup(null) },
                                trailing = {
                                    IconButton(onClick = { albumSelecting = true }) {
                                        Icon(Icons.Rounded.Checklist, "Select albums", tint = DarkTextPrimary)
                                    }
                                    GroupMenu(
                                        onRename = { namingGroup = GroupNaming.Rename(group.id, group.name) },
                                        onUngroup = {
                                            onAlbumLayoutChange(layout.ungrouping(setOf(group.id), entries.map { it.key }))
                                        }
                                    )
                                }
                            )
                        }
                        AlbumEntryGrid(
                            entries = shown,
                            gridState = groupGridState,
                            selecting = albumSelecting,
                            selectedKeys = selectedAlbumKeys.toSet(),
                            onToggleSelected = toggleAlbumSelected,
                            onOpen = { entry -> if (entry is AlbumEntry.Single) onOpenAlbum(entry.album.id) },
                            onReorder = { keys ->
                                onAlbumLayoutChange(
                                    layout.withGroupOrder(group.id, keys.map { it.removePrefix("a:") })
                                )
                            }
                        )
                    }

                    else -> {
                        if (albumSelecting) {
                            SelectionHeader(selectedAlbumKeys.size, "Tap albums to put them in a group", clearAlbumSelection)
                        } else {
                            OneUIHeader(
                                title = "Albums",
                                subtitle = albumCountLabel(albums.size, mediaItems.size),
                                trailing = {
                                    IconButton(onClick = { albumSelecting = true }) {
                                        Icon(Icons.Rounded.Checklist, "Select albums", tint = DarkTextPrimary)
                                    }
                                    IconButton(onClick = onOpenTrash) {
                                        Icon(Icons.Rounded.DeleteOutline, "Trash", tint = DarkTextPrimary)
                                    }
                                    // A one-off: puts the tiles in that order now. It does not
                                    // stay "sorted" — the order is the user's again afterwards.
                                    SortMenu(
                                        current = null,
                                        options = AlbumSort.entries,
                                        label = { it?.label.orEmpty() },
                                        contentDescription = "Arrange albums by",
                                        onSelect = { sort ->
                                            if (sort != null) {
                                                onAlbumLayoutChange(layout.withOrder(entries.arrangedBy(sort).map { it.key }))
                                            }
                                        }
                                    )
                                }
                            )
                        }
                        AlbumEntryGrid(
                            entries = entries,
                            gridState = albumListGridState,
                            selecting = albumSelecting,
                            selectedKeys = selectedAlbumKeys.toSet(),
                            onToggleSelected = toggleAlbumSelected,
                            onOpen = { entry ->
                                when (entry) {
                                    is AlbumEntry.Single -> onOpenAlbum(entry.album.id)
                                    is AlbumEntry.Group -> onOpenGroup(entry.id)
                                }
                            },
                            onReorder = { keys -> onAlbumLayoutChange(layout.withOrder(keys)) }
                        )
                    }
                }
            }
        }
    }

    namingGroup?.let { naming ->
        GroupNameDialog(
            title = if (naming is GroupNaming.New) "Group albums" else "Rename group",
            initialName = naming.initialName,
            confirmLabel = if (naming is GroupNaming.New) "Group" else "Rename",
            onConfirm = { name ->
                when (naming) {
                    is GroupNaming.New -> {
                        onAlbumLayoutChange(
                            layout.grouping(
                                keys = naming.keys,
                                name = name,
                                newGroupId = System.currentTimeMillis().toString(36),
                                visibleOrder = entries.map { it.key }
                            )
                        )
                        clearAlbumSelection()
                    }
                    is GroupNaming.Rename -> onAlbumLayoutChange(layout.renamingGroup(naming.groupId, name))
                }
                namingGroup = null
            },
            onDismiss = { namingGroup = null }
        )
    }
}

/** What the group-name dialog is open for. */
private sealed interface GroupNaming {
    val initialName: String

    data class New(val keys: Set<String>, override val initialName: String) : GroupNaming
    data class Rename(val groupId: String, override val initialName: String) : GroupNaming
}

@Composable
private fun GroupMenu(onRename: () -> Unit, onUngroup: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, "Group options", tint = DarkTextPrimary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = DarkSurface) {
            DropdownMenuItem(
                text = { Text("Rename group", color = DarkTextPrimary) },
                onClick = { expanded = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text("Ungroup", color = DarkTextPrimary) },
                onClick = { expanded = false; onUngroup() }
            )
        }
    }
}

/** Where the grid screen is: inside [album], inside [group], or on the album list. */
private data class GridDestination(val album: Album?, val group: AlbumEntry.Group?) {
    val key: String
        get() = when {
            album != null -> "album:${album.id}"
            group != null -> "group:${group.id}"
            else -> "albums"
        }

    /** How far in: decides which way the transition slides. */
    val depth: Int get() = if (album != null) 2 else if (group != null) 1 else 0
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
internal fun EmptyState(message: String) {
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
private fun SelectionHeader(
    count: Int,
    onClose: () -> Unit
) = SelectionHeader(count, "Hold & drag, or select two endpoints and tap Between", onClose)

@Composable
private fun SelectionHeader(count: Int, hint: String, onClose: () -> Unit) {
    OneUIHeader("$count selected", hint, trailing = {
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
