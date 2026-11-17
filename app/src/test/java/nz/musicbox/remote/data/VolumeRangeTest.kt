package nz.musicbox.remote.data

import kotlin.test.Test
import kotlin.test.assertEquals

class VolumeRangeTest {
    @Test
    fun sliderCoversSixtyToHundredOnly() {
        assertEquals(60, VolumeRange.toMpd(0f))
        assertEquals(80, VolumeRange.toMpd(0.5f))
        assertEquals(100, VolumeRange.toMpd(1f))
        assertEquals(60, VolumeRange.toMpd(-3f))
        assertEquals(100, VolumeRange.toMpd(7f))
    }

    @Test
    fun quietServerVolumeShowsAtBottomOfSlider() {
        assertEquals(0f, VolumeRange.toSlider(20))
        assertEquals(0.5f, VolumeRange.toSlider(80))
        assertEquals(1f, VolumeRange.toSlider(100))
    }

    @Test
    fun roundTrips() {
        for (v in 60..100) assertEquals(v, VolumeRange.toMpd(VolumeRange.toSlider(v)))
    }
}
