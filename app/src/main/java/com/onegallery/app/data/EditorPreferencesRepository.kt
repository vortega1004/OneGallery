package com.onegallery.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onegallery.app.ui.editor.decodeColors
import com.onegallery.app.ui.editor.encodeColors
import com.onegallery.app.ui.editor.rememberedColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * What the photo editor remembers between uses: the last few canvas fill colours.
 *
 * Only the *list* is kept. The colour the editor opens with is always the default green — the
 * list is there to get back to a colour, not to change what "default" means.
 */
class EditorPreferencesRepository(context: Context) {

    private val dataStore: DataStore<Preferences> = context.applicationContext.editorDataStore

    /** Newest first, at most five, never the default green. */
    val recentFillColors: Flow<List<Int>> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { stored -> decodeColors(stored[RECENT_FILL_COLORS].orEmpty()) }

    suspend fun rememberFillColor(color: Int) {
        dataStore.edit { stored ->
            val current = decodeColors(stored[RECENT_FILL_COLORS].orEmpty())
            stored[RECENT_FILL_COLORS] = encodeColors(rememberedColors(current, color))
        }
    }

    private companion object {
        val RECENT_FILL_COLORS = stringPreferencesKey("recent_fill_colors")
    }
}

private val Context.editorDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "editor_preferences"
)
