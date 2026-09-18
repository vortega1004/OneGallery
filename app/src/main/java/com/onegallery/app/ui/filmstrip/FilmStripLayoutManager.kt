package com.onegallery.app.ui.filmstrip

import android.content.Context
import android.graphics.PointF
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Direct port of Samsung Gallery's internal FilmStripLayoutManager:
 * - Dynamic center alignment: items dynamically offset so that the active item is centered
 * - Midpoint magnification: items within the center zone expand in width & height
 * - Snap-to-center physics via LinearSnapHelper
 */
class FilmStripLayoutManager(
    private val context: Context,
    private val defaultItemWidth: Int,
    private val defaultItemHeight: Int,
    private val expandedScale: Float = 1.25f
) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider {

    private var horizontalOffset = 0
    private val maxItemWidth = (defaultItemWidth * expandedScale).toInt()
    private val maxItemHeight = (defaultItemHeight * expandedScale).toInt()

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams {
        return RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun canScrollHorizontally(): Boolean = true

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (itemCount == 0) {
            detachAndScrapAttachedViews(recycler)
            return
        }

        detachAndScrapAttachedViews(recycler)
        fill(recycler)
    }

    override fun scrollHorizontallyBy(
        dx: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        val maxScroll = (itemCount - 1) * defaultItemWidth
        val newOffset = (horizontalOffset + dx).coerceIn(0, maxScroll)
        val actualDx = newOffset - horizontalOffset
        horizontalOffset = newOffset

        offsetChildrenHorizontal(-actualDx)
        fill(recycler)
        return actualDx
    }

    private fun fill(recycler: RecyclerView.Recycler) {
        val midPoint = width / 2
        val startX = midPoint - (defaultItemWidth / 2) - horizontalOffset

        for (i in 0 until itemCount) {
            val itemLeft = startX + i * defaultItemWidth
            val itemRight = itemLeft + defaultItemWidth

            // Check if item is within visible viewport
            if (itemRight > 0 && itemLeft < width) {
                val view = recycler.getViewForPosition(i)
                addView(view)
                measureChildWithMargins(view, 0, 0)

                val itemCenter = itemLeft + defaultItemWidth / 2
                val distanceToMid = abs(itemCenter - midPoint)
                val fraction = (1f - (distanceToMid / (width / 3f)).coerceIn(0f, 1f))
                val scale = 1f + (expandedScale - 1f) * fraction

                view.scaleX = scale
                view.scaleY = scale

                val layoutTop = (height - defaultItemHeight) / 2
                layoutDecorated(view, itemLeft, layoutTop, itemRight, layoutTop + defaultItemHeight)
            }
        }
    }

    override fun scrollToPosition(position: Int) {
        if (position in 0 until itemCount) {
            horizontalOffset = position * defaultItemWidth
            requestLayout()
        }
    }

    override fun smoothScrollToPosition(
        recyclerView: RecyclerView,
        state: RecyclerView.State,
        position: Int
    ) {
        val smoothScroller = object : LinearSmoothScroller(context) {
            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                return 40f / displayMetrics.densityDpi
            }

            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }
        }
        smoothScroller.targetPosition = position
        startSmoothScroll(smoothScroller)
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
        if (childCount == 0) return null
        val currentPosition = getPosition(getChildAt(0) ?: return null)
        val direction = if (targetPosition < currentPosition) -1f else 1f
        return PointF(direction, 0f)
    }
}

/**
 * SnapHelper that ensures thumbnails always settle dead-center on release
 */
class FilmStripSnapHelper : LinearSnapHelper() {
    override fun calculateDistanceToFinalSnap(
        layoutManager: RecyclerView.LayoutManager,
        targetView: View
    ): IntArray {
        val out = IntArray(2)
        val targetCenter = targetView.left + targetView.width / 2
        val layoutCenter = layoutManager.width / 2
        out[0] = targetCenter - layoutCenter
        out[1] = 0
        return out
    }
}
