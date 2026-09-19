package com.onegallery.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.onegallery.app.domain.AlbumSort
import com.onegallery.app.domain.MediaSort
import com.onegallery.app.domain.forAlbum
import com.onegallery.app.domain.sortedBy
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.ui.grid.GalleryGridScreen
import com.onegallery.app.ui.grid.TAB_PICTURES
import com.onegallery.app.ui.theme.DarkBackground
import com.onegallery.app.ui.theme.OneGalleryTheme
import com.onegallery.app.ui.viewer.MediaViewerScreen

class MainActivity : ComponentActivity() {

    private val viewModel: GalleryViewModel by viewModels()
    private var hasStoragePermission by mutableStateOf(false)

    // Set when another app opened a single image/video with us (ACTION_VIEW)
    private var viewIntentItem by mutableStateOf<MediaItem?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Re-check instead of trusting the result map: "Select photos" (Android 14+) reports the
        // full-access permissions as denied while still granting READ_MEDIA_VISUAL_USER_SELECTED.
        updatePermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The whole UI is dark (grid) or black (viewer), so always use light system bar icons
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        viewIntentItem = resolveViewIntent(intent)
        hasStoragePermission = hasMediaAccess()

        // A VIEW intent carries its own read grant, so it doesn't need the library permission
        if (!hasStoragePermission && viewIntentItem == null && savedInstanceState == null) {
            requestMediaPermissions()
        }

