package com.andrewchqng.usbwebcam.webcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2ConfigHelperTest {

    @Test
    fun computeCropRegion_aspectMatches4by3SensorTo16by9AndAllowsVerticalTiltAt1x() {
        val active = CropRegion(0, 0, 4096, 3072)
        val capture1080p = Dimensions(1920, 1080)

        val centered = Camera2ConfigHelper.computeCropRegion(
            activeRegion = active,
            captureSize = capture1080p,
            zoomRatio = 1.0f,
            maxDigitalZoom = 8.0f,
            panX = 0f,
            panY = 0f,
            rotationDegrees = 0,
            mirrorHorizontal = false
        )
        assertEquals(4096, centered.width)
        assertEquals(2304, centered.height)
        assertEquals(0, centered.left)
        assertEquals(384, centered.top)

        val tiltedDown = Camera2ConfigHelper.computeCropRegion(
            activeRegion = active,
            captureSize = capture1080p,
            zoomRatio = 1.0f,
            maxDigitalZoom = 8.0f,
            panX = 0f,
            panY = 1.0f,
            rotationDegrees = 0,
            mirrorHorizontal = false
        )
        assertEquals(4096, tiltedDown.width)
        assertEquals(2304, tiltedDown.height)
        assertEquals(768, tiltedDown.top)
        assertEquals(3072, tiltedDown.bottom)
    }

    @Test
    fun computeCropRegion_handlesZoomPanRotationsMirrorAndNonZeroOrigin() {
        val active = CropRegion(8, 8, 4624, 3472) // width=4616, height=3464
        val capture720p = Dimensions(1280, 720)

        val zoomed2xRight = Camera2ConfigHelper.computeCropRegion(
            activeRegion = active,
            captureSize = capture720p,
            zoomRatio = 2.0f,
            maxDigitalZoom = 8.0f,
            panX = 1.0f,
            panY = -1.0f,
            rotationDegrees = 0,
            mirrorHorizontal = false
        )
        assertTrue(zoomed2xRight.width % 2 == 0)
        assertTrue(zoomed2xRight.height % 2 == 0)
        assertEquals(active.right, zoomed2xRight.right)
        assertEquals(active.top, zoomed2xRight.top)

        // Mirrored X flips left/right in sensor space
        val mirroredLeft = Camera2ConfigHelper.computeCropRegion(
            activeRegion = active,
            captureSize = capture720p,
            zoomRatio = 2.0f,
            maxDigitalZoom = 8.0f,
            panX = 1.0f,
            panY = -1.0f,
            rotationDegrees = 0,
            mirrorHorizontal = true
        )
        assertEquals(active.left, mirroredLeft.left)
        assertEquals(active.top, mirroredLeft.top)

        // Rotation 90 maps userRight (+X, 0) -> sensor (0, -userX = top edge)
        val rot90Right = Camera2ConfigHelper.computeCropRegion(
            activeRegion = active,
            captureSize = capture720p,
            zoomRatio = 2.0f,
            maxDigitalZoom = 8.0f,
            panX = 1.0f,
            panY = 0.0f,
            rotationDegrees = 90,
            mirrorHorizontal = false
        )
        assertEquals(active.top, rot90Right.top)

        // Rotation 180 maps user (+1, +1) -> sensor (-1, -1 = top-left)
        val rot180 = Camera2ConfigHelper.computeCropRegion(
            activeRegion = active,
            captureSize = capture720p,
            zoomRatio = 2.0f,
            maxDigitalZoom = 8.0f,
            panX = 1.0f,
            panY = 1.0f,
            rotationDegrees = 180,
            mirrorHorizontal = false
        )
        assertEquals(active.left, rot180.left)
        assertEquals(active.top, rot180.top)

        // Rotation 270 maps userRight (+1, 0) -> sensor (0, +1 = bottom edge)
        val rot270Right = Camera2ConfigHelper.computeCropRegion(
            activeRegion = active,
            captureSize = capture720p,
            zoomRatio = 2.0f,
            maxDigitalZoom = 8.0f,
            panX = 1.0f,
            panY = 0.0f,
            rotationDegrees = 270,
            mirrorHorizontal = false
        )
        assertEquals(active.bottom, rot270Right.bottom)
    }

    @Test
    fun selectOptimalCaptureDimensions_prefersDirectAndPortraitMatches() {
        val available = listOf(
            Dimensions(3840, 2160),
            Dimensions(2560, 1440),
            Dimensions(1920, 1080),
            Dimensions(1280, 720),
            Dimensions(640, 480),
            Dimensions(640, 360),
            Dimensions(320, 240)
        )

        assertEquals(Dimensions(1280, 720), Camera2ConfigHelper.selectOptimalCaptureDimensions(available, 1280, 720))
        // Portrait 720x1280 matches landscape 1280x720 1:1 sensor capture before native rotation
        assertEquals(Dimensions(1280, 720), Camera2ConfigHelper.selectOptimalCaptureDimensions(available, 720, 1280))
        // Non-native intermediate picks smallest covering resolution
        assertEquals(Dimensions(1280, 720), Camera2ConfigHelper.selectOptimalCaptureDimensions(available, 960, 540))
        // Larger than max falls back to closest
        assertEquals(Dimensions(3840, 2160), Camera2ConfigHelper.selectOptimalCaptureDimensions(available, 4096, 2160))
    }

    @Test
    fun selectFpsRange_respectsConstantLockAndFallback() {
        val ranges = listOf(
            FpsRange(10, 15),
            FpsRange(15, 15),
            FpsRange(10, 24),
            FpsRange(24, 24),
            FpsRange(10, 30),
            FpsRange(30, 30)
        )

        assertEquals(FpsRange(30, 30), Camera2ConfigHelper.selectFpsRange(ranges, 30, lockConstant = true))
        assertEquals(FpsRange(10, 30), Camera2ConfigHelper.selectFpsRange(ranges, 30, lockConstant = false))
        assertEquals(FpsRange(24, 24), Camera2ConfigHelper.selectFpsRange(ranges, 24, lockConstant = true))
        assertEquals(FpsRange(30, 30), Camera2ConfigHelper.selectFpsRange(ranges, 60, lockConstant = true))
        assertNotNull(Camera2ConfigHelper.selectFpsRange(ranges, 5, lockConstant = true))
        assertNull(Camera2ConfigHelper.selectFpsRange(emptyList(), 30, lockConstant = true))
    }

    @Test
    fun pacingSkipsOnlyWhenSoftwareDecimationNeeded() {
        val eff30 = Camera2ConfigHelper.computeEffectiveFps(negotiatedFps = 30, targetFpsCap = 0)
        assertEquals(30, eff30)

        // When hardware sensor is already capped at 30fps (activeCameraFpsUpper == 30), never drop on jitter
        assertFalse(
            Camera2ConfigHelper.shouldSkipFrameByTimestamp(
                frameTimestampNs = 1_020_000_000L,
                lastSubmittedFrameNs = 1_000_000_000L,
                effectiveFps = 30,
                activeCameraFpsUpper = 30
            )
        )

        // When hardware delivers 30fps but user capped to 15fps -> min interval = (66.66ms * 82 / 100) = ~54.6ms
        val eff15 = Camera2ConfigHelper.computeEffectiveFps(negotiatedFps = 30, targetFpsCap = 15)
        assertEquals(15, eff15)
        assertTrue(
            Camera2ConfigHelper.shouldSkipFrameByTimestamp(
                frameTimestampNs = 1_033_333_333L,
                lastSubmittedFrameNs = 1_000_000_000L,
                effectiveFps = eff15,
                activeCameraFpsUpper = 30
            )
        )
        assertFalse(
            Camera2ConfigHelper.shouldSkipFrameByTimestamp(
                frameTimestampNs = 1_066_666_666L,
                lastSubmittedFrameNs = 1_000_000_000L,
                effectiveFps = eff15,
                activeCameraFpsUpper = 30
            )
        )
        // Timestamp monotonic reset protection
        assertFalse(
            Camera2ConfigHelper.shouldSkipFrameByTimestamp(
                frameTimestampNs = 500_000_000L,
                lastSubmittedFrameNs = 1_000_000_000L,
                effectiveFps = eff15,
                activeCameraFpsUpper = 30
            )
        )
    }

    @Test
    fun calculateUsbBusMetrics_detects100PercentSaturationAndRespectsPacketLimit() {
        val idle = Camera2ConfigHelper.calculateUsbBusMetrics(30f, 100f, 3072, isHostStreaming = false)
        assertFalse(idle.isSaturated)
        assertEquals(0f, idle.utilizationPct, 0.001f)

        // 3072B * 8000 = 24,576,000 B/s. At 30fps * 800KB = 24,576,000 B/s -> 100%
        val saturated = Camera2ConfigHelper.calculateUsbBusMetrics(30f, 800f, 3072, isHostStreaming = true)
        assertTrue(saturated.isSaturated)
        assertEquals(100f, saturated.utilizationPct, 0.01f)

        // 2048B * 8000 = 16,384,000 B/s. At 30fps * 200KB = 6,144,000 B/s -> 37.5%
        val partial2048 = Camera2ConfigHelper.calculateUsbBusMetrics(30f, 200f, 2048, isHostStreaming = true)
        assertFalse(partial2048.isSaturated)
        assertEquals(37.5f, partial2048.utilizationPct, 0.05f)
    }
}
