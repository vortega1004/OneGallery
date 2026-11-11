package com.onegallery.app.ui.video

/**
 * When each frame of a video is shown: the real presentation time of every frame, in order,
 * read from the file — never worked out from a frame rate.
 *
 * **Why not `index × 1000 / fps`.** That is only true for footage with a constant rate, a video
 * track exactly as long as the file, and a first frame at time zero. A phone camera gives none
 * of the three: the rate wanders, the sound runs a little past the picture, so "frames over
 * duration" is an average that is wrong at both ends. Measured on a variable-rate clip, pressing
 * *previous* on the last frame landed eleven frames back. With the times in hand the mapping is
 * a lookup and is exact for any file.
 *
 * [ptsUs] is ascending. [keyframes] holds the indices (into [ptsUs]) of the frames a decoder can
 * start from, ascending; a clip written by ffmpeg with default settings — which is what ComfyUI
 * produces — often has exactly one, at index 0.
 */
class VideoTimeline(
    private val ptsUs: LongArray,
    private val keyframes: IntArray
) {
    init {
        require(ptsUs.isNotEmpty()) { "A timeline needs at least one frame" }
    }

    val frameCount: Int get() = ptsUs.size
    val lastIndex: Int get() = ptsUs.size - 1

    /** Presentation time of frame [index], in microseconds. */
    fun timeUs(index: Int): Long = ptsUs[index.coerceIn(0, lastIndex)]

    /**
     * The position to give the player so that an exact seek shows frame [index].
     *
     * An exact seek shows the first frame at or after the position asked for, and the player
     * takes whole milliseconds, so the frame's time is rounded **down**: rounding up would step
     * past it onto the next frame.
     */
    fun seekPositionMs(index: Int): Long = timeUs(index) / 1000

    /** The frame on screen at [timeUs]: the last one whose time has come. */
    fun indexAtOrBeforeUs(timeUs: Long): Int {
        var low = 0
        var high = lastIndex
        var found = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (ptsUs[mid] <= timeUs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** The frame whose time is closest to [timeUs] — for a time that came from somewhere else. */
    fun nearestIndexUs(timeUs: Long): Int {
        val before = indexAtOrBeforeUs(timeUs)
        if (before >= lastIndex) return lastIndex
        val after = before + 1
        return if (ptsUs[after] - timeUs < timeUs - ptsUs[before]) after else before
    }

    fun nearestIndexMs(positionMs: Long): Int = nearestIndexUs(positionMs * 1000)

    /** The last keyframe at or before [index]: where a decoder has to start to reach it. */
    fun keyframeAtOrBefore(index: Int): Int {
        var found = 0
        for (keyframe in keyframes) {
            if (keyframe > index) break
            found = keyframe
        }
        return found
    }

    /** Frames per second across the whole clip: exact for constant-rate footage, a mean otherwise. */
    val averageFrameRate: Float
        get() {
            val span = ptsUs[lastIndex] - ptsUs[0]
            return if (lastIndex < 1 || span <= 0L) 0f else lastIndex * 1_000_000f / span
        }

    companion object {
        /**
         * Builds a timeline from samples in file (decode) order, which is not display order
         * when the encoder used B-frames. Samples with a negative time are dropped: they sit
         * before the start of the track's edit and are never shown.
         */
        fun fromSamples(timesUs: LongArray, isKeyframe: BooleanArray): VideoTimeline? {
            val order = timesUs.indices.filter { timesUs[it] >= 0L }.sortedBy { timesUs[it] }
            if (order.isEmpty()) return null
            val sorted = LongArray(order.size) { timesUs[order[it]] }
            val keys = order.indices.filter { isKeyframe[order[it]] }.toIntArray()
            return VideoTimeline(sorted, if (keys.isEmpty()) intArrayOf(0) else keys)
        }
    }
}
