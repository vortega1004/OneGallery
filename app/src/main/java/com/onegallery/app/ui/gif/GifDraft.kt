package com.onegallery.app.ui.gif

import com.onegallery.app.ui.editor.PhotoEditState
import kotlin.math.roundToInt

enum class GifLoop { FORWARD, REVERSE, BOOMERANG }

/** Endpoints appear once per cycle: A B C B | A B C B, not A B C C B A. */
internal fun gifFrameOrder(count: Int, loop: GifLoop): List<Int> = when {
    count <= 0 -> emptyList()
    loop == GifLoop.REVERSE -> (count - 1 downTo 0).toList()
    loop == GifLoop.BOOMERANG && count > 2 -> (0 until count).toList() + (count - 2 downTo 1).toList()
    else -> (0 until count).toList()
}

/** Range selection uses displayed order, never MediaStore IDs or the order of taps. */
internal fun selectionBetween(orderedIds: List<Long>, selected: Set<Long>): Set<Long> {
    if (selected.size != 2) return selected
    val positions = orderedIds.indices.filter { orderedIds[it] in selected }
    if (positions.size != 2) return selected
    return orderedIds.subList(positions.first(), positions.last() + 1).toSet()
}

data class GifFrame(val key: Long, val uri: String, val name: String)

data class GifDraft(
    val frames: List<GifFrame>,
    val activeIndex: Int = 0,
    val loop: GifLoop = GifLoop.FORWARD,
    // GIF timing is quantized to hundredths of a second. Preview uses this same value.
    val delayCentiseconds: Int = 15,
    val longEdge: Int = 720,
    val canvasAspect: Float = 1f,
    val edit: PhotoEditState = PhotoEditState()
) {
    val order: List<Int> get() = gifFrameOrder(frames.size, loop)
    val durationMs: Long get() = order.size * delayCentiseconds * 10L
    val outputSize: Pair<Int, Int>
        get() {
            val (w, h) = gifCanvasSize(canvasAspect, longEdge)
            val width = if (edit.swapsDimensions) h else w
            val height = if (edit.swapsDimensions) w else h
            val left = (edit.crop.left * width).roundToInt().coerceIn(0, width - 1)
            val top = (edit.crop.top * height).roundToInt().coerceIn(0, height - 1)
            return (edit.crop.width * width).roundToInt().coerceIn(1, width - left) to
                (edit.crop.height * height).roundToInt().coerceIn(1, height - top)
        }

    fun duplicateActive(newKey: Long): GifDraft {
        if (frames.isEmpty()) return this
        val result = frames.toMutableList()
        result.add(activeIndex + 1, frames[activeIndex].copy(key = newKey))
        return copy(frames = result, activeIndex = activeIndex + 1)
    }

    fun removeActive(): GifDraft {
        if (frames.size <= 2) return this
        val result = frames.toMutableList().apply { removeAt(activeIndex) }
        return copy(frames = result, activeIndex = activeIndex.coerceAtMost(result.lastIndex))
    }

    fun moveActive(delta: Int): GifDraft {
        val target = activeIndex + delta
        if (target !in frames.indices) return this
        val result = frames.toMutableList()
        result.add(target, result.removeAt(activeIndex))
        return copy(frames = result, activeIndex = target)
    }
}

internal fun gifCanvasSize(aspect: Float, longEdge: Int): Pair<Int, Int> =
    if (aspect >= 1f) longEdge to (longEdge / aspect).roundToInt().coerceAtLeast(1)
    else (longEdge * aspect).roundToInt().coerceAtLeast(1) to longEdge
