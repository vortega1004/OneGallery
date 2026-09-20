package com.onegallery.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onegallery.app.domain.AlbumSort
import com.onegallery.app.domain.MediaSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The user's chosen sort orders, as they are held in memory.
 *
 * Media sort is *per album*. Different folders want different orders — a camera roll by date,
 * scanned documents by name — and forcing one order on all of them was the complaint this
 * exists to answer. [defaultMediaSort] covers every album the user has never explicitly sorted,
 * and it tracks the last choice they made anywhere, so a preference expressed once carries to
 * folders they have not visited without overriding the ones they have deliberately set.
 *
 * Album sort is a single value: there is only one list of albums.
 */
data class SortPreferences(
    val albumSort: AlbumSort = AlbumSort.DEFAULT,
    val defaultMediaSort: MediaSort = MediaSort.DEFAULT,
    val mediaSortByAlbum: Map<String, MediaSort> = emptyMap()
) {
    /** [albumId] is null for the flat Pictures tab, which gets its own remembered order. */
    fun mediaSortFor(albumId: String?): MediaSort =
        mediaSortByAlbum[albumId ?: ALL_MEDIA_KEY] ?: defaultMediaSort

    companion object {
        /** Stands in for "no album" so the Pictures tab can be stored like any other bucket. */
        const val ALL_MEDIA_KEY = "\u0000all"
    }
}

/**
 * Persists [SortPreferences] across launches.
 *
 * Enum constants are stored by [Enum.name] and parsed defensively: renaming or deleting a sort
 * option must degrade to the default, not crash on the next launch with a stored value that no
 * longer resolves.
 */
class SortPreferencesRepository(context: Context) {

    private val dataStore: DataStore<Preferences> = context.applicationContext.sortDataStore

    val preferences: Flow<SortPreferences> = dataStore.data
        // A corrupt or unreadable file should cost the user their sort order, nothing more
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { stored ->
            SortPreferences(
                albumSort = stored[ALBUM_SORT].toEnumOr(AlbumSort.DEFAULT, AlbumSort::valueOf),
                defaultMediaSort = stored[MEDIA_SORT_DEFAULT]
                    .toEnumOr(MediaSort.DEFAULT, MediaSort::valueOf),
                mediaSortByAlbum = stored.asMap()
                    .mapNotNull { (key, value) ->
                        val albumId = key.name.removePrefixOrNull(MEDIA_SORT_ALBUM_PREFIX)
                        val sort = (value as? String)
                            ?.toEnumOrNull(MediaSort::valueOf)
                        if (albumId != null && sort != null) albumId to sort else null
                    }
                    .toMap()
            )
        }

    suspend fun setAlbumSort(sort: AlbumSort) {
        dataStore.edit { it[ALBUM_SORT] = sort.name }
    }

    /**
     * Records [sort] for [albumId] and makes it the fallback for albums with no choice of their
     * own. Both in one write, so the two can never disagree after a crash mid-update.
     */
    suspend fun setMediaSort(albumId: String?, sort: MediaSort) {
        val key = albumId ?: SortPreferences.ALL_MEDIA_KEY
        dataStore.edit { prefs ->
            prefs[mediaSortKeyFor(key)] = sort.name
            prefs[MEDIA_SORT_DEFAULT] = sort.name
        }
    }

    private companion object {
        val ALBUM_SORT = stringPreferencesKey("album_sort")
        val MEDIA_SORT_DEFAULT = stringPreferencesKey("media_sort_default")
        const val MEDIA_SORT_ALBUM_PREFIX = "media_sort_album:"

        fun mediaSortKeyFor(albumId: String) =
            stringPreferencesKey(MEDIA_SORT_ALBUM_PREFIX + albumId)

        fun String.removePrefixOrNull(prefix: String): String? =
            if (startsWith(prefix)) removePrefix(prefix).takeIf { it.isNotEmpty() } else null

        inline fun <T : Enum<T>> String?.toEnumOrNull(valueOf: (String) -> T): T? =
            this?.let { runCatching { valueOf(it) }.getOrNull() }

        inline fun <T : Enum<T>> String?.toEnumOr(fallback: T, valueOf: (String) -> T): T =
            toEnumOrNull(valueOf) ?: fallback
    }
}

private val Context.sortDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sort_preferences"
)
