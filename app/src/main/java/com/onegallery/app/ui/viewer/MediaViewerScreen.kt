package com.onegallery.app.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.domain.MediaType
import com.onegallery.app.domain.shortDisplayName
import com.onegallery.app.ui.common.rememberFullSizeRequest
import com.onegallery.app.ui.common.rememberThumbnailRequest
import com.onegallery.app.ui.comfy.SendToComfySheet
import com.onegallery.app.ui.common.swallowTaps
import com.onegallery.app.ui.editor.PhotoEditorScreen
import com.onegallery.app.ui.filmstrip.FilmStripHeight
import com.onegallery.app.ui.filmstrip.FilmStripInfinityViewer
import com.onegallery.app.ui.video.VideoPlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * @param onVisibleItemChange reports the id of the item currently on screen, so the caller can
 *   restore the grid to it on exit. Reported continuously rather than on back, because back can
 *   arrive as a gesture, a system key or the toolbar button.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    mediaItems: List<MediaItem>,
    initialIndex: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onVisibleItemChange: (Long) -> Unit = {},
    onMoveRequest: (MediaItem) -> Unit = {},
    onTrashRequest: (MediaItem) -> Unit = {}
) {
    if (mediaItems.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, mediaItems.size - 1),
        pageCount = { mediaItems.size }
    )
    val coroutineScope = rememberCoroutineScope()
    var isOverlayVisible by remember { mutableStateOf(true) }
    var showDetailsSheet by remember { mutableStateOf(false) }
    // The top bar shows only the tail of the file name; tapping it spells the whole name out
    var showFullName by remember { mutableStateOf(false) }
    // The item the Send to ComfyUI sheet is open for, if it is open
    var comfyItem by remember { mutableStateOf<MediaItem?>(null) }
    // Videos autoplay, so they start silent. Held here rather than in the player so unmuting
    // once carries across every video in this viewer session.
    var isVideoMuted by rememberSaveable { mutableStateOf(true) }

    // The editor is an overlay on the viewer rather than a separate destination, so closing it
    // lands back on the same photo with the pager untouched. The item is kept separately from
    // the open flag so the editor still has something to draw while it animates out.
    var isEditorOpen by remember { mutableStateOf(false) }
    var editorItem by remember { mutableStateOf<MediaItem?>(null) }
    // Composed after GalleryApp's handler, so while the editor is open back closes the editor
    BackHandler(enabled = isEditorOpen) { isEditorOpen = false }
    val detailsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The pager re-clamps its page only on the next measure pass, so when the library shrinks
    // (file deleted elsewhere) currentPage can briefly point past the end of the new list.
    val currentPage = pagerState.currentPage.coerceIn(0, mediaItems.lastIndex)

    // --- Which item is on screen: by identity, never by page number --------------------------
    // The list can change under the viewer at any moment — a snapshot saved from the video being
    // watched, a file moved, a photo arriving from the camera. Everything that used to read the
    // page *number* then pointed at the wrong item until the pager caught up a frame later: the
    // video counted as "not the active page", was paused and its frame walk dropped, then became
    // active again, and activation means autoplay. That was "taking a snapshot starts the video",
    // and the filmstrip and top bar briefly following a neighbour.
    //
    // So the viewer keeps the id of the item on screen ([ViewerAnchor]). It moves to whatever page
    // settles after a swipe or a filmstrip scrub, in the same frame the page settles; a change to
    // the *list* leaves it alone, and the pager is asked to stay on that item wherever it now sits.
    val anchor = remember { ViewerAnchor(mediaItems, pagerState.settledPage) }
    val reanchorTo = anchor.onComposition(mediaItems, pagerState.settledPage)
    // requestScrollToPage is made for exactly this — holding a position steady through a data
    // change — and applies in the coming measure pass, so the frame that shows the new list
    // already shows it at the right page. Only on a list change: re-anchoring any time the page
    // and the remembered item disagree also fires the moment a swipe ends, and bounced the user
    // back to where they came from.
    if (reanchorTo != null && reanchorTo != pagerState.currentPage && !pagerState.isScrollInProgress) {
        pagerState.requestScrollToPage(reanchorTo)
    }
    val shownId = anchor.shownId
    val shownIndex = mediaItems.indexOfFirst { it.id == shownId }
    val latestShownIndex by rememberUpdatedState(shownIndex)
    // Mid-swipe the top bar follows the page under the finger; otherwise the anchored item
    val currentItem =
        if (pagerState.isScrollInProgress) mediaItems[currentPage]
        else mediaItems.getOrNull(shownIndex) ?: mediaItems[currentPage]
    val anchoredPage = if (shownIndex >= 0) shownIndex else currentPage

    // Keep the caller told which item is showing, so backing out can land the grid here even
    // if the user paged or scrubbed far from where they entered.
    LaunchedEffect(currentItem.id) { onVisibleItemChange(currentItem.id) }
    LaunchedEffect(shownId) { showFullName = false }

    // The picture area never changes size. The bottom band belongs to the chrome whether the
    // chrome is on screen or not, so showing and hiding it slides a bar over a layout that has
    // already settled instead of re-measuring the page under the user's thumb. Tapping to bring
    // the controls back used to resize the picture twice — once on the miss, once on the retry.
    //
    // Derived rather than measured, for the same reason: a measured band is zero until the bar's
    // first layout pass, which would put a jump right where the viewer opens.
    val reservedBottom = FilmStripHeight + ViewerActionRowHeight +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The same at the top: the status bar plus a thin bar of our own, and the picture starts
    // under both. The bar used to lie *over* the top of the picture and slide down across it
    // every time the chrome came back — on every filmstrip scrub after a frame walk.
    val reservedTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        ViewerTopBarHeight

    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = reservedTop, bottom = reservedBottom),
            key = { index -> mediaItems[index].id }
        ) { pageIndex ->
            val item = mediaItems[pageIndex]
            // By id: a page number can point at a different item for a frame after the list
            // changes, which used to pause and then autoplay the video being watched
            val isActivePage = item.id == shownId

            if (item.isVideo) {
                // Video controls share the viewer's overlay visibility, so one tap hides/shows both
                VideoPlayerView(
                    mediaItem = item,
                    modifier = Modifier
                        .fillMaxSize()
                        .swipeUpToReveal { showDetailsSheet = true },
                    isActivePage = isActivePage,
                    controlsVisible = isOverlayVisible,
                    onControlsVisibleChange = { isOverlayVisible = it },
                    // Zero: the page already ends above the bottom bar, so the player's controls
                    // only need their own margin from the edge of the page
                    controlsPadding = PaddingValues(0.dp),
                    isMuted = isVideoMuted,
                    onMutedChange = { isVideoMuted = it }
                )
            } else {
                // Zoomable image viewer
                ZoomableImageView(
                    mediaItem = item,
                    isActivePage = isActivePage,
                    onTap = { isOverlayVisible = !isOverlayVisible },
                    onSwipeUp = { showDetailsSheet = true }
                )
            }
        }

        // Top bar: one thin row in a band of its own above the picture (see reservedTop), so it
        // never covers the image. It fades with the rest of the chrome instead of sliding down
        // over the picture, and since it covers nothing it no longer steps aside for frame walks.
        AnimatedVisibility(
            visible = isOverlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .swallowTaps()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ViewerTopBarHeight)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TopBarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)

                        // Date and time on one line: two stacked lines do not fit a bar this thin
                        val takenAt = Date(currentItem.dateTaken)
                        Text(
                            text = "${dateFormat.format(takenAt)} · ${timeFormat.format(takenAt)}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(start = 2.dp)
                        )

                        // The tail of the file name: enough to tell two takes apart, short
                        // enough to stay out of the way. Tap for the whole name.
                        Box(
                            modifier = Modifier
                                // Shrinks before it pushes the buttons on the right off the bar
                                .weight(1f, fill = false)
                                .padding(start = 10.dp)
                                .height(ViewerTopBarHeight - 4.dp)
                                .background(Color(0x26FFFFFF), RoundedCornerShape(10.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = "Show the full file name"
                                ) { showFullName = !showFullName }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = shortDisplayName(currentItem.displayName),
                                color = Color.White,
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    TopBarIcon(Icons.Rounded.Info, "Details") { showDetailsSheet = true }
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        TopBarIcon(Icons.Rounded.MoreVert, "More") { menuOpen = true }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Move to album") },
                                onClick = {
                                    menuOpen = false
                                    onMoveRequest(currentItem)
                                }
                            )
                        }
                    }
                }

                // Asked for, so it may cover the top of the picture until tapped away
                if (showFullName) {
                    Text(
                        text = currentItem.displayName,
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xCC000000))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = "Hide the full file name"
                            ) { showFullName = false }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Bottom Controls Container: FilmStrip Scrubber + Action Bar
        AnimatedVisibility(
            visible = isOverlayVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Exactly the band the pager gave up, so the two cannot drift apart
                    .height(reservedBottom)
                    .background(Color(0xCC000000))
                    .swallowTaps()
                    .navigationBarsPadding()
            ) {
                // Samsung Filmstrip Infinity Scrubber
                FilmStripInfinityViewer(
                    mediaItems = mediaItems,
                    currentIndex = anchoredPage,
                    // Mirrors the swipe 1:1 while there is one; otherwise sits on the anchored item,
                    // so a list change cannot drag the strip to a neighbour for a frame
                    pagerPosition = {
                        if (pagerState.isScrollInProgress) {
                            pagerState.currentPage + pagerState.currentPageOffsetFraction
                        } else {
                            latestShownIndex.takeIf { it >= 0 }?.toFloat()
                                ?: pagerState.currentPage.toFloat()
                        }
                    },
                    onItemSelected = { targetIndex ->
                        // Snap, don't animate: the strip reports every thumbnail it passes while
                        // scrubbing, and the pager has to keep up with it 1:1
                        coroutineScope.launch {
                            pagerState.scrollToPage(targetIndex)
                        }
                    }
                )

                // Bottom action bar: Delete, Favorite, Edit, Share.
                // Delete is on the far left on purpose — the right-hand end is where a thumb
                // working the video controls comes to rest, and that is no place for it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ViewerActionRowHeight)
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Straight to the trash, no question asked: it can be undone from the
                    // message that follows and for thirty days from the trash itself. An item
                    // another app handed us is not in the library and is not ours to remove.
                    val canTrash = currentItem.id >= 0
                    IconButton(enabled = canTrash, onClick = { onTrashRequest(currentItem) }) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Move to trash",
                            tint = if (canTrash) Color.White else Color(0x61FFFFFF)
                        )
                    }
                    IconButton(onClick = { /* Toggle favorite */ }) {
                        Icon(
                            imageVector = if (currentItem.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (currentItem.isFavorite) Color.Red else Color.White
                        )
                    }
                    // Stills only: video trimming and animated GIFs are out of scope for the
                    // basic editor, so the button greys out instead of failing on tap
                    val canEdit = currentItem.mediaType == MediaType.IMAGE
                    IconButton(
                        enabled = canEdit,
                        onClick = {
                            editorItem = currentItem
                            isEditorOpen = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit",
                            tint = if (canEdit) Color.White else Color(0x61FFFFFF)
                        )
                    }
                    // Share means "send to ComfyUI" here, and only that: the owner sends photos
                    // to his own workflows, not to other apps. The sheet lists the gateway's jobs.
                    IconButton(onClick = { comfyItem = currentItem }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = "Send to ComfyUI",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // Photo editor overlay
        AnimatedVisibility(
            visible = isEditorOpen,
            enter = fadeIn() + slideInVertically { it / 8 },
            exit = fadeOut() + slideOutVertically { it / 8 }
        ) {
            editorItem?.let { item ->
                PhotoEditorScreen(
                    mediaItem = item,
                    onClose = { isEditorOpen = false },
                    // The copy shows up in the grid/pager through the MediaStore observer
                    onSaved = { isEditorOpen = false }
                )
            }
        }

        comfyItem?.let { item ->
            SendToComfySheet(item = item, onDismiss = { comfyItem = null })
        }

        // Swipe-up / Info Sheet
        if (showDetailsSheet) {
            MediaDetailsSheet(
                mediaItem = currentItem,
                sheetState = detailsSheetState,
                onDismiss = { showDetailsSheet = false }
            )
        }
    }
}

