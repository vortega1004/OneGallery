package com.onegallery.app.ui.gif

import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import com.onegallery.app.ui.editor.CropOverlay
import com.onegallery.app.ui.editor.NormalizedRect
import com.onegallery.app.ui.editor.PhotoEditState
import com.onegallery.app.ui.theme.OneUIBlue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun GifCreatorScreen(model: GifCreatorViewModel, onClose: () -> Unit) {
    val draft = model.draft ?: return
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf(false) }
    var previewIndex by remember { mutableIntStateOf(draft.activeIndex) }
    var previewError by remember { mutableStateOf(false) }
    val busy = model.loading || model.saving
    val displayedIndex = (if (playing) previewIndex else draft.activeIndex).coerceIn(draft.frames.indices)
    val frame = draft.frames[displayedIndex]
    val previewEdit = if (cropping) draft.edit.copy(crop = NormalizedRect.FULL) else draft.edit
    val cache = remember {
        object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    }
    val previewKey = "${frame.uri}|${draft.canvasAspect}|$previewEdit"
    val bitmap by produceState<Bitmap?>(null, previewKey, model.loading) {
        value = cache.get(previewKey)
        previewError = false
        if (model.loading) return@produceState
        if (value != null) return@produceState
        try {
            value = withContext(Dispatchers.IO) {
                cache.get(previewKey) ?: GifRenderer.render(context.applicationContext, frame,
                    draft.copy(edit = previewEdit), edge = 480).also { cache.put(previewKey, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            previewError = true
        }
    }

    // Wait for each preview to decode before timing its dwell. Export timing is exact;
    // uncached, large-library previews may take longer while individual frames load.
    // Separate cursor: boomerang visits interior frames twice in each cycle.
    var cyclePosition by remember { mutableIntStateOf(0) }
    LaunchedEffect(playing, bitmap, draft.loop, draft.delayCentiseconds, busy, cyclePosition) {
        if (playing && bitmap != null && !busy) {
            delay(draft.delayCentiseconds * 10L)
            val order = draft.order
            cyclePosition = (cyclePosition + 1) % order.size
            previewIndex = order[cyclePosition]
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playing = false }
    BackHandler { if (model.saving) model.cancelSave() else onClose() }

    val stop: () -> Unit = {
        if (playing) model.update { it.copy(activeIndex = displayedIndex) }
        playing = false
    }
    val preview: @Composable (Modifier) -> Unit = { modifier ->
        BoxWithConstraints(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
            val image = bitmap
            when {
                previewError -> Text("Can't read this image. Remove it or restore access.", color = Color.LightGray)
                image == null -> CircularProgressIndicator()
                else -> {
                    val aspect = image.width.toFloat() / image.height
                    val w = minOf(maxWidth, maxHeight * aspect)
                    val h = w / aspect
                    Box(Modifier.size(w, h)) {
                        Image(image.asImageBitmap(), "GIF preview, frame ${displayedIndex + 1}",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        if (cropping) CropOverlay(draft.edit.crop, !busy,
                            { crop -> model.update { it.copy(edit = it.edit.copy(crop = crop)) } },
                            Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
    val tools: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            val strip = rememberLazyListState()
            LaunchedEffect(displayedIndex) {
                if (strip.layoutInfo.visibleItemsInfo.none { it.index == displayedIndex }) {
                    strip.scrollToItem(displayedIndex)
                }
            }
            Text("${displayedIndex + 1} / ${draft.frames.size} frames · ${String.format(Locale.US, "%.2f", draft.durationMs / 1000f)} s / loop",
                color = Color.LightGray)
            LazyRow(state = strip, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                itemsIndexed(draft.frames, key = { _, f -> f.key }) { index, f ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(62.dp).border(if (index == displayedIndex) 2.dp else 0.dp,
                            if (index == displayedIndex) OneUIBlue else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable(enabled = !busy) {
                                playing = false
                                model.update { it.copy(activeIndex = index) }
                            }.padding(3.dp)) {
                        AsyncImage(f.uri, "Frame ${index + 1}: ${f.name}", contentScale = ContentScale.Crop,
                            modifier = Modifier.size(54.dp))
                        Text("${index + 1}", color = Color.White)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.Center) {
                GifButton(Icons.AutoMirrored.Rounded.ArrowBack, "Move frame earlier", !busy && displayedIndex > 0) {
                    stop(); model.update { it.moveActive(-1) }
                }
                GifButton(Icons.Rounded.ContentCopy, "Duplicate frame in place", !busy) { stop(); model.duplicate() }
                GifButton(Icons.Rounded.DeleteOutline, "Remove frame from GIF", !busy && draft.frames.size > 2) { stop(); model.remove() }
                GifButton(Icons.AutoMirrored.Rounded.ArrowForward, "Move frame later", !busy && displayedIndex < draft.frames.lastIndex) {
                    stop(); model.update { it.moveActive(1) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                GifButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playing) "Pause preview" else "Play preview", !busy && !previewError) {
                    if (playing) stop() else {
                        cropping = false
                        cyclePosition = 0
                        previewIndex = draft.order.first()
                        playing = true
                    }
                }
                listOf(GifLoop.FORWARD to Icons.AutoMirrored.Rounded.ArrowForward,
                    GifLoop.REVERSE to Icons.AutoMirrored.Rounded.ArrowBack,
                    GifLoop.BOOMERANG to Icons.Rounded.Sync).forEach { (loop, icon) ->
                    GifButton(icon, when (loop) {
                        GifLoop.FORWARD -> "Forward loop"
                        GifLoop.REVERSE -> "Reverse loop"
                        GifLoop.BOOMERANG -> "Boomerang loop"
                    }, !busy, draft.loop == loop) {
                        stop(); model.update { it.copy(loop = loop) }
                    }
                }
            }
            Text("Speed · ${String.format(Locale.US, "%.1f", 100f / draft.delayCentiseconds)} fps · ${draft.delayCentiseconds * 10} ms/frame",
                color = Color.White)
            Slider(value = 100f / draft.delayCentiseconds,
                onValueChange = { speed -> model.update { it.copy(delayCentiseconds = (100f / speed).roundToInt()) } },
                valueRange = 1f..25f, steps = 23, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Playback speed" })
            Row(verticalAlignment = Alignment.CenterVertically) {
                GifButton(Icons.AutoMirrored.Rounded.RotateLeft, "Rotate GIF left", !busy) {
                    stop(); model.update { it.copy(edit = it.edit.rotateCounterClockwise()) }
                }
                GifButton(Icons.AutoMirrored.Rounded.RotateRight, "Rotate GIF right", !busy) {
                    stop(); model.update { it.copy(edit = it.edit.rotateClockwise()) }
                }
                GifButton(Icons.Rounded.Crop, "Crop entire GIF", !busy, cropping) { stop(); cropping = !cropping }
                GifButton(Icons.Rounded.RestartAlt, "Reset crop and rotation", !busy) {
                    stop(); model.update { it.copy(edit = PhotoEditState()) }
                }
            }
            if (cropping) {
                Text("Drag corners or move the crop · applies to every frame", color = Color.LightGray)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Full" to 0f, "1:1" to 1f, "4:3" to 4f / 3, "16:9" to 16f / 9, "9:16" to 9f / 16).forEach { (name, ratio) ->
                        TextButton(enabled = !busy, onClick = {
                            model.update {
                                val aspect = if (it.edit.swapsDimensions) 1f / it.canvasAspect else it.canvasAspect
                                it.copy(edit = it.edit.copy(crop = NormalizedRect.centered(ratio, aspect)))
                            }
                        }) { Text(name) }
                    }
                    TextButton(onClick = { cropping = false }) { Text("Done") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Canvas", color = Color.White)
                listOf(480, 720, 1080).forEach { edge ->
                    FilterChip(draft.longEdge == edge, { model.update { it.copy(longEdge = edge) } },
                        label = { Text("${edge}px") }, enabled = !busy)
                }
            }
            val (width, height) = draft.outputSize
            Text("Output: $width × $height", color = Color.LightGray)
            Text("Saves to ${GifRenderer.FOLDER}", color = Color.LightGray, modifier = Modifier.padding(bottom = 8.dp))
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GifButton(Icons.Rounded.Close, "Close GIF creator", !model.saving, onClick = onClose)
            Text("Create GIF", color = Color.White, modifier = Modifier.weight(1f))
            TextButton(enabled = !busy && !previewError, onClick = { stop(); cropping = false; model.save() }) { Text("Save") }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val toolWidth = minOf(380.dp, maxWidth / 2)
            val toolHeight = maxHeight * 0.60f
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    preview(Modifier.weight(1f).fillMaxSize())
                    tools(Modifier.width(toolWidth).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    preview(Modifier.weight(1f).fillMaxWidth())
                    tools(Modifier.fillMaxWidth().heightIn(max = toolHeight))
                }
            }
        }
    }

    if (model.saving) AlertDialog(onDismissRequest = model::cancelSave,
        properties = DialogProperties(dismissOnClickOutside = false), title = { Text("Saving GIF") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
            Text("${(model.progress * 100).roundToInt()}%")
        }
    }, confirmButton = { TextButton(onClick = model::cancelSave) { Text("Cancel") } })
    model.error?.let { message ->
        AlertDialog(onDismissRequest = model::dismissError, title = { Text("GIF creator") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text("OK") } })
    }
    model.savedUri?.let { uri ->
        AlertDialog(onDismissRequest = onClose, title = { Text("GIF saved") }, text = { Text(GifRenderer.FOLDER) },
            confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
            dismissButton = { TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/gif"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share GIF"))
            }) { Text("Share") } })
    }
}

@Composable
private fun GifButton(icon: ImageVector, description: String, enabled: Boolean = true,
    isSelected: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.semantics { selected = isSelected }) {
        Icon(icon, description, tint = when {
            !enabled -> Color.Gray
            isSelected -> OneUIBlue
            else -> Color.White
        })
    }
}
