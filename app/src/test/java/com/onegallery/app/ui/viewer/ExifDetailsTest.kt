package com.onegallery.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ExifDetailsTest {

    @Test
    fun `fast shutter speeds are shown as a fraction`() {
        assertEquals("1/125 s", formatExposureTime(0.008))
        assertEquals("1/2 s", formatExposureTime(0.5))
    }

    @Test
    fun `exposures of a second or longer are shown in seconds`() {
        assertEquals("1.0 s", formatExposureTime(1.0))
        assertEquals("2.5 s", formatExposureTime(2.5))
    }
}
