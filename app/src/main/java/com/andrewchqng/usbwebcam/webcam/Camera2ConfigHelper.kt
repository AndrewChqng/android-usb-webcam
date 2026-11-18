package com.andrewchqng.usbwebcam.webcam

import kotlin.math.abs
import kotlin.math.roundToInt

data class Dimensions(val width: Int, val height: Int)

data class FpsRange(val lower: Int, val upper: Int)

data class CropRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class UsbBusMetrics(
    val mbps: Float,
    val totalBytesPerSec: Float,
    val isocLimitBytesPerSec: Float,
    val utilizationPct: Float,
    val isSaturated: Boolean
)

/**
 * Pure Camera2 selection, PTZ crop geometry, frame pacing, and USB bus telemetry calculations.
 * Kept framework-free for deterministic JVM unit testing.
 */
object Camera2ConfigHelper {

    fun selectOptimalCaptureDimensions(
        availableSizes: List<Dimensions>,
        targetW: Int,
        targetH: Int
    ): Dimensions {
        if (availableSizes.isEmpty()) return Dimensions(targetW, targetH)

        val reqW = maxOf(targetW, targetH)
        val reqH = minOf(targetW, targetH)

        // Direct 1:1 sensor match first (either direct orientation or rotated) with no scaler downscaling
        availableSizes.firstOrNull {
            (it.width == targetW && it.height == targetH) || (it.width == reqW && it.height == reqH)
        }?.let { return it }

        // Match 4K UHD or 1440p QHD if requested
        if (reqW >= 3840 && reqH >= 2160) {
            availableSizes.firstOrNull { it.width == 3840 && it.height == 2160 }?.let { return it }
        } else if (reqW >= 2560 && reqH >= 1440) {
            availableSizes.firstOrNull { it.width == 2560 && it.height == 1440 }?.let { return it }
                ?: availableSizes.firstOrNull { it.width == 3840 && it.height == 2160 }?.let { return it }
        }

        // Smallest sensor resolution >= requested size to maximize efficiency and avoid unnecessary downscaling
        val candidates = availableSizes.filter { it.width >= reqW && it.height >= reqH }
        if (candidates.isNotEmpty()) {
            return candidates.minByOrNull { it.width.toLong() * it.height } ?: candidates.first()
        }

        // Otherwise closest matching resolution
        return availableSizes.minByOrNull { abs(it.width - reqW) + abs(it.height - reqH) }
            ?: Dimensions(targetW, targetH)
    }

