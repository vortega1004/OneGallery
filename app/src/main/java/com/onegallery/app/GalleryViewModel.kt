package com.onegallery.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onegallery.app.data.MediaStoreRepository
import com.onegallery.app.domain.MediaItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Owns the single live MediaStore subscription so it survives recomposition and configuration
 * changes instead of being rebuilt (observers re-registered, library re-queried) by the UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    val repository = MediaStoreRepository(application)

    private val refreshRequests = MutableStateFlow(0)

    val mediaItems: StateFlow<List<MediaItem>> = refreshRequests
        .flatMapLatest { repository.observeMediaItems() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** Re-subscribes and re-queries, e.g. after the granted media permissions changed. */
    fun refresh() {
        refreshRequests.value++
    }
}
