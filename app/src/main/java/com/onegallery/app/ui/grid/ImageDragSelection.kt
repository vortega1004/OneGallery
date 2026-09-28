package com.onegallery.app.ui.grid

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.domain.MediaType
import kotlinx.coroutines.delay

internal val MediaItem.canBeGifFrame: Boolean
    get() = mediaType == MediaType.IMAGE || mediaType == MediaType.MOTION_PHOTO

/** Long-press, sweep a range, and dwell at either edge to scroll beyond the viewport. */
@Composable
internal fun imageDragSelection(
    grid: LazyGridState,
    items: List<MediaItem>,
    selected: Set<Long>,
    onSelection: (Set<Long>) -> Unit,
    onStart: () -> Unit
): Modifier {
    val latestItems by rememberUpdatedState(items)
    val latestSelected by rememberUpdatedState(selected)
    val latestOnSelection by rememberUpdatedState(onSelection)
    val latestOnStart by rememberUpdatedState(onStart)
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var anchorId by remember { mutableStateOf<Long?>(null) }
    var baseline by remember { mutableStateOf(emptySet<Long>()) }
    val edge = with(LocalDensity.current) { 64.dp.toPx() }

    fun indexAt(point: Offset): Int? {
        val x = point.x.coerceIn(0f, (viewport.width - 1).coerceAtLeast(0).toFloat())
        val y = point.y.coerceIn(0f, (viewport.height - 1).coerceAtLeast(0).toFloat())
        return grid.layoutInfo.visibleItemsInfo.firstOrNull {
            x >= it.offset.x && x < it.offset.x + it.size.width &&
                y >= it.offset.y && y < it.offset.y + it.size.height
        }?.index
    }
    fun extend(point: Offset) {
        val anchor = latestItems.indexOfFirst { it.id == anchorId }
        val end = indexAt(point) ?: return
        if (anchor < 0 || end !in latestItems.indices) return
        val range = latestItems.subList(minOf(anchor, end), maxOf(anchor, end) + 1)
        val selection = baseline + range.map { it.id }
        if (selection != latestSelected) latestOnSelection(selection)
    }
    // Reads current pointer and callbacks instead of restarting the gesture on each selection.
    LaunchedEffect(anchorId) {
        if (anchorId == null) return@LaunchedEffect
        while (true) {
            val point = pointer
            if (point != null && viewport.height > 0) {
                val amount = when {
                    point.y < edge -> -((edge - point.y) / edge).coerceIn(0f, 1f) * 28f
                    point.y > viewport.height - edge -> ((point.y - viewport.height + edge) / edge).coerceIn(0f, 1f) * 28f
                    else -> 0f
                }
                if (amount != 0f) { grid.scrollBy(amount); extend(point) }
            }
            delay(16)
        }
    }
    return Modifier.onSizeChanged { viewport = it }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val item = indexAt(held.position)?.let { latestItems.getOrNull(it) }
            if (item == null) return@awaitEachGesture
            baseline = latestSelected
            anchorId = item.id
            pointer = held.position
            latestOnStart()
            latestOnSelection(baseline + item.id)
            try {
                // Claim moves BEFORE LazyGrid's scroll detector sees them. A Main-pass drag
                // detector is cancelled by that child as soon as the long press changes UI.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    pointer = change.position
                    extend(change.position)
                }
            } finally {
                anchorId = null
                pointer = null
            }
        }
    }
}
