package com.onegallery.app.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.onegallery.app.domain.Album
import com.onegallery.app.domain.AlbumEntry
import com.onegallery.app.ui.theme.DarkSurface
import com.onegallery.app.ui.theme.DarkTextPrimary
import com.onegallery.app.ui.theme.DarkTextSecondary
import com.onegallery.app.ui.theme.OneUIBlue
import kotlinx.coroutines.channels.Channel

/**
 * The album list — or the inside of one group — as a grid of tiles the user can rearrange.
 *
 * **Rearranging.** Press and hold a tile, then drag: it lifts, the others make room as it
 * passes over them, and where it is dropped is where it stays ([onReorder] gets the new order
 * once, on the drop). Nothing else ever moves a tile.
 *
 * While [selecting], a tap ticks a tile instead of opening it and holding does nothing; that
 * mode is for gathering albums into a group.
 */
@Composable
internal fun AlbumEntryGrid(
    entries: List<AlbumEntry>,
    gridState: LazyGridState,
    selecting: Boolean,
    selectedKeys: Set<String>,
    onToggleSelected: (String) -> Unit,
    onOpen: (AlbumEntry) -> Unit,
    onReorder: (List<String>) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyState("No folders found.")
        return
    }

    // What is on screen while a drag is in progress. The saved order only changes on the drop;
    // writing it on every swap would be a disk write per tile passed.
    var working by remember(entries) { mutableStateOf(entries) }
    val latestEntries by rememberUpdatedState(entries)
    val latestOnReorder by rememberUpdatedState(onReorder)
    val haptic = LocalHapticFeedback.current
    val drag = remember(gridState) { GridDragState(gridState) }

    LaunchedEffect(drag) {
        // Dragging a tile against the top or bottom edge scrolls the list under it
        for (delta in drag.scrollRequests) gridState.scrollBy(delta)
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        // Vertical only. The sideways margin is ordinary padding on the grid itself, so that a
        // finger position and a tile's reported position are measured from the same edge.
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .pointerInput(selecting) {
                if (selecting) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    if (!drag.start(held.position)) return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    var finished = false
                    try {
                        // Moves are claimed in the Initial pass, before the grid's own scrolling
                        // sees them: a drag detector on the ordinary pass loses the gesture to
                        // the grid the moment the finger travels (same as the drag-selection).
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } > 1) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                finished = true
                                break
                            }
                            val amount = change.positionChange()
                            change.consume()
                            drag.dragBy(amount) { from, to ->
                                working = working.toMutableList().apply { add(to, removeAt(from)) }
                            }
                        }
                    } finally {
                        drag.stop()
                        val changed = working.map { it.key } != latestEntries.map { it.key }
                        // A second finger, or the gesture being taken away, drops the tile back
                        if (finished && changed) latestOnReorder(working.map { it.key })
                        else if (!finished) working = latestEntries
                    }
                }
            }
    ) {
        itemsIndexed(items = working, key = { _, entry -> entry.key }) { index, entry ->
            val lifted = index == drag.draggedIndex
            AlbumTile(
                entry = entry,
                selecting = selecting,
                selected = entry.key in selectedKeys,
                onClick = { if (selecting) onToggleSelected(entry.key) else onOpen(entry) },
                modifier = if (lifted) {
                    // Follows the finger, above its neighbours. Not animated: animateItem would
                    // chase the finger with a spring.
                    Modifier
                        .zIndex(1f)
                        .graphicsLayer {
                            translationX = drag.draggedOffset.x
                            translationY = drag.draggedOffset.y
                            scaleX = 1.04f
                            scaleY = 1.04f
                            alpha = 0.92f
                        }
                } else {
                    Modifier.animateItem()
                }
            )
        }
    }
}

@Composable
private fun AlbumTile(
    entry: AlbumEntry,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1F2125))
        ) {
            when (entry) {
                is AlbumEntry.Single -> AsyncImage(
                    model = entry.album.coverUri,
                    contentDescription = entry.album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                is AlbumEntry.Group -> GroupMosaic(entry.albums, entry.name)
            }
            if (selecting) {
                if (selected) Box(Modifier.fillMaxSize().border(3.dp, OneUIBlue, RoundedCornerShape(16.dp)))
                Icon(
                    imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) OneUIBlue else Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0x99000000), RoundedCornerShape(14.dp))
                        .size(28.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = when (entry) {
                is AlbumEntry.Single -> entry.album.name
                is AlbumEntry.Group -> entry.name
            },
            color = DarkTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = when (entry) {
                is AlbumEntry.Single -> "${entry.album.count}"
                is AlbumEntry.Group ->
                    "${entry.albums.size} ${if (entry.albums.size == 1) "album" else "albums"} · ${entry.itemCount}"
            },
            color = DarkTextSecondary,
            fontSize = 12.sp
        )
    }
}

