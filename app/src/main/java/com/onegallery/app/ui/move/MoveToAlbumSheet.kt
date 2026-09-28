package com.onegallery.app.ui.move

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.onegallery.app.data.MoveDestination
import com.onegallery.app.data.destinationAllows
import com.onegallery.app.data.newAlbumPath
import com.onegallery.app.data.normalizeRelativePath
import com.onegallery.app.domain.Album
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.domain.MediaType
import com.onegallery.app.ui.common.rememberThumbnailRequest
import com.onegallery.app.ui.theme.DarkSurface
import com.onegallery.app.ui.theme.DarkSurfaceVariant
import com.onegallery.app.ui.theme.DarkTextPrimary
import com.onegallery.app.ui.theme.DarkTextSecondary
import com.onegallery.app.ui.theme.OneUIBlue

/**
 * Picks where [items] should go: an existing album, or a new one under Pictures/.
 *
 * Albums the move cannot land in are shown greyed with the reason, rather than hidden. A folder
 * that silently fails to appear reads as a bug; one that says "Photos can't go in Movies/"
 * explains a rule the user would otherwise hit as an error after confirming.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToAlbumSheet(
    items: List<MediaItem>,
    albums: List<Album>,
    onMove: (MoveDestination) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var namingNewAlbum by remember { mutableStateOf(false) }
    // Most recently added-to first: the folders someone is actively sorting into
    val ordered = remember(albums) { albums.sortedByDescending { it.newestDate } }
    val volume = items.firstOrNull()?.volumeName.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                text = if (items.size == 1) "Move 1 item to" else "Move ${items.size} items to",
                color = DarkTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                item {
                    DestinationRow(
                        title = "New album",
                        subtitle = "Created in Pictures",
                        enabled = true,
                        leading = {
                            Box(
                                Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                                    .background(DarkSurfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.CreateNewFolder, null, tint = OneUIBlue)
                            }
                        },
                        onClick = { namingNewAlbum = true }
                    )
                }
                items(ordered, key = { it.id }) { album ->
                    val blocked = blockedReason(items, album)
                    DestinationRow(
                        title = album.name,
                        subtitle = blocked ?: "${album.count} · ${album.relativePath.trimEnd('/')}",
                        enabled = blocked == null,
                        leading = {
                            AsyncImage(
                                model = rememberThumbnailRequest(album.coverUri),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                            )
                        },
                        onClick = {
                            onMove(MoveDestination(album.relativePath, album.volumeName, album.name))
                        }
                    )
                }
                item { MediaManagementHint() }
            }
        }
    }

    if (namingNewAlbum) {
        NewAlbumDialog(
            onDismiss = { namingNewAlbum = false },
            onCreate = { name, path ->
                namingNewAlbum = false
                onMove(MoveDestination(path, volume, name))
            }
        )
    }
}

/**
 * Why [album] cannot take this batch, or null if it can.
 *
 * Blocked only when *nothing* could move: a mixed batch where some items are already there, or
 * one video among photos headed for Movies/, still goes — the move reports what it skipped.
 */
private fun blockedReason(items: List<MediaItem>, album: Album): String? {
    // The storage root ("/" in MediaStore) is not a place media may live
    if (normalizeRelativePath(album.relativePath).isEmpty()) return "Can't move items here"
    if (items.all { it.bucketId == album.id }) return "Already here"
    val candidates = items.filter { it.bucketId != album.id }
    if (candidates.none { it.volumeName == album.volumeName }) return "On a different storage"
    if (candidates.none { destinationAllows(it.mediaType, album.relativePath) }) {
        val folder = album.relativePath.substringBefore('/')
        val what = when {
            candidates.all { it.mediaType == MediaType.VIDEO } -> "Videos"
            candidates.none { it.mediaType == MediaType.VIDEO } -> "Photos"
            else -> "These items"
        }
        return "$what can't go in $folder/"
    }
    return null
}

@Composable
private fun DestinationRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    leading: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, color = DarkTextPrimary, fontSize = 16.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = DarkTextSecondary, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NewAlbumDialog(onDismiss: () -> Unit, onCreate: (name: String, path: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val path = newAlbumPath(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = { Text("New album", color = DarkTextPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Album name", color = DarkTextSecondary) },
                supportingText = { path?.let { Text(it.trimEnd('/'), color = DarkTextSecondary) } },
                // The app's palette is not wired into MaterialTheme, so the defaults drew the
                // typed name dark grey on the dark dialog — there, but next to invisible
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = DarkTextPrimary,
                    unfocusedTextColor = DarkTextPrimary,
                    cursorColor = OneUIBlue,
                    focusedBorderColor = OneUIBlue,
                    unfocusedBorderColor = DarkTextSecondary
                )
            )
        },
        confirmButton = {
            TextButton(enabled = path != null, onClick = { path?.let { onCreate(name.trim(), it) } }) {
                Text("Move here")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Offers the one-time grant that removes the system's confirmation dialog from every move.
 *
 * Shown only where it applies — Android 12 and later, and not already granted — because the
 * dialog is otherwise the price of every single move, and the setting is buried where nobody
 * would find it unprompted.
 */
@Composable
private fun MediaManagementHint() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    if (MediaStore.canManageMedia(context)) return
    TextButton(
        onClick = {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}"))
            )
        },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            "Skip the confirmation on future moves",
            color = Color(0xFF8AB4F8),
            fontSize = 13.sp
        )
    }
}
