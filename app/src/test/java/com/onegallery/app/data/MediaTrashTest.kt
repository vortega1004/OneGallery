package com.onegallery.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTrashTest {

    private val day = 24L * 60 * 60 * 1000

    @Test fun `a trashed file gets its own name back`() {
        assertEquals("IMG_20260810_160000.jpg", originalName(".trashed-1796949918-IMG_20260810_160000.jpg"))
    }

    @Test fun `a name that merely looks similar is left alone`() {
        assertEquals("trashed-123-photo.jpg", originalName("trashed-123-photo.jpg"))
        assertEquals(".trashed-notanumber-photo.jpg", originalName(".trashed-notanumber-photo.jpg"))
        assertEquals("holiday.jpg", originalName("holiday.jpg"))
    }

    @Test fun `only the prefix is removed`() {
        assertEquals(
            "copy of .trashed-1-x.jpg",
            originalName(".trashed-1796949918-copy of .trashed-1-x.jpg")
        )
    }

    @Test fun `a prefix with nothing after it is not turned into an empty name`() {
        assertEquals(".trashed-1796949918-", originalName(".trashed-1796949918-"))
    }

    @Test fun `time left rounds up to whole days`() {
        assertEquals("30 days", expiryLabel(30 * day, 0))
        assertEquals("30 days", expiryLabel(30 * day, 1))
        assertEquals("2 days", expiryLabel(day + 1, 0))
        assertEquals("1 day", expiryLabel(day, 0))
        assertEquals("1 day", expiryLabel(60_000, 0))
    }

    @Test fun `an item past its date says today, and an unknown date says nothing`() {
        assertEquals("today", expiryLabel(1_000, 2_000))
        assertEquals("", expiryLabel(0, 2_000))
    }
}