/** A group's tile: the covers of its first four albums, so it reads as "several albums". */
@Composable
private fun GroupMosaic(albums: List<Album>, name: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (row in 0..1) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (column in 0..1) {
                    val album = albums.getOrNull(row * 2 + column)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2A2D33))
                    ) {
                        if (album != null) {
                            AsyncImage(
                                model = album.coverUri,
                                contentDescription = if (row == 0 && column == 0) name else null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Asks for a group's name. [onConfirm] is only called with something other than blank. */
@Composable
internal fun GroupNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        titleContentColor = DarkTextPrimary,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                singleLine = true,
                label = { Text("Group name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Follows one tile of a lazy grid while it is dragged, and says when it has moved far enough to
 * change places with another.
 *
 * The grid does the rest: the caller reorders its list when [dragBy] reports a swap, and the
 * grid — keyed by item — slides the displaced tiles to their new slots. The lifted tile itself
 * is drawn at [draggedOffset] from wherever the grid currently lays it out, so it stays under
 * the finger through every swap.
 */
internal class GridDragState(private val grid: LazyGridState) {

    /** Index of the lifted tile in the caller's list, or null when nothing is being dragged. */
    var draggedIndex by mutableStateOf<Int?>(null)
        private set

    val isDragging: Boolean get() = draggedIndex != null

    /** Scroll distances asked for while a tile is held against the top or bottom edge. */
    val scrollRequests = Channel<Float>(Channel.CONFLATED)

    private var fingerTravel by mutableStateOf(Offset.Zero)
    private var liftedFrom by mutableStateOf(Offset.Zero)

    private val draggedInfo: LazyGridItemInfo?
        get() = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggedIndex }

    /** How far to draw the lifted tile from its laid-out slot. */
    val draggedOffset: Offset
        get() = draggedInfo?.let { liftedFrom + fingerTravel - it.topLeft() } ?: Offset.Zero

    /** Lifts the tile under [position] (in the grid's own coordinates); false if there is none. */
    fun start(position: Offset): Boolean {
        // Tile positions are measured from the end of the leading content padding
        val x = position.x
        val y = position.y + grid.layoutInfo.viewportStartOffset
        val hit = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.contains(x, y) } ?: return false
        draggedIndex = hit.index
        liftedFrom = hit.topLeft()
        fingerTravel = Offset.Zero
        return true
    }

    fun dragBy(amount: Offset, onSwap: (from: Int, to: Int) -> Unit) {
        val dragged = draggedInfo ?: return
        fingerTravel += amount
        val topLeft = liftedFrom + fingerTravel
        val centerX = topLeft.x + dragged.size.width / 2f
        val centerY = topLeft.y + dragged.size.height / 2f
        val target = grid.layoutInfo.visibleItemsInfo.firstOrNull {
            it.index != dragged.index && it.contains(centerX, centerY)
        }
        if (target != null) {
            // Moving the first visible tile would otherwise make the grid scroll to follow it
            if (dragged.index == grid.firstVisibleItemIndex || target.index == grid.firstVisibleItemIndex) {
                grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
            }
            onSwap(dragged.index, target.index)
            draggedIndex = target.index
            return
        }
        val bottom = topLeft.y + dragged.size.height
        val overshoot = when {
            amount.y > 0 -> (bottom - grid.layoutInfo.viewportEndOffset).coerceAtLeast(0f)
            amount.y < 0 -> (topLeft.y - grid.layoutInfo.viewportStartOffset).coerceAtMost(0f)
            else -> 0f
        }
        if (overshoot != 0f) scrollRequests.trySend(overshoot * EDGE_SCROLL_FRACTION)
    }

    fun stop() {
        draggedIndex = null
        fingerTravel = Offset.Zero
        liftedFrom = Offset.Zero
    }

    private fun LazyGridItemInfo.topLeft() = Offset(offset.x.toFloat(), offset.y.toFloat())

    private fun LazyGridItemInfo.contains(x: Float, y: Float): Boolean =
        x >= offset.x && x < offset.x + size.width && y >= offset.y && y < offset.y + size.height

    private companion object {
        /** A tile pushed past the edge scrolls a share of the overshoot per drag event, not all of it. */
        const val EDGE_SCROLL_FRACTION = 0.35f
    }
}
