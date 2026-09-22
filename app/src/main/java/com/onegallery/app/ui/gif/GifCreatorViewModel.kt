package com.onegallery.app.ui.gif

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.ui.editor.PhotoEditRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Draft and export survive Activity recreation. Only bounded frame bitmaps are decoded. */
class GifCreatorViewModel(application: Application) : AndroidViewModel(application) {
    var draft by mutableStateOf<GifDraft?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var savedUri by mutableStateOf<Uri?>(null)
        private set
    private var nextKey = 0L
    private var job: Job? = null

    fun open(items: List<MediaItem>) {
        if (items.size < 2 || draft != null) return
        // The grid caps the selection; this keeps the ceiling true for any other caller.
        val chosen = items.take(MAX_GIF_FRAMES)
        draft = GifDraft(chosen.map { GifFrame(nextKey++, it.uri.toString(), it.displayName) })
        loading = true
        error = null
        savedUri = null
        job = viewModelScope.launch {
            try {
                val aspect = withContext(Dispatchers.IO) {
                    val bitmap = PhotoEditRenderer.decode(getApplication(), chosen.first().uri, 720)
                    try { bitmap.width.toFloat() / bitmap.height } finally { bitmap.recycle() }
                }
                draft = draft?.copy(canvasAspect = aspect)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't open the first image. Return to selection and choose another."
            } finally {
                loading = false
            }
        }
    }

    fun update(change: (GifDraft) -> GifDraft) {
        if (!saving && !loading) draft = draft?.let(change)
    }

    fun duplicate() = update { it.duplicateActive(nextKey++) }
    fun remove() = update { it.removeActive() }

    fun close() {
        if (saving) return
        job?.cancel()
        draft = null
        error = null
        savedUri = null
    }

    fun save() {
        val snapshot = draft ?: return
        if (loading || saving) return
        saving = true
        progress = 0f
        error = null
        job = viewModelScope.launch {
            try {
                savedUri = GifRenderer.save(getApplication(), snapshot) { completed, total ->
                    // Compose state is updated on the main dispatcher, not the encoder thread.
                    viewModelScope.launch { progress = completed.toFloat() / total }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't save the GIF. Check free space and access to the selected images, then retry."
            } finally {
                saving = false
            }
        }
    }

    fun cancelSave() { job?.cancel() }
    fun dismissError() { error = null }
}
