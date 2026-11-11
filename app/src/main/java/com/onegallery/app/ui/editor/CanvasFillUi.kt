package com.onegallery.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onegallery.app.ui.theme.DarkSurface
import com.onegallery.app.ui.theme.DarkTextPrimary
import com.onegallery.app.ui.theme.OneUIBlue
import kotlin.math.roundToInt

/**
 * The photo on its canvas, as it is being arranged. Drag the photo to place it.
 *
 * **Two looks.** While working, the part of the canvas the photo does not cover is drawn as a
 * pane of dark glass with a light rim — chroma green fills most of the screen on a square
 * canvas and is tiring to stare at, and the glass still shows exactly where the canvas ends.
 * A faint wash of the chosen colour says which colour is picked. With [showFinal] the canvas is
 * drawn as it will be saved: square corners, flat fill.
 *
 * [preview] is the upright photo; [crop] is the part of it that is kept.
 */
@Composable
internal fun CanvasPreview(
    preview: Bitmap,
    crop: NormalizedRect,
    colorFilter: ColorFilter?,
    fill: CanvasFill,
    showFinal: Boolean,
    interactive: Boolean,
    onFillChange: (CanvasFill) -> Unit,
    maxWidth: Dp,
    maxHeight: Dp
) {
    val image = remember(preview) { preview.asImageBitmap() }
    val cropLeft = (crop.left * preview.width).roundToInt().coerceIn(0, preview.width - 1)
    val cropTop = (crop.top * preview.height).roundToInt().coerceIn(0, preview.height - 1)
    val cropWidth = (crop.width * preview.width).roundToInt().coerceIn(1, preview.width - cropLeft)
    val cropHeight = (crop.height * preview.height).roundToInt().coerceIn(1, preview.height - cropTop)

    // Laid out at the preview's own scale; only the proportions are used here
    val layout = fill.layout(cropWidth, cropHeight, maxPixels = Long.MAX_VALUE) ?: return
    val canvasAspect = layout.canvasWidth.toFloat() / layout.canvasHeight
    val photoWidthFraction = layout.imageWidth.toFloat() / layout.canvasWidth
    val photoHeightFraction = layout.imageHeight.toFloat() / layout.canvasHeight

    val boxAspect = maxWidth / maxHeight
    val width = if (canvasAspect > boxAspect) maxWidth else maxHeight * canvasAspect
    val height = if (canvasAspect > boxAspect) maxWidth / canvasAspect else maxHeight

    val latestFill by rememberUpdatedState(fill)
    val latestOnFillChange by rememberUpdatedState(onFillChange)
    val latestInteractive by rememberUpdatedState(interactive)
    val latestWidthFraction by rememberUpdatedState(photoWidthFraction)
    val latestHeightFraction by rememberUpdatedState(photoHeightFraction)
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    val fillColor = Color(fill.color)

    Canvas(
        modifier = Modifier
            .size(width, height)
            .semantics { contentDescription = "Canvas. Drag the photo to place it." }
            .pointerInput(Unit) {
                // Where the finger has taken the photo, before it settles on an edge or the
                // middle. Kept apart from the snapped value, or a photo resting on a snap point
                // could never be dragged off it.
                var free = latestFill
                detectDragGestures(
                    onDragStart = {
                        free = latestFill
                        dragging = latestInteractive
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onDrag = { change, amount ->
                        if (!latestInteractive || size.width == 0 || size.height == 0) return@detectDragGestures
                        change.consume()
                        free = free.dragged(
                            dx = amount.x / size.width,
                            dy = amount.y / size.height,
                            imageWidthFraction = latestWidthFraction,
                            imageHeightFraction = latestHeightFraction
                        )
                        val settled = latestFill.copy(
                            anchorX = snapAnchor(free.anchorX),
                            anchorY = snapAnchor(free.anchorY)
                        )
                        if (settled != latestFill) {
                            val arrived = (settled.anchorX != free.anchorX && settled.anchorX != latestFill.anchorX) ||
                                (settled.anchorY != free.anchorY && settled.anchorY != latestFill.anchorY)
                            if (arrived) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnFillChange(settled)
                        }
                    }
                )
            }
    ) {
        val corner = if (showFinal) 0f else 14.dp.toPx()
        val radius = CornerRadius(corner, corner)
        val photoWidth = (size.width * photoWidthFraction).roundToInt()
        val photoHeight = (size.height * photoHeightFraction).roundToInt()
        val photoLeft = ((size.width - photoWidth) * fill.anchorX).roundToInt()
        val photoTop = ((size.height - photoHeight) * fill.anchorY).roundToInt()

        val pane = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), radius)) }
        clipPath(pane) {
            if (showFinal) {
                drawRect(fillColor)
            } else {
                drawRect(Color(0xFF14171C))
                drawRect(fillColor.copy(alpha = 0.10f))
                drawRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.03f)))
                )
                // A soft diagonal sheen: the thing that reads as "glass" rather than "grey box"
                drawRect(
                    Brush.linearGradient(
                        0.30f to Color.Transparent,
                        0.47f to Color.White.copy(alpha = 0.08f),
                        0.60f to Color.Transparent,
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                )
            }
            drawImage(
                image = image,
                srcOffset = IntOffset(cropLeft, cropTop),
                srcSize = IntSize(cropWidth, cropHeight),
                dstOffset = IntOffset(photoLeft, photoTop),
                dstSize = IntSize(photoWidth, photoHeight),
                colorFilter = colorFilter,
                filterQuality = FilterQuality.Medium
            )
        }

        if (showFinal) {
            // Only so a canvas the colour of the screen behind it can still be made out
            drawRect(Color(0x33FFFFFF), style = Stroke(width = 1.dp.toPx()))
            return@Canvas
        }

        // The photo's own edge, then the rim of the pane
        drawRect(
            color = Color.White.copy(alpha = 0.40f),
            topLeft = Offset(photoLeft.toFloat(), photoTop.toFloat()),
            size = Size(photoWidth.toFloat(), photoHeight.toFloat()),
            style = Stroke(width = 1.dp.toPx())
        )
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.60f), Color.White.copy(alpha = 0.14f))),
            cornerRadius = radius,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Guides, while the photo is being dragged and has settled on the middle
        if (dragging) {
            val guide = OneUIBlue.copy(alpha = 0.85f)
            if (fill.anchorX == 0.5f && photoWidthFraction < 0.999f) {
                drawLine(guide, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.dp.toPx())
            }
            if (fill.anchorY == 0.5f && photoHeightFraction < 0.999f) {
                drawLine(guide, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.dp.toPx())
            }
        }
    }
}