@Composable
private fun ZoomableImageView(
    mediaItem: MediaItem,
    isActivePage: Boolean,
    onTap: () -> Unit,
    onSwipeUp: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val zoomScope = rememberCoroutineScope()

    // A page shows the (usually already cached) thumbnail first and only asks for the
    // full-resolution decode once it has been the active page for a moment. Pages merely passed
    // while scrubbing the filmstrip, or peeking in during a swipe, never start a 12 MP decode.
    // Once upgraded a page stays upgraded, so flicking back to it doesn't re-blur.
    var loadFullRes by remember { mutableStateOf(false) }
    LaunchedEffect(isActivePage) {
        if (isActivePage && !loadFullRes) {
            delay(FULL_RES_DELAY_MS)
            loadFullRes = true
        }
    }
    val thumbnailRequest = rememberThumbnailRequest(mediaItem.uri)
    val fullSizeRequest = rememberFullSizeRequest(mediaItem.uri)

    // Swiping away from a zoomed photo shouldn't leave it zoomed when the user comes back
    LaunchedEffect(isActivePage) {
        if (!isActivePage) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Swipe up opens the details sheet. Only at 1x: once zoomed, a vertical drag pans
            // the photo (and at the photo's edge it should do nothing, not pop a sheet).
            // First in the chain = outermost = sees each event after the zoom loop below has
            // had its chance to consume it.
            .swipeUpToReveal(enabled = scale == 1f, onSwipeUp = onSwipeUp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        val startScale = scale
                        val startX = offsetX
                        val startY = offsetY
                        val targetScale = if (scale > 1.2f) 1f else 2.5f
                        zoomScope.launch {
                            animate(
                                initialValue = 0f,
                                targetValue = 1f,
                                animationSpec = tween(DOUBLE_TAP_ZOOM_MS, easing = FastOutSlowInEasing)
                            ) { fraction, _ ->
                                scale = lerp(startScale, targetScale, fraction)
                                offsetX = lerp(startX, 0f, fraction)
                                offsetY = lerp(startY, 0f, fraction)
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                // detectTransformGestures consumes every drag past touch slop, which starves the
                // enclosing HorizontalPager of its swipe. This loop only claims the gesture when
                // it really zooms or pans the image; a one-finger drag at 1x, or a push past the
                // horizontal edge of a zoomed image, stays unconsumed so the pager can page.
                awaitEachGesture {
                    var accumulatedZoom = 1f
                    var accumulatedPan = Offset.Zero
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop

                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (!pastTouchSlop) {
                                accumulatedZoom *= zoomChange
                                accumulatedPan += panChange
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1 - accumulatedZoom) * centroidSize
                                if (zoomMotion > touchSlop || accumulatedPan.getDistance() > touchSlop) {
                                    pastTouchSlop = true
                                }
                            }

                            if (pastTouchSlop) {
                                val isMultiTouch = event.changes.count { it.pressed } > 1
                                val newScale = (scale * zoomChange).coerceIn(1f, 4f)
                                val maxOffsetX = (newScale - 1f) * size.width / 2f
                                val maxOffsetY = (newScale - 1f) * size.height / 2f
                                val newOffsetX = (offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                val newOffsetY = (offsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)

                                val pushesPastHorizontalEdge = newOffsetX == offsetX &&
                                    abs(panChange.x) > abs(panChange.y)
                                val movesImage = newScale != scale ||
                                    newOffsetX != offsetX ||
                                    newOffsetY != offsetY

                                if (isMultiTouch || (movesImage && !pushesPastHorizontalEdge)) {
                                    scale = newScale
                                    offsetX = newOffsetX
                                    offsetY = newOffsetY
                                    event.changes.forEach {
                                        if (it.positionChanged()) it.consume()
                                    }
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = if (loadFullRes) fullSizeRequest else thumbnailRequest,
            contentDescription = mediaItem.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
    }
}

/**
 * The action row under the filmstrip: one 48 dp icon target plus a little breathing room.
 *
 * Fixed rather than wrapped, because [MediaViewerScreen] subtracts this from the page before
 * either is laid out. The row is given this height explicitly so the two stay in agreement.
 */
private val ViewerActionRowHeight = 52.dp

/**
 * The top bar's own height, under the status bar. Kept thinner than the status bar itself at
 * the owner's request: it is a strip of information, and every dp of it is taken from the
 * picture. Fixed for the same reason as [ViewerActionRowHeight].
 */
private val ViewerTopBarHeight = 24.dp

/**
 * An icon button for the thin top bar. Not `IconButton`, which insists on a 48 dp square and
 * would make the bar twice as tall; wider than it is tall instead, to stay easy to hit.
 */
@Composable
private fun TopBarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = ViewerTopBarHeight)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/**
 * Which item the viewer is showing, by id, across changes to the list underneath it.
 *
 * Driven from composition so that it is never a frame behind: a page that settles is adopted in
 * the composition that sees it settle, and a new list is recognised in the composition that
 * receives it. An effect-driven version lagged a frame, and in that frame the page that had just
 * been swiped to looked like a stray and was pulled back.
 */
private class ViewerAnchor(initialList: List<MediaItem>, initialSettledPage: Int) {
    private var list = initialList
    private var settledPage = initialSettledPage
    var shownId by mutableStateOf(initialList[initialSettledPage.coerceIn(0, initialList.lastIndex)].id)
        private set

    /**
     * Returns the page the pager must move to so that the shown item stays on screen, when the
     * list has just changed and the item now sits elsewhere; otherwise null.
     */
    fun onComposition(newList: List<MediaItem>, newSettledPage: Int): Int? {
        if (newList !== list) {
            list = newList
            // Whatever settledPage says now still refers to the old list — not a navigation
            settledPage = newSettledPage
            val index = newList.indexOfFirst { it.id == shownId }
            if (index < 0) {
                // The item itself left (moved out of this album, deleted): show what replaced it
                shownId = newList[newSettledPage.coerceIn(0, newList.lastIndex)].id
                return null
            }
            return index
        }
        if (newSettledPage != settledPage) {
            settledPage = newSettledPage
            newList.getOrNull(newSettledPage)?.let { shownId = it.id }
        }
        return null
    }
}

private const val FULL_RES_DELAY_MS = 120L
private const val DOUBLE_TAP_ZOOM_MS = 220
