package com.onegallery.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortDisplayNameTest {

    @Test
    fun `keeps the last six characters before the extension`() {
        assertEquals("…217123", shortDisplayName("PXL_20260917_142217123.mp4"))
    }

    @Test
    fun `a copy is told apart by its suffix`() {
        assertEquals("…23 (2)", shortDisplayName("PXL_20260917_142217123 (2).mp4"))
    }

    @Test
    fun `a name that already fits is shown whole, without an ellipsis`() {
        assertEquals("IMG_01", shortDisplayName("IMG_01.jpg"))
        assertEquals("a", shortDisplayName("a.png"))
    }

    @Test
    fun `only the last dot starts the extension`() {
        assertEquals("….final", shortDisplayName("holiday.clip.final.mp4"))
    }

    @Test
    fun `a name with no extension, or nothing but one, is not emptied`() {
        assertEquals("…ADME_2", shortDisplayName("README_2"))
        assertEquals("…omedia", shortDisplayName(".nomedia"))
    }
}
