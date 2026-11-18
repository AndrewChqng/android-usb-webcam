package com.andrewchqng.usbwebcam.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionItemTest {

    @Test
    fun fromKey_validatesBoundsAndDisallowsSub240p() {
        val valid = ResolutionItem.fromKey("1920x1080")
        assertNotNull(valid)
        assertEquals(1920, valid?.width)
        assertEquals(1080, valid?.height)
        assertEquals("16:9", valid?.aspectRatio)

        val vgaMin = ResolutionItem.fromKey("320x240")
        assertNotNull(vgaMin)
        assertEquals("4:3", vgaMin?.aspectRatio)

        // Disallowed sub-240p resolutions (short side < 240)
        assertNull(ResolutionItem.fromKey("176x144"))
        assertNull(ResolutionItem.fromKey("256x144"))
        assertNull(ResolutionItem.fromKey("144x256"))

        // Malformed or above 8K ceiling
        assertNull(ResolutionItem.fromKey("bad"))
        assertNull(ResolutionItem.fromKey("8000x4000"))
    }

    @Test
    fun getPresetResolutions_returnsOrderedValidSetsUpToTier() {
        val (tier240, def240) = ResolutionItem.getPresetResolutions("max_240p")
        assertEquals(listOf(ResolutionItem(320, 240)), tier240)
        assertEquals(ResolutionItem(320, 240), def240)

        val (tier1080, def1080) = ResolutionItem.getPresetResolutions("max_1080p")
        assertEquals(ResolutionItem(1920, 1080), def1080)
        assertEquals(5, tier1080.size)
        assertEquals(ResolutionItem(1920, 1080), tier1080.first())
        assertEquals(ResolutionItem(320, 240), tier1080.last())
        assertTrue(tier1080.all { minOf(it.width, it.height) >= 240 })

        val (tier4k, def4k) = ResolutionItem.getPresetResolutions("max_4k")
        assertEquals(ResolutionItem(3840, 2160), def4k)
        assertEquals(7, tier4k.size)
        assertTrue(tier4k.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun aspectRatio_computesStandardAndReducedRatios() {
        assertEquals("16:9", ResolutionItem(1280, 720).aspectRatio)
        assertEquals("9:16", ResolutionItem(1080, 1920).aspectRatio)
        assertEquals("4:3", ResolutionItem(640, 480).aspectRatio)
        assertEquals("1:1", ResolutionItem(1080, 1080).aspectRatio)
    }
}
