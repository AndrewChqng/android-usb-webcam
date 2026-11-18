package com.andrewchqng.usbwebcam.hid_utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcFrameConfigBuilderTest {

    @Test
    fun buildFormatFrames_usesZeroPaddedDirectoryNamesAndRespectsFpsCaps() {
        val widths = IntArray(11) { 1280 }.also {
            it[0] = 3840
            it[1] = 1920
        }
        val heights = IntArray(11) { 720 }.also {
            it[0] = 2160
            it[1] = 1080
        }

        val res60 = UvcFrameConfigBuilder.buildFormatFrames(widths, heights, configuredMaxFps = 60)
        assertEquals(11, res60.mjpegFrames.size)
        assertEquals(11, res60.yuyvFrames.size)

        // Zero-padded ordering guarantees lexicographical ConfigFS order past index 9
        assertEquals("f01_3840x2160", res60.mjpegFrames[0].dirName)
        assertEquals("f02_1920x1080", res60.mjpegFrames[1].dirName)
        assertEquals("f10_1280x720", res60.mjpegFrames[9].dirName)
        assertEquals("f11_1280x720", res60.mjpegFrames[10].dirName)

        // MJPEG 4K caps at 30 FPS even when configuredMaxFps = 60
        assertEquals(333333L, res60.mjpegFrames[0].defaultInterval)
        assertEquals(listOf(333333L, 416666L, 666666L, 1000000L, 2000000L), res60.mjpegFrames[0].intervals)

        // MJPEG 1080p allows full 60 FPS
        assertEquals(166666L, res60.mjpegFrames[1].defaultInterval)
        assertEquals(6, res60.mjpegFrames[1].intervals.size)

        // YUYV >720p (1080p) caps at 5 FPS, 720p caps at 15 FPS
        assertEquals(listOf(2000000L), res60.yuyvFrames[1].intervals)
        assertEquals(listOf(666666L, 1000000L, 2000000L), res60.yuyvFrames[2].intervals)
    }

    @Test
    fun buildFormatFrames_clampsIntervalsWhenConfiguredFpsIsLower() {
        val widths = intArrayOf(1920, 640)
        val heights = intArrayOf(1080, 480)

        val res24 = UvcFrameConfigBuilder.buildFormatFrames(widths, heights, configuredMaxFps = 24)
        assertEquals(416666L, res24.mjpegFrames[0].defaultInterval)
        assertEquals(listOf(416666L, 666666L, 1000000L, 2000000L), res24.mjpegFrames[0].intervals)
        assertEquals(listOf(416666L, 666666L, 1000000L, 2000000L), res24.yuyvFrames[1].intervals)

        val res5 = UvcFrameConfigBuilder.buildFormatFrames(widths, heights, configuredMaxFps = 5)
        for (frame in res5.mjpegFrames + res5.yuyvFrames) {
            assertEquals(2000000L, frame.defaultInterval)
            assertEquals(listOf(2000000L), frame.intervals)
            assertTrue(frame.minBitRate <= frame.maxBitRate)
        }
    }

    @Test
    fun generateConfigFsCommands_emitsValidDescriptorCommands() {
        val frames = UvcFrameConfigBuilder.buildFormatFrames(
            widths = intArrayOf(1920, 1280),
            heights = intArrayOf(1080, 720),
            configuredMaxFps = 30
        ).mjpegFrames

        val script = UvcFrameConfigBuilder.generateConfigFsCommands("streaming/mjpeg/m", frames, defaultFrameIndex = 2)
        assertTrue(script.contains("mkdir -p \"\${UVC}/streaming/mjpeg/m/f01_1920x1080\""))
        assertTrue(script.contains("echo 333333 > \"\${UVC}/streaming/mjpeg/m/f01_1920x1080/dwDefaultFrameInterval\""))
        assertTrue(script.contains("printf \"333333\\n416666\\n666666\\n1000000\\n2000000\\n\""))
        assertTrue(script.contains("echo 2 > \"\${UVC}/streaming/mjpeg/m/bDefaultFrameIndex\""))
    }
}