    fun selectFpsRange(
        availableRanges: List<FpsRange>,
        desiredFps: Int,
        lockConstant: Boolean
    ): FpsRange? {
        if (availableRanges.isEmpty()) return null
        return if (lockConstant) {
            availableRanges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }
                ?: availableRanges.filter { it.upper == desiredFps }.maxByOrNull { it.lower }
                ?: availableRanges.filter { it.upper >= desiredFps }.minByOrNull { it.upper - it.lower }
                ?: availableRanges.maxWithOrNull(compareBy<FpsRange> { it.upper }.thenBy { it.lower })
        } else {
            availableRanges.filter { it.upper == desiredFps }.minByOrNull { it.lower }
                ?: availableRanges.firstOrNull { it.upper >= desiredFps }
                ?: availableRanges.maxByOrNull { it.upper }
        }
    }

    fun computeCropRegion(
        activeRegion: CropRegion,
        captureSize: Dimensions,
        zoomRatio: Float,
        maxDigitalZoom: Float,
        panX: Float,
        panY: Float,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean
    ): CropRegion {
        val activeW = activeRegion.width
        val activeH = activeRegion.height
        if (activeW <= 0 || activeH <= 0) return activeRegion

        val capW = maxOf(captureSize.width.coerceAtLeast(1), captureSize.height.coerceAtLeast(1))
        val capH = minOf(captureSize.width.coerceAtLeast(1), captureSize.height.coerceAtLeast(1))
        val reqAspectW = if (activeW >= activeH) capW else capH
        val reqAspectH = if (activeW >= activeH) capH else capW

        var baseW = activeW
        var baseH = activeH
        if (activeW.toLong() * reqAspectH > activeH.toLong() * reqAspectW) {
            baseW = ((activeH.toLong() * reqAspectW) / reqAspectH).toInt()
        } else if (activeW.toLong() * reqAspectH < activeH.toLong() * reqAspectW) {
            baseH = ((activeW.toLong() * reqAspectH) / reqAspectW).toInt()
        }

        val clampedMaxZoom = maxDigitalZoom.coerceAtLeast(1.0f)
        val zoom = zoomRatio.coerceIn(1.0f, clampedMaxZoom)
        val minCropW = minOf(64, activeW)
        val minCropH = minOf(64, activeH)
        val cropW = ((baseW / zoom).roundToInt().coerceIn(minCropW, activeW)) and 1.inv()
        val cropH = ((baseH / zoom).roundToInt().coerceIn(minCropH, activeH)) and 1.inv()

        val userX = if (mirrorHorizontal) -panX else panX
        val userY = panY
        var sensorPanX = userX
        var sensorPanY = userY
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> {
                sensorPanX = userY
                sensorPanY = -userX
            }
            180 -> {
                sensorPanX = -userX
                sensorPanY = -userY
            }
            270 -> {
                sensorPanX = -userY
                sensorPanY = userX
            }
        }

        val maxPanX = (activeW - cropW).coerceAtLeast(0)
        val maxPanY = (activeH - cropH).coerceAtLeast(0)
        val normX = (sensorPanX.coerceIn(-1f, 1f) + 1f) * 0.5f
        val normY = (sensorPanY.coerceIn(-1f, 1f) + 1f) * 0.5f

        val rawLeft = activeRegion.left + (normX * maxPanX).roundToInt()
        val rawTop = activeRegion.top + (normY * maxPanY).roundToInt()
        val clampedLeft = rawLeft.coerceIn(activeRegion.left, activeRegion.right - cropW)
        val clampedTop = rawTop.coerceIn(activeRegion.top, activeRegion.bottom - cropH)
        val left = clampedLeft - ((clampedLeft - activeRegion.left) and 1)
        val top = clampedTop - ((clampedTop - activeRegion.top) and 1)
        return CropRegion(left, top, left + cropW, top + cropH)
    }

    fun computeEffectiveFps(negotiatedFps: Int, targetFpsCap: Int): Int =
        if (targetFpsCap > 0) minOf(negotiatedFps, targetFpsCap) else negotiatedFps

    fun shouldSkipFrameByTimestamp(
        frameTimestampNs: Long,
        lastSubmittedFrameNs: Long,
        effectiveFps: Int,
        activeCameraFpsUpper: Int
    ): Boolean {
        val needsSoftwarePacing = activeCameraFpsUpper <= 0 || effectiveFps < activeCameraFpsUpper
        if (!needsSoftwarePacing || effectiveFps <= 0 || lastSubmittedFrameNs == 0L || frameTimestampNs < lastSubmittedFrameNs) {
            return false
        }
        val minIntervalNs = (1_000_000_000L / effectiveFps) * 82 / 100
        return (frameTimestampNs - lastSubmittedFrameNs) < minIntervalNs
    }

    fun calculateUsbBusMetrics(
        measuredFps: Float,
        avgFrameSizeKb: Float,
        boundMaxPacket: Int,
        isHostStreaming: Boolean
    ): UsbBusMetrics {
        val isocLimit = (boundMaxPacket.coerceAtLeast(1024) * 8_000).toFloat()
        if (!isHostStreaming) {
            return UsbBusMetrics(
                mbps = 0f,
                totalBytesPerSec = 0f,
                isocLimitBytesPerSec = isocLimit,
                utilizationPct = 0f,
                isSaturated = false
            )
        }
        val mbps = (measuredFps * avgFrameSizeKb * 8f) / 1024f
        val totalBytesSec = measuredFps * avgFrameSizeKb * 1024f
        val usbPct = ((totalBytesSec / isocLimit) * 100f).coerceIn(0f, 100f)
        val isSaturated = usbPct.roundToInt() >= 100
        return UsbBusMetrics(
            mbps = mbps,
            totalBytesPerSec = totalBytesSec,
            isocLimitBytesPerSec = isocLimit,
            utilizationPct = usbPct,
            isSaturated = isSaturated
        )
    }
}
