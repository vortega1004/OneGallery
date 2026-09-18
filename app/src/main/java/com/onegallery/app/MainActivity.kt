package com.onegallery.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.onegallery.app.data.MediaStoreRepository
import com.onegallery.app.ui.grid.GalleryGridScreen
import com.onegallery.app.ui.theme.DarkBackground
import com.onegallery.app.ui.theme.OneGalleryTheme
import com.onegallery.app.ui.viewer.MediaViewerScreen

class MainActivity : ComponentActivity() {

    private lateinit var repository: MediaStoreRepository
    private var hasStoragePermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasStoragePermission = permissions.values.any { it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MediaStoreRepository(applicationContext)

        checkAndRequestPermissions()

        setContent {
            OneGalleryTheme {
                if (hasStoragePermission) {
                    GalleryApp(repository = repository)
                } else {
                    PermissionRequestScreen {
                        checkAndRequestPermissions()
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            hasStoragePermission = true
        } else {
            permissionLauncher.launch(permissions)
        }
    }
}

@Composable
fun GalleryApp(repository: MediaStoreRepository) {
    val mediaItems by repository.observeMediaItems().collectAsState(initial = emptyList())
    var selectedItemIndex by remember { mutableIntStateOf(-1) }

    if (selectedItemIndex >= 0 && mediaItems.isNotEmpty()) {
        MediaViewerScreen(
            mediaItems = mediaItems,
            initialIndex = selectedItemIndex,
            onBack = { selectedItemIndex = -1 }
        )
    } else {
        GalleryGridScreen(
            mediaItems = mediaItems,
            onItemClick = { index ->
                selectedItemIndex = index
            }
        )
    }
}

@Composable
fun PermissionRequestScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Storage permission is required to view your photos and videos.",
            color = Color.White,
            fontSize = 16.sp
        )
    }
}
