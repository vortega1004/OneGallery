package com.onegallery.app.ui.viewer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** The EXIF fields the details sheet shows. Every field is null when the file doesn't carry it. */
data class ExifDetails(
    /** "Google Pixel 10 Pro XL" */
    val camera: String?,
    /** "f/1.7 · 1/120 s · ISO 64 · 6.9 mm" */
    val exposure: String?,
    /** "37.42190, -122.08400" */
    val location: String?
)

/**
 * Reads [ExifDetails] for an image. Returns null when the file can't be opened or has no EXIF
 * at all (screenshots, most downloads), so the caller can simply skip those rows.
 */
suspend fun readExifDetails(context: Context, uri: Uri): ExifDetails? = withContext(Dispatchers.IO) {
    // GPS is redacted unless the original is requested, which needs ACCESS_MEDIA_LOCATION.
    // Fall back to the plain Uri so camera/exposure still show without that permission.
    val exif = openExif(context, originalUriOrNull(uri)) ?: openExif(context, uri)
        ?: return@withContext null

    val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
    val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
    val camera = when {
        model.isEmpty() -> make
        // Many vendors repeat the make inside the model ("Google" / "Google Pixel 10")
        make.isEmpty() || model.startsWith(make, ignoreCase = true) -> model
        else -> "$make $model"
    }.ifEmpty { null }

    val exposureParts = buildList {
        exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
            .takeIf { it > 0 }
            ?.let { add(String.format(Locale.US, "f/%.1f", it)) }
        exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            .takeIf { it > 0 }
            ?.let { add(formatExposureTime(it)) }
        exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
            .takeIf { it > 0 }
            ?.let { add("ISO $it") }
        exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
            .takeIf { it > 0 }
            ?.let { add(String.format(Locale.US, "%.1f mm", it)) }
    }

    val location = exif.latLong?.let { (latitude, longitude) ->
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
    }

    if (camera == null && exposureParts.isEmpty() && location == null) {
        null
    } else {
        ExifDetails(
            camera = camera,
            exposure = exposureParts.joinToString(" · ").ifEmpty { null },
            location = location
        )
    }
}

/** 0.008 -> "1/125 s", 2.5 -> "2.5 s". Pure, so it is unit-testable. */
internal fun formatExposureTime(seconds: Double): String =
    if (seconds >= 1.0) {
        String.format(Locale.US, "%.1f s", seconds)
    } else {
        "1/${(1.0 / seconds).roundToInt()} s"
    }

private fun originalUriOrNull(uri: Uri): Uri? =
    try {
        MediaStore.setRequireOriginal(uri)
    } catch (_: Exception) {
        // Not a MediaStore Uri (ACTION_VIEW from another app)
        null
    }

private fun openExif(context: Context, uri: Uri?): ExifInterface? {
    if (uri == null) return null
    return try {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
    } catch (_: Exception) {
        // SecurityException/UnsupportedOperationException without ACCESS_MEDIA_LOCATION,
        // FileNotFoundException if the item vanished
        null
    }
}
