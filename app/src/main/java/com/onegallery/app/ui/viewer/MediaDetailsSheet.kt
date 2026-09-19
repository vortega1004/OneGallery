package com.onegallery.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.ui.theme.DarkSurface
import com.onegallery.app.ui.theme.DarkTextPrimary
import com.onegallery.app.ui.theme.DarkTextSecondary
import com.onegallery.app.ui.theme.OneUIBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Replicates Samsung Gallery's signature Swipe-Up Details Sheet:
 * Shows rich EXIF, camera aperture, shutter speed, ISO, resolution, file size, and storage path.
 *
 * MediaStore fields render immediately; the EXIF rows (camera, exposure, location) are read
 * from the file off the main thread and appear when ready. Location only shows when the user
 * granted ACCESS_MEDIA_LOCATION — without it MediaStore hands back a redacted file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailsSheet(
    mediaItem: MediaItem,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            val context = LocalContext.current
            val exif by produceState<ExifDetails?>(initialValue = null, mediaItem.uri) {
                value = if (mediaItem.isVideo) null else readExifDetails(context, mediaItem.uri)
            }

            // Drag handle / Title
            Text(
                text = "Details",
                color = DarkTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Date & Time
            val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy • h:mm a", Locale.getDefault())
                .format(Date(mediaItem.dateTaken))

            DetailRow(
                icon = Icons.Rounded.CalendarToday,
                title = "Date taken",
                value = dateStr
            )

            // File Name & Resolution
            DetailRow(
                icon = Icons.Rounded.Image,
                title = mediaItem.displayName,
                value = "${mediaItem.width} × ${mediaItem.height} • ${formatFileSize(mediaItem.size)}"
            )

            // Folder & Path
            DetailRow(
                icon = Icons.Rounded.Folder,
                title = mediaItem.bucketName,
                value = mediaItem.path
            )

            // Camera & exposure (EXIF)
            exif?.let { details ->
                if (details.camera != null || details.exposure != null) {
                    DetailRow(
                        icon = Icons.Rounded.CameraAlt,
                        title = details.camera ?: "Camera",
                        value = details.exposure ?: "No exposure data"
                    )
                }
                details.location?.let { location ->
                    DetailRow(
                        icon = Icons.Rounded.Place,
                        title = "Location",
                        value = location
                    )
                }
            }

            // Camera / Tech Specs (Samsung style card)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF222428))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (mediaItem.isVideo) "Video Details" else "Photo Details",
                            color = DarkTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (mediaItem.isVideo) "Duration: ${mediaItem.formattedDuration}" else "Format: ${mediaItem.mimeType.substringAfter("/")}",
                            color = DarkTextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        tint = OneUIBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DarkTextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                color = DarkTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                color = DarkTextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        kb >= 1.0 -> String.format("%.0f KB", kb)
        else -> "$bytes B"
    }
}
