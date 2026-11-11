package com.onegallery.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onegallery.app.domain.AlbumLayout
import com.onegallery.app.domain.decodeAlbumLayout
import com.onegallery.app.domain.encode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Persists the user's arrangement of the album list ([AlbumLayout]) across launches.
 *
 * Its own file rather than a key beside the sort preferences: this one is rewritten on every
 * drag and group edit, and a corrupt write should cost the arrangement, not the sort orders too.
 */
class AlbumLayoutRepository(context: Context) {

    private val dataStore: DataStore<Preferences> = context.applicationContext.albumLayoutDataStore

    /** Emits an empty layout when nothing has been saved yet. */
    val layout: Flow<AlbumLayout> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { stored -> decodeAlbumLayout(stored[LAYOUT].orEmpty()) }

    suspend fun save(layout: AlbumLayout) {
        dataStore.edit { it[LAYOUT] = layout.encode() }
    }

    private companion object {
        val LAYOUT = stringPreferencesKey("layout")
    }
}

private val Context.albumLayoutDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "album_layout"
)
