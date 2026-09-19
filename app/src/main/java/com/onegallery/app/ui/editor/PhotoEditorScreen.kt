package com.onegallery.app.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onegallery.app.data.MediaStoreRepository
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.ui.theme.OneUIBlue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class EditorTool(val label: String) {
    CROP("Crop & rotate"),
    ADJUST("Adjust")
}

private data class AspectPreset(val label: String, val aspect: Float?)

private val ASPECT_PRESETS = listOf(
    AspectPreset("Full", null),
    AspectPreset("1:1", 1f),
    AspectPreset("4:3", 4f / 3f),
    AspectPreset("3:4", 3f / 4f),
    AspectPreset("16:9", 16f / 9f),
    AspectPreset("9:16", 9f / 16f)
)

/**
 * Basic, non-destructive photo editor: rotate, mirror, free-form crop (with aspect presets)
 * and brightness / contrast / saturation. Saving always writes a **copy** — see
 * [MediaStoreRepository.saveEditedImage].
 *
 * Works on a [PhotoEditRenderer.PREVIEW_MAX_PX] preview; the full-resolution file is only
 * decoded once, when the user taps Save. All edits are plain values in [PhotoEditState].
 */
@Composable
fun PhotoEditorScreen(
    mediaItem: MediaItem,
    onClose: () -> Unit,
    onSaved: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val repository = remember { MediaStoreRepository(appContext) }

    var editState by remember(mediaItem.uri) { mutableStateOf(PhotoEditState()) }
    var tool by remember { mutableStateOf(EditorTool.CROP) }
    var isSaving by remember { mutableStateOf(false) }
    var loadFailed by remember(mediaItem.uri) { mutableStateOf(false) }

    // Upright preview of the untouched photo
    val basePreview by produceState<Bitmap?>(initialValue = null, mediaItem.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                PhotoEditRenderer.decode(appContext, mediaItem.uri, PhotoEditRenderer.PREVIEW_MAX_PX)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
        if (value == null) loadFailed = true
    }

    // The preview with the current flip/rotation baked in, so the crop overlay can work in the
    // same coordinate space the user sees. Colour is applied as a draw-time filter instead —
    // sliders would otherwise re-render a bitmap on every tick.
    val flip = editState.flipHorizontal
    val rotation = editState.rotationDegrees
    val orientedPreview by produceState<Bitmap?>(initialValue = null, basePreview, flip, rotation) {
        val base = basePreview
        value = if (base == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                PhotoEditRenderer.orient(base, PhotoEditState(flipHorizontal = flip, rotationDegrees = rotation))
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallow touches so nothing reaches the pager underneath
            .pointerInput(Unit) { }
            .systemBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            EditorTopBar(
                canReset = editState.hasChanges && !isSaving,
                canSave = editState.hasChanges && !isSaving && basePreview != null,
                onClose = onClose,
                onReset = { editState = PhotoEditState() },
                onSave = {
                    val stateToSave = editState
                    isSaving = true
                    scope.launch {
                        val savedUri = renderAndSave(appContext, repository, mediaItem, stateToSave)
                        isSaving = false
                        if (savedUri != null) {
                            Toast.makeText(context, "Saved a copy to Pictures/OneGallery_Edits", Toast.LENGTH_SHORT).show()
                            onSaved(savedUri)
                        } else {
                            Toast.makeText(context, "Couldn't save the edited photo", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )

            // Preview
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                val preview = orientedPreview
                when {
                    preview != null -> {
                        // Size the box to exactly the fitted image so the crop overlay's
                        // 0..1 coordinates line up with the pixels
                        val imageAspect = preview.width.toFloat() / preview.height.toFloat()
                        val boxAspect = maxWidth / maxHeight
                        val fittedWidth = if (imageAspect > boxAspect) maxWidth else maxHeight * imageAspect
                        val fittedHeight = if (imageAspect > boxAspect) maxWidth / imageAspect else maxHeight

                        val colorValues = editState.colorMatrixValues()
                        val colorFilter = remember(editState.brightness, editState.contrast, editState.saturation) {
                            ColorFilter.colorMatrix(ColorMatrix(colorValues))
                        }

                        Box(modifier = Modifier.size(fittedWidth, fittedHeight)) {
                            Image(
                                bitmap = remember(preview) { preview.asImageBitmap() },
                                contentDescription = mediaItem.displayName,
                                contentScale = ContentScale.FillBounds,
                                colorFilter = if (editState.hasColorAdjustments) colorFilter else null,
                                modifier = Modifier.fillMaxSize()
                            )
                            CropOverlay(
                                crop = editState.crop,
                                interactive = tool == EditorTool.CROP && !isSaving,
                                onCropChange = { editState = editState.copy(crop = it) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    loadFailed -> Text(
                        text = "This photo can't be opened for editing.",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )

                    else -> CircularProgressIndicator(color = Color.White)
                }
            }

            // Tool panel
            when (tool) {
                EditorTool.CROP -> CropPanel(
                    onRotateLeft = { editState = editState.rotateCounterClockwise() },
                    onRotateRight = { editState = editState.rotateClockwise() },
                    onFlip = { editState = editState.flipHorizontally() },
                    onPreset = { preset ->
                        val preview = orientedPreview
                        editState = editState.copy(
                            crop = if (preset.aspect == null || preview == null) {
                                NormalizedRect.FULL
                            } else {
                                NormalizedRect.centered(
                                    aspect = preset.aspect,
                                    imageAspect = preview.width.toFloat() / preview.height.toFloat()
                                )
                            }
                        )
                    }
                )

                EditorTool.ADJUST -> AdjustPanel(
                    state = editState,
                    onStateChange = { editState = it }
                )
            }

            // Tool switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EditorTool.entries.forEach { entry ->
                    TextButton(onClick = { tool = entry }) {
                        Text(
                            text = entry.label,
                            color = if (entry == tool) OneUIBlue else Color.White,
                            fontWeight = if (entry == tool) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }

        if (isSaving) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .pointerInput(Unit) { },
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

/**
 * Decodes the original at save resolution, renders the edit and writes the copy. Returns null
 * on any failure, including running out of memory on a very large source.
 */
private suspend fun renderAndSave(
    context: android.content.Context,
    repository: MediaStoreRepository,
    mediaItem: MediaItem,
    state: PhotoEditState
): Uri? = withContext(Dispatchers.Default) {
    var source: Bitmap? = null
    var rendered: Bitmap? = null
    try {
        source = PhotoEditRenderer.decode(context, mediaItem.uri, PhotoEditRenderer.SAVE_MAX_PX)
        rendered = PhotoEditRenderer.render(source, state)
        repository.saveEditedImage(rendered, mediaItem)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // Throwable on purpose: OutOfMemoryError is an Error, not an Exception
        t.printStackTrace()
        null
    } finally {
        if (rendered !== source) rendered?.recycle()
        source?.recycle()
    }
}

@Composable
private fun EditorTopBar(
    canReset: Boolean,
    canSave: Boolean,
    onClose: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close editor",
                tint = Color.White
            )
        }
        Text(
            text = "Edit",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        )
        TextButton(onClick = onReset, enabled = canReset) {
            Text(text = "Reset", color = if (canReset) Color.White else Color.Gray)
        }
        TextButton(onClick = onSave, enabled = canSave) {
            Text(
                text = "Save copy",
                color = if (canSave) OneUIBlue else Color.Gray,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CropPanel(
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlip: () -> Unit,
    onPreset: (AspectPreset) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            EditorAction(icon = Icons.Rounded.RotateLeft, label = "Rotate left", onClick = onRotateLeft)
            EditorAction(icon = Icons.Rounded.RotateRight, label = "Rotate right", onClick = onRotateRight)
            EditorAction(icon = Icons.Rounded.Flip, label = "Mirror", onClick = onFlip)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ASPECT_PRESETS.forEach { preset ->
                Text(
                    text = preset.label,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(16.dp))
                        .clickable { onPreset(preset) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun EditorAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White)
        Text(text = label, color = Color.LightGray, fontSize = 11.sp)
    }
}

@Composable
private fun AdjustPanel(
    state: PhotoEditState,
    onStateChange: (PhotoEditState) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        AdjustSlider(
            label = "Brightness",
            value = state.brightness,
            range = -1f..1f,
            neutral = 0f,
            onValueChange = { onStateChange(state.copy(brightness = it)) }
        )
        AdjustSlider(
            label = "Contrast",
            value = state.contrast,
            range = 0.5f..1.5f,
            neutral = 1f,
            onValueChange = { onStateChange(state.copy(contrast = it)) }
        )
        AdjustSlider(
            label = "Saturation",
            value = state.saturation,
            range = 0f..2f,
            neutral = 1f,
            onValueChange = { onStateChange(state.copy(saturation = it)) }
        )
    }
}

@Composable
private fun AdjustSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    neutral: Float,
    onValueChange: (Float) -> Unit
) {
    // Shown as -100..+100 around the neutral point, whatever the underlying range is
    val halfSpan = (range.endInclusive - range.start) / 2f
    val displayValue = ((value - neutral) / halfSpan * 100f).roundToInt()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.width(84.dp)
        )
        Slider(
            value = value,
            onValueChange = { raw ->
                // Snap to neutral near the middle so "unchanged" is easy to hit again
                onValueChange(if (kotlin.math.abs(raw - neutral) < halfSpan * 0.04f) neutral else raw)
            },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = OneUIBlue,
                inactiveTrackColor = Color.Gray
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (displayValue > 0) "+$displayValue" else "$displayValue",
            color = Color.LightGray,
            fontSize = 12.sp,
            modifier = Modifier
                .width(40.dp)
                .padding(start = 8.dp)
        )
    }
}

/**
 * Dims everything outside [crop] and, when [interactive], lets the user drag the corners or
 * the whole rectangle. Coordinates are normalized, so this only needs to be laid out exactly
 * over the image.
 */
@Composable
private fun CropOverlay(
    crop: NormalizedRect,
    interactive: Boolean,
    onCropChange: (NormalizedRect) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestCrop by rememberUpdatedState(crop)
    val latestInteractive by rememberUpdatedState(interactive)
    val latestOnCropChange by rememberUpdatedState(onCropChange)

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            val handleRadiusPx = 36.dp.toPx()
            var activeHandle: CropHandle? = null

            detectDragGestures(
                onDragStart = { position ->
                    activeHandle = if (latestInteractive) {
                        pickHandle(position, latestCrop, size.width.toFloat(), size.height.toFloat(), handleRadiusPx)
                    } else {
                        null
                    }
                },
                onDragEnd = { activeHandle = null },
                onDragCancel = { activeHandle = null },
                onDrag = { change, dragAmount ->
                    val handle = activeHandle
                    if (handle != null && size.width > 0 && size.height > 0) {
                        change.consume()
                        latestOnCropChange(
                            latestCrop.dragged(
                                handle = handle,
                                dx = dragAmount.x / size.width,
                                dy = dragAmount.y / size.height
                            )
                        )
                    }
                }
            )
        }
    ) {
        if (crop.isFull && !interactive) return@Canvas

        val left = crop.left * size.width
        val top = crop.top * size.height
        val right = crop.right * size.width
        val bottom = crop.bottom * size.height
        val scrim = Color(0x99000000)

        // Four bands around the crop window
        drawRect(scrim, topLeft = Offset.Zero, size = Size(size.width, top))
        drawRect(scrim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, bottom - top))
        drawRect(scrim, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))

        if (!interactive) return@Canvas

        drawRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 2.dp.toPx())
        )

        // Rule-of-thirds guides
        val guide = Color(0x80FFFFFF)
        for (i in 1..2) {
            val x = left + (right - left) * i / 3f
            val y = top + (bottom - top) * i / 3f
            drawLine(guide, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
            drawLine(guide, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }

        // Corner handles
        val handleRadius = 7.dp.toPx()
        listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom)).forEach {
            drawCircle(Color.White, radius = handleRadius, center = it)
        }
    }
}

/** Nearest corner within reach wins; otherwise a touch inside the rectangle moves it. */
private fun pickHandle(
    position: Offset,
    crop: NormalizedRect,
    width: Float,
    height: Float,
    handleRadiusPx: Float
): CropHandle? {
    val corners = listOf(
        CropHandle.TOP_LEFT to Offset(crop.left * width, crop.top * height),
        CropHandle.TOP_RIGHT to Offset(crop.right * width, crop.top * height),
        CropHandle.BOTTOM_LEFT to Offset(crop.left * width, crop.bottom * height),
        CropHandle.BOTTOM_RIGHT to Offset(crop.right * width, crop.bottom * height)
    )
    val nearest = corners.minByOrNull { (_, corner) ->
        hypot(position.x - corner.x, position.y - corner.y)
    }
    if (nearest != null) {
        val (handle, corner) = nearest
        if (hypot(position.x - corner.x, position.y - corner.y) <= handleRadiusPx) return handle
    }

    val isInside = position.x in (crop.left * width)..(crop.right * width) &&
        position.y in (crop.top * height)..(crop.bottom * height)
    return if (isInside) CropHandle.MOVE else null
}
