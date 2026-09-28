package com.onegallery.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onegallery.app.data.MediaMover
import com.onegallery.app.data.MediaStoreRepository
import com.onegallery.app.data.MoveDestination
import com.onegallery.app.data.MoveResult
import com.onegallery.app.data.SortPreferences
import com.onegallery.app.data.SortPreferencesRepository
import com.onegallery.app.domain.AlbumSort
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.domain.MediaSort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the single live MediaStore subscription so it survives recomposition and configuration
 * changes instead of being rebuilt (observers re-registered, library re-queried) by the UI.
 *
 * Also owns the persisted sort preferences, for the same reason: they are read once and shared,
 * not re-read by whichever composable happens to need them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    val repository = MediaStoreRepository(application)

    /** Launch [MediaMover.writeRequest] first; call [move] only once the user has allowed it. */
    val mover = MediaMover(application)

    private val refreshRequests = MutableStateFlow(0)

    val mediaItems: StateFlow<List<MediaItem>> = refreshRequests
        .flatMapLatest { repository.observeMediaItems() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val sortPreferencesRepository = SortPreferencesRepository(application)

    /**
     * Starts at the defaults and is replaced by the stored values as soon as DataStore reads
     * them. That first frame with defaults is why the grid's scroll-to-top is wired to explicit
     * sort *events* rather than to changes in the sort value — see `GalleryApp`.
     */
    val sortPreferences: StateFlow<SortPreferences> = sortPreferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SortPreferences())

    fun setAlbumSort(sort: AlbumSort) {
        viewModelScope.launch { sortPreferencesRepository.setAlbumSort(sort) }
    }

    /** [albumId] is null when sorting the flat Pictures tab. */
    fun setMediaSort(albumId: String?, sort: MediaSort) {
        viewModelScope.launch { sortPreferencesRepository.setMediaSort(albumId, sort) }
    }

    /**
     * Moves [items] into [destination]; [onDone] runs on the main thread with the tally.
     *
     * Nothing here updates [mediaItems] directly: each move is a MediaStore update, the observer
     * sees it, and the library re-queries — the same path a move made in another app takes.
     */
    fun move(items: List<MediaItem>, destination: MoveDestination, onDone: (MoveResult) -> Unit) {
        viewModelScope.launch { onDone(mover.move(items, destination)) }
    }

    /** Re-subscribes and re-queries, e.g. after the granted media permissions changed. */
    fun refresh() {
        refreshRequests.value++
    }
}
