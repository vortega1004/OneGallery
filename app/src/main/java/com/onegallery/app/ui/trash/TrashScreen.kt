package com.onegallery.app.ui.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.onegallery.app.data.TrashedItem
import com.onegallery.app.data.expiryLabel
import com.onegallery.app.ui.theme.DarkBackground
import com.onegallery.app.ui.theme.DarkSurface
import com.onegallery.app.ui.theme.DarkTextPrimary
import com.onegallery.app.ui.theme.DarkTextSecondary
import com.onegallery.app.ui.theme.OneUIBlue

/**
 * What is in the trash, with the two things that can be done about it: put it back, or delete
 * it for good now instead of waiting out Android's thirty days.
 *
 * Tapping an item selects it — there is nothing to open, and the actions are all about which
 * items they apply to. With nothing selected the bottom bar acts on the whole trash, which is
 * the common case ("empty it") and should not need a select-all first.
 *
 * Deleting for good is the only action in the app that cannot be undone, so it is the only one
 * that asks first.
 */
@Composable
fun TrashScreen(
    items: List<TrashedItem>,
    onRestore: (List<TrashedItem>) -> Unit,
    onDeleteForever: (List<TrashedItem>) -> Unit,
    onClose: () -> Unit
) {
    var selectedIds by rememberSaveable { mutableStateOf(longArrayOf()) }
    val selected = remember(selectedIds, items) {
        val ids = selectedIds.toHashSet()
        items.filter { it.id in ids }
    }
    // Items leave the trash underneath us (restored, expired, emptied from another app)
    LaunchedEffect(items) {
        val present = items.mapTo(hashSetOf()) { it.id }
        if (selectedIds.any { it !in present }) {
            selectedIds = selectedIds.filter { it in present }.toLongArray()
        }
    }
    var confirmDelete by remember { mutableStateOf<List<TrashedItem>?>(null) }
    val now = remember(items) { System.currentTimeMillis() }

    BackHandler { if (selectedIds.isNotEmpty()) selectedIds = longArrayOf() else onClose() }

    // What the bottom bar acts on: the selection, or everything when nothing is selected
    val targets = selected.ifEmpty { items }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground,
        bottomBar = {
            if (items.isNotEmpty()) {
                NavigationBar(containerColor = DarkSurface) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { onRestore(targets) }) {
                            Text(if (selected.isEmpty()) "Restore all" else "Restore (${selected.size})")
                        }
                        TextButton(onClick = { confirmDelete = targets }) {
                            Text(
                                text = if (selected.isEmpty()) "Empty trash" else "Delete (${selected.size})",
                                color = DeleteRed
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 20.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to albums", tint = DarkTextPrimary)
                }
                Spacer(Modifier.size(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (selected.isEmpty()) "Trash" else "${selected.size} selected",
                        color = DarkTextPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when (items.size) {
                            0 -> "Nothing in the trash"
                            1 -> "1 item · deleted for good after 30 days"
                            else -> "${items.size} items · deleted for good after 30 days"
                        },
                        color = DarkTextSecondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (items.isNotEmpty()) {
                    TextButton(onClick = {
                        selectedIds = if (selected.size == items.size) longArrayOf()
                        else items.map { it.id }.toLongArray()
                    }) { Text(if (selected.size == items.size) "None" else "All") }
                }
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Photos and videos you delete wait here for 30 days.",
                        color = DarkTextSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items = items, key = { it.id }) { item ->
                        val isSelected = item.id in selectedIds
                        TrashCell(
                            item = item,
                            expiry = expiryLabel(item.expiresAtMs, now),
                            selected = isSelected,
                            onClick = {
                                selectedIds = if (isSelected) selectedIds.filter { it != item.id }.toLongArray()
                                else selectedIds + item.id
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = DarkSurface,
            titleContentColor = DarkTextPrimary,
            textContentColor = DarkTextSecondary,
            title = {
                Text(if (doomed.size == 1) "Delete 1 item for good?" else "Delete ${doomed.size} items for good?")
            },
            text = { Text("This removes them from the phone. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    onDeleteForever(doomed)
                }) { Text("Delete", color = DeleteRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TrashCell(
    item: TrashedItem,
    expiry: String,
    selected: Boolean,
    onClick: () -> Unit,
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
            model = item.uri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (selected) Box(Modifier.fillMaxSize().border(3.dp, OneUIBlue))
        Icon(
            imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (selected) "Selected" else "Not selected",
            tint = if (selected) OneUIBlue else Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .background(Color(0x99000000), RoundedCornerShape(12.dp))
                .size(24.dp)
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xB3000000))
                .padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.isVideo) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
            if (expiry.isNotEmpty()) {
                Text(
                    text = expiry,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = if (item.isVideo) 2.dp else 0.dp)
                )
            }
        }
    }
}

private val DeleteRed = Color(0xFFFF6B6B)
