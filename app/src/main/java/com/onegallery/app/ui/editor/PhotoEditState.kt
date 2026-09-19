package com.onegallery.app.ui.editor

/**
 * A rectangle in 0..1 coordinates relative to the image it sits on, so the same crop applies
 * to the small preview bitmap and the full-resolution bitmap that gets saved.
 *
 * Deliberately free of Android/Compose types: everything in this file is pure and unit-tested
 * (`PhotoEditStateTest`).
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    val isFull: Boolean
        get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    /** Where this rectangle ends up after the image under it is rotated 90° clockwise. */
    fun rotatedClockwise() = NormalizedRect(
        left = 1f - bottom,
        top = left,
        right = 1f - top,
        bottom = right
    )

    fun rotatedCounterClockwise() = NormalizedRect(
        left = top,
        top = 1f - right,
        right = bottom,
        bottom = 1f - left
    )

    fun flippedHorizontally() = NormalizedRect(
        left = 1f - right,
        top = top,
        right = 1f - left,
        bottom = bottom
    )

    /**
     * Moves one [handle] by a normalized delta, keeping the rectangle inside the image and no
     * smaller than [MIN_SIZE] on either side.
     */
    fun dragged(handle: CropHandle, dx: Float, dy: Float): NormalizedRect = when (handle) {
        CropHandle.TOP_LEFT -> copy(
            left = (left + dx).coerceIn(0f, right - MIN_SIZE),
            top = (top + dy).coerceIn(0f, bottom - MIN_SIZE)
        )
        CropHandle.TOP_RIGHT -> copy(
            right = (right + dx).coerceIn(left + MIN_SIZE, 1f),
            top = (top + dy).coerceIn(0f, bottom - MIN_SIZE)
        )
        CropHandle.BOTTOM_LEFT -> copy(
            left = (left + dx).coerceIn(0f, right - MIN_SIZE),
            bottom = (bottom + dy).coerceIn(top + MIN_SIZE, 1f)
        )
        CropHandle.BOTTOM_RIGHT -> copy(
            right = (right + dx).coerceIn(left + MIN_SIZE, 1f),
            bottom = (bottom + dy).coerceIn(top + MIN_SIZE, 1f)
        )
        CropHandle.MOVE -> {
            val clampedDx = dx.coerceIn(-left, 1f - right)
            val clampedDy = dy.coerceIn(-top, 1f - bottom)
            NormalizedRect(left + clampedDx, top + clampedDy, right + clampedDx, bottom + clampedDy)
        }
    }

    companion object {
        val FULL = NormalizedRect(0f, 0f, 1f, 1f)

        /** Smallest crop allowed, as a fraction of the image side. */
        const val MIN_SIZE = 0.1f

        /**
         * The largest centered rectangle with pixel aspect ratio [aspect] (width / height) that
         * fits an image whose own pixel aspect ratio is [imageAspect].
         */
        fun centered(aspect: Float, imageAspect: Float): NormalizedRect {
            if (aspect <= 0f || imageAspect <= 0f) return FULL
            return if (aspect > imageAspect) {
                // Wider than the image: full width, reduced height
                val height = imageAspect / aspect
                NormalizedRect(0f, (1f - height) / 2f, 1f, (1f + height) / 2f)
            } else {
                val width = aspect / imageAspect
                NormalizedRect((1f - width) / 2f, 0f, (1f + width) / 2f, 1f)
            }
        }
    }
}

enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

/**
 * Everything the editor can change, as plain values.
 *
 * Geometry is stored as one canonical transform — **flip horizontally first, then rotate
 * clockwise by [rotationDegrees]** — rather than as the list of buttons the user pressed. Any
 * sequence of 90° rotations and mirror flips reduces to that form, which keeps saving simple:
 * the renderer applies exactly one flip and one rotation however long the user played with the
 * buttons. [crop] lives in the coordinate space of the image *after* that transform, i.e. what
 * the user sees.
 */
data class PhotoEditState(
    val flipHorizontal: Boolean = false,
    val rotationDegrees: Int = 0,
    val crop: NormalizedRect = NormalizedRect.FULL,
    /** -1..1, 0 = unchanged */
    val brightness: Float = 0f,
    /** 0.5..1.5, 1 = unchanged */
    val contrast: Float = 1f,
    /** 0..2, 1 = unchanged, 0 = greyscale */
    val saturation: Float = 1f
) {
    val hasGeometryChanges: Boolean
        get() = flipHorizontal || rotationDegrees != 0 || !crop.isFull

    val hasColorAdjustments: Boolean
        get() = brightness != 0f || contrast != 1f || saturation != 1f

    val hasChanges: Boolean
        get() = hasGeometryChanges || hasColorAdjustments

    /** True when the visible image is on its side relative to the source. */
    val swapsDimensions: Boolean
        get() = rotationDegrees == 90 || rotationDegrees == 270

    fun rotateClockwise() = copy(
        rotationDegrees = (rotationDegrees + 90) % 360,
        crop = crop.rotatedClockwise()
    )

    fun rotateCounterClockwise() = copy(
        rotationDegrees = (rotationDegrees + 270) % 360,
        crop = crop.rotatedCounterClockwise()
    )

    /**
     * Mirrors what the user currently sees. Mirroring *after* a rotation equals mirroring
     * *before* the opposite rotation (H·R(θ) = R(−θ)·H), which is how the new flip is folded
     * back into the canonical flip-then-rotate form.
     */
    fun flipHorizontally() = copy(
        flipHorizontal = !flipHorizontal,
        rotationDegrees = (360 - rotationDegrees) % 360,
        crop = crop.flippedHorizontally()
    )

    /**
     * 4x5 colour matrix (row-major, offsets in 0..255) combining saturation, then contrast
     * around mid-grey, then brightness. The same array feeds the Compose preview filter and the
     * android.graphics filter used when saving, so the saved file matches the preview.
     */
    fun colorMatrixValues(): FloatArray {
        val s = saturation
        val invS = 1f - s
        val r = LUMA_R * invS
        val g = LUMA_G * invS
        val b = LUMA_B * invS

        val c = contrast
        val offset = (1f - c) * 127.5f + brightness * BRIGHTNESS_RANGE

        return floatArrayOf(
            c * (r + s), c * g, c * b, 0f, offset,
            c * r, c * (g + s), c * b, 0f, offset,
            c * r, c * g, c * (b + s), 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    }

    private companion object {
        const val LUMA_R = 0.213f
        const val LUMA_G = 0.715f
        const val LUMA_B = 0.072f

        /** Offset applied at brightness = ±1. Half the channel range is plenty. */
        const val BRIGHTNESS_RANGE = 100f
    }
}
