package com.onegallery.app.data

import com.onegallery.app.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMoverRulesTest {

    @Test fun `photos may live under DCIM and Pictures only`() {
        assertTrue(destinationAllows(MediaType.IMAGE, "DCIM/Camera/"))
        assertTrue(destinationAllows(MediaType.IMAGE, "Pictures/Trips/"))
        assertTrue(destinationAllows(MediaType.GIF, "Pictures/"))
        assertFalse(destinationAllows(MediaType.IMAGE, "Movies/Clips/"))
        assertFalse(destinationAllows(MediaType.IMAGE, "Download/"))
    }

    @Test fun `videos may also live under Movies`() {
        assertTrue(destinationAllows(MediaType.VIDEO, "Movies/Clips/"))
        assertTrue(destinationAllows(MediaType.VIDEO, "DCIM/Camera/"))
        assertTrue(destinationAllows(MediaType.VIDEO, "Pictures/Trips/"))
        assertFalse(destinationAllows(MediaType.VIDEO, "Music/"))
    }

    @Test fun `the folder check ignores case and stray slashes`() {
        assertTrue(destinationAllows(MediaType.IMAGE, "/pictures/Trips"))
        assertTrue(destinationAllows(MediaType.IMAGE, "dcim/"))
    }

    @Test fun `with All files access any shared folder is open`() {
        assertTrue(destinationAllows(MediaType.IMAGE, "Download/", anyFolder = true))
        assertTrue(destinationAllows(MediaType.IMAGE, "Documents/Scans/", anyFolder = true))
        assertTrue(destinationAllows(MediaType.IMAGE, "MyStuff/", anyFolder = true))
        assertTrue(destinationAllows(MediaType.VIDEO, "Music/Art/", anyFolder = true))
        assertTrue(destinationAllows(MediaType.IMAGE, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", anyFolder = true))
    }

    @Test fun `some places stay closed even with All files access`() {
        assertFalse(destinationAllows(MediaType.IMAGE, "/", anyFolder = true))
        assertFalse(destinationAllows(MediaType.IMAGE, "Android/data/com.example/", anyFolder = true))
        assertFalse(destinationAllows(MediaType.IMAGE, "Android/obb/com.example/", anyFolder = true))
    }

    @Test fun `relative paths take MediaStore's own form`() {
        assertEquals("DCIM/Camera/", normalizeRelativePath("DCIM/Camera"))
        assertEquals("DCIM/Camera/", normalizeRelativePath("/DCIM/Camera/"))
        assertEquals("Pictures/", normalizeRelativePath(" Pictures "))
        assertEquals("", normalizeRelativePath("/"))
    }

    @Test fun `a clash gets the first free numbered name`() {
        assertEquals("IMG_1.jpg", uniqueName("IMG_1.jpg", setOf("img_2.jpg")))
        assertEquals("IMG_1 (1).jpg", uniqueName("IMG_1.jpg", setOf("img_1.jpg")))
        assertEquals("IMG_1 (3).jpg", uniqueName("IMG_1.jpg", setOf("img_1.jpg", "img_1 (1).jpg", "img_1 (2).jpg")))
    }

    @Test fun `only the last dot starts the extension`() {
        assertEquals("clip.final (1).mp4", uniqueName("clip.final.mp4", setOf("clip.final.mp4")))
        assertEquals("README (1)", uniqueName("README", setOf("readme")))
        // A leading dot is part of the name, not an extension
        assertEquals(".nomedia (1)", uniqueName(".nomedia", setOf(".nomedia")))
    }

    @Test fun `clashes are case-insensitive, as shared storage is`() {
        assertEquals("Photo (1).JPG", uniqueName("Photo.JPG", setOf("photo.jpg")))
    }

    @Test fun `new albums go under Pictures with unusable characters removed`() {
        assertEquals("Pictures/Trips/", newAlbumPath("Trips"))
        assertEquals("Pictures/Summer 2026/", newAlbumPath("  Summer 2026 "))
        assertEquals("Pictures/ab/", newAlbumPath("a/b"))
        assertEquals("Pictures/What/", newAlbumPath("What?*"))
        assertNull(newAlbumPath("   "))
        assertNull(newAlbumPath("..."))
    }
}