/**
 * The Canvas tool's controls: the shape, how much room to leave, the fill colour, and two
 * icon-only buttons — put the photo back in the middle, and see the finished picture.
 *
 * [outputSize] is what will be saved, in pixels, or null when it is not known yet.
 */
@Composable
internal fun CanvasPanel(
    fill: CanvasFill,
    outputSize: Pair<Int, Int>?,
    recentColors: List<Int>,
    showFinal: Boolean,
    onShowFinalChange: (Boolean) -> Unit,
    onFillChange: (CanvasFill) -> Unit,
    onPickColor: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShapeChip(label = "Off", selected = !fill.isActive) { onFillChange(fill.copy(aspect = null)) }
            CANVAS_SHAPES.forEach { shape ->
                ShapeChip(label = shape.label, selected = fill.aspect == shape.aspect) {
                    onFillChange(fill.copy(aspect = shape.aspect))
                }
            }
        }

        if (!fill.isActive) {
            Text(
                text = "Pick a shape. The photo keeps its size; the rest of the canvas is filled.",
                color = Color.LightGray,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Room", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(52.dp))
            Slider(
                value = fill.room,
                onValueChange = { raw ->
                    // Settles on "snug" so the smallest canvas is easy to get back to
                    onFillChange(fill.copy(room = if (raw < CanvasFill.MIN_ROOM + 0.04f) CanvasFill.MIN_ROOM else raw))
                },
                valueRange = CanvasFill.MIN_ROOM..CanvasFill.MAX_ROOM,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = OneUIBlue,
                    inactiveTrackColor = Color.Gray
                ),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = outputSize?.let { "${it.first} × ${it.second}" }.orEmpty(),
                color = Color.LightGray,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .width(96.dp)
                    .padding(start = 8.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Green first, always: it is the default and never drops off the list
                Swatch(CanvasFill.DEFAULT_COLOR, fill.color == CanvasFill.DEFAULT_COLOR, "Green") {
                    onFillChange(fill.copy(color = CanvasFill.DEFAULT_COLOR))
                }
                recentColors.forEach { color ->
                    Swatch(color, fill.color == color, "#${colorToHex(color)}") {
                        onFillChange(fill.copy(color = color))
                    }
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color(0x99FFFFFF), CircleShape)
                        .clickable(onClickLabel = "Pick another colour", onClick = onPickColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Pick another colour", tint = Color.White)
                }
            }
            IconButton(onClick = { onFillChange(fill.copy(anchorX = 0.5f, anchorY = 0.5f)) }) {
                Icon(Icons.Rounded.FilterCenterFocus, contentDescription = "Centre the photo", tint = Color.White)
            }
            IconButton(onClick = { onShowFinalChange(!showFinal) }) {
                Icon(
                    imageVector = if (showFinal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (showFinal) "Back to the working view" else "Show the finished picture",
                    tint = if (showFinal) OneUIBlue else Color.White
                )
            }
        }
    }
}