        setContent {
            OneGalleryTheme {
                val externalItem = viewIntentItem
                when {
                    externalItem != null -> {
                        MediaViewerScreen(
                            mediaItems = remember(externalItem) { listOf(externalItem) },
                            initialIndex = 0,
                            onBack = { finish() }
                        )
                    }
                    hasStoragePermission -> GalleryApp(viewModel = viewModel)
                    else -> {
                        PermissionRequestScreen(
                            onRequest = ::requestMediaPermissions,
                            onOpenSettings = ::openAppSettings
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Picks up access granted (or revoked) from the system settings screen
        updatePermissionState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewIntentItem = resolveViewIntent(intent)
    }

    private fun resolveViewIntent(intent: Intent?): MediaItem? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        return viewModel.repository.mediaItemFromUri(uri, intent.type)
    }

    private fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Full access, images-only, videos-only and partial ("Select photos") access all count. */
    private fun hasMediaAccess(): Boolean = mediaPermissions().any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun updatePermissionState() {
        val hasAccess = hasMediaAccess()
        if (hasAccess != hasStoragePermission) {
            hasStoragePermission = hasAccess
            if (hasAccess) viewModel.refresh()
        }
    }

    private fun requestMediaPermissions() {
        // ACCESS_MEDIA_LOCATION is a nice-to-have (GPS in EXIF); it never gates the gallery
        permissionLauncher.launch(mediaPermissions() + Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }
}

@Composable
fun GalleryApp(viewModel: GalleryViewModel) {
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    var selectedItemIndex by rememberSaveable { mutableIntStateOf(-1) }

    // Grid browsing state lives here, not in GalleryGridScreen: the viewer *replaces* the grid
    // in the composition, so state owned by the grid would be discarded every time a photo is
    // opened. Holding it here is what makes back-from-viewer return to the album you were in.
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_PICTURES) }
    var openedAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
    var columnCount by rememberSaveable { mutableIntStateOf(3) }

    // Grid scroll positions live here for the same reason as the state above: the grid is
    // disposed while the viewer is open, so a state remembered inside it would reset to the
    // top of the library every time the user opened a photo.
    val picturesGridState = rememberLazyGridState()
    val albumGridState = rememberLazyGridState()
    val albumListGridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // Which album the viewer was opened from; null means the flat Pictures grid.
    var viewerAlbumId by rememberSaveable { mutableStateOf<String?>(null) }

    // The item currently shown in the viewer, and the one the grid should return to on exit.
    // Tracked by id, not index, so a live MediaStore update that shifts positions cannot send
    // the grid to the wrong photo.
    var viewerVisibleItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var restoreToItemId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Hoisted with the rest of the browsing state so the chosen order survives opening a photo.
    var albumSort by rememberSaveable { mutableStateOf(AlbumSort.DEFAULT) }
    var mediaSort by rememberSaveable { mutableStateOf(MediaSort.DEFAULT) }

    // Scope the pager to the set the user was actually looking at, so swiping inside an album
    // stays inside that album. Kept as a bucket id rather than a list so it survives process
    // death and still tracks live MediaStore updates.
    // Must match GalleryGridScreen's list exactly — same filter, same sort. The viewer opens
    // by index, so any disagreement would open a different photo than the one tapped.
    val viewerItems = remember(mediaItems, viewerAlbumId, mediaSort) {
        mediaItems.forAlbum(viewerAlbumId).sortedBy(mediaSort)
    }

    val closeViewer = {
        // Hand the grid the photo the user actually ended on, which may be far from the one
        // they opened if they paged or scrubbed the filmstrip.
        restoreToItemId = viewerVisibleItemId
        selectedItemIndex = -1
        viewerAlbumId = null
        viewerVisibleItemId = null
    }

    // System back / back gesture returns to the grid instead of leaving the app
    BackHandler(enabled = selectedItemIndex >= 0) { closeViewer() }

    if (selectedItemIndex >= 0 && viewerItems.isNotEmpty()) {
        MediaViewerScreen(
            mediaItems = viewerItems,
            initialIndex = selectedItemIndex,
            onBack = closeViewer,
            onVisibleItemChange = { viewerVisibleItemId = it }
        )
    } else {
        GalleryGridScreen(
            mediaItems = mediaItems,
            selectedTab = selectedTab,
            onTabChange = { selectedTab = it },
            openedAlbumId = openedAlbumId,
            onOpenAlbum = { openedAlbumId = it },
            columnCount = columnCount,
            onColumnCountChange = { columnCount = it },
            picturesGridState = picturesGridState,
            albumGridState = albumGridState,
            restoreToItemId = restoreToItemId,
            onRestoreHandled = { restoreToItemId = null },
            albumListGridState = albumListGridState,
            albumSort = albumSort,
            // Changing the sort must jump back to the top. LazyGrid keys are stable, so it
            // otherwise anchors whatever item was first visible and follows it to its new
            // position — picking "Name (Z-A)" would leave you in the middle of the list.
            // Done here as an explicit event rather than a LaunchedEffect on the sort value,
            // which would also fire when returning from the viewer and fight the
            // restore-to-item scroll.
            onAlbumSortChange = {
                albumSort = it
                scope.launch { albumListGridState.scrollToTopAfterReorder() }
            },
            mediaSort = mediaSort,
            onMediaSortChange = {
                mediaSort = it
                scope.launch {
                    picturesGridState.scrollToTopAfterReorder()
                    albumGridState.scrollToTopAfterReorder()
                }
            },
            onItemClick = { albumId, index ->
                viewerAlbumId = albumId
                selectedItemIndex = index
            }
        )
    }
}

@Composable
fun PermissionRequestScreen(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .systemBarsPadding()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Storage permission is required to view your photos and videos.",
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
        Button(onClick = onRequest) {
            Text("Allow access")
        }
        // Once the system stops showing the dialog ("Don't ask again"), settings is the only way in
        TextButton(onClick = onOpenSettings) {
            Text("Open settings")
        }
    }
}

/**
 * Scrolls a grid back to the top after its list has been re-ordered.
 *
 * Waits one frame first. LazyGrid keys are stable, so on a re-order it re-anchors to whichever
 * item was previously first visible — and it does that during the *measure* pass, which runs
 * after this coroutine resumes. Scrolling immediately is silently undone; scrolling after the
 * frame lands.
 */
private suspend fun LazyGridState.scrollToTopAfterReorder() {
    withFrameNanos { }
    scrollToItem(0)
}
