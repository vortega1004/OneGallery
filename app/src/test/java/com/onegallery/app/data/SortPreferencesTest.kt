package com.onegallery.app.data

import com.onegallery.app.domain.MediaSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Resolution rules for the per-album sort. The storage layer needs an instrumented test to say
 * anything useful; this covers the part that decides which order a given screen gets, which is
 * where the behaviour people actually notice lives.
 */
class SortPreferencesTest {

    @Test
    fun `an album with no stored choice follows the default`() {
        val prefs = SortPreferences(defaultMediaSort = MediaSort.NAME_ASC)

        assertEquals(MediaSort.NAME_ASC, prefs.mediaSortFor("bucket-1"))
    }

    @Test
    fun `an album with its own choice keeps it when the default moves`() {
        val prefs = SortPreferences(
            defaultMediaSort = MediaSort.NAME_ASC,
            mediaSortByAlbum = mapOf("screenshots" to MediaSort.OLDEST_FIRST)
        )

        assertEquals(MediaSort.OLDEST_FIRST, prefs.mediaSortFor("screenshots"))
        assertEquals(MediaSort.NAME_ASC, prefs.mediaSortFor("camera"))
    }

    @Test
    fun `the Pictures tab is addressed by null and has its own slot`() {
        val prefs = SortPreferences(
            defaultMediaSort = MediaSort.NAME_ASC,
            mediaSortByAlbum = mapOf(SortPreferences.ALL_MEDIA_KEY to MediaSort.ADDED_NEWEST)
        )

        assertEquals(MediaSort.ADDED_NEWEST, prefs.mediaSortFor(null))
        // …and setting it must not leak into real albums
        assertEquals(MediaSort.NAME_ASC, prefs.mediaSortFor("camera"))
    }

    @Test
    fun `the Pictures tab slot cannot collide with a real bucket id`() {
        // MediaStore bucket ids are decimal strings, so a key containing NUL is unreachable
        assertNotEquals(SortPreferences.ALL_MEDIA_KEY, "")
        assertEquals(true, SortPreferences.ALL_MEDIA_KEY.any { it == '\u0000' })
    }

    @Test
    fun `defaults apply when nothing has ever been stored`() {
        val prefs = SortPreferences()

        assertEquals(MediaSort.DEFAULT, prefs.mediaSortFor(null))
        assertEquals(MediaSort.DEFAULT, prefs.mediaSortFor("anything"))
    }
}