@Composable
private fun ShapeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) OneUIBlue.copy(alpha = 0.35f) else Color.Transparent)
            .border(1.dp, if (selected) OneUIBlue else Color(0x66FFFFFF), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

@Composable
private fun Swatch(color: Int, selected: Boolean, name: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) Color.White else Color(0x66FFFFFF), CircleShape)
            .padding(if (selected) 5.dp else 1.dp)
            .clip(CircleShape)
            .background(Color(color))
            .clickable(onClickLabel = "Fill with $name", onClick = onClick)
            .semantics { contentDescription = "Fill colour $name" }
    )
}

/**
 * Any colour: a square of shades for the chosen hue, a strip of hues, and the hex code for
 * typing one in exactly. [onPick] is called with an opaque colour.
 */
@Composable
internal fun ColorPickerDialog(
    initialColor: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val start = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor, it) }
    }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val picked = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
    // What is in the text box. Follows the sliders unless the user is part-way through typing.
    var hexText by remember { mutableStateOf(colorToHex(initialColor)) }

    fun setFromControls(newHue: Float = hue, newSaturation: Float = saturation, newValue: Float = value) {
        hue = newHue
        saturation = newSaturation
        value = newValue
        hexText = colorToHex(android.graphics.Color.HSVToColor(floatArrayOf(newHue, newSaturation, newValue)))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        titleContentColor = DarkTextPrimary,
        title = { Text("Fill colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Shades of the hue: more colour to the right, darker downwards
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.5f)
                        .clip(RoundedCornerShape(12.dp))
                        .semantics { contentDescription = "Shade" }
                        .pointerInput(Unit) {
                            detectTapGestures { at ->
                                setFromControls(
                                    newSaturation = (at.x / size.width).coerceIn(0f, 1f),
                                    newValue = 1f - (at.y / size.height).coerceIn(0f, 1f)
                                )
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                setFromControls(
                                    newSaturation = (change.position.x / size.width).coerceIn(0f, 1f),
                                    newValue = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                )
                            }
                        }
                ) {
                    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val at = Offset(saturation * size.width, (1f - value) * size.height)
                    drawCircle(Color.Black, radius = 11.dp.toPx(), center = at, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, radius = 11.dp.toPx(), center = at, style = Stroke(1.5.dp.toPx()))
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .semantics { contentDescription = "Hue" }
                        .pointerInput(Unit) {
                            detectTapGestures { at ->
                                setFromControls(newHue = (at.x / size.width).coerceIn(0f, 1f) * 360f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                setFromControls(newHue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f)
                            }
                        }
                ) {
                    drawRect(Brush.horizontalGradient(HUE_STOPS))
                    val x = hue / 360f * size.width
                    drawLine(Color.Black, Offset(x, 0f), Offset(x, size.height), strokeWidth = 5.dp.toPx())
                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.5.dp.toPx())
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(picked))
                            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { typed ->
                            val cleaned = typed.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                            hexText = cleaned
                            hexToColor(cleaned)?.let { color ->
                                val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
                                hue = hsv[0]
                                saturation = hsv[1]
                                value = hsv[2]
                            }
                        },
                        singleLine = true,
                        prefix = { Text("#") },
                        label = { Text("Hex") },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = DarkTextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = DarkTextPrimary,
                            unfocusedTextColor = DarkTextPrimary,
                            focusedPrefixColor = DarkTextPrimary,
                            unfocusedPrefixColor = DarkTextPrimary,
                            unfocusedLabelColor = Color.LightGray
                        ),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(picked or 0xFF000000.toInt()) }) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Red round to red, through every hue. */
private val HUE_STOPS: List<Color> =
    (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }
