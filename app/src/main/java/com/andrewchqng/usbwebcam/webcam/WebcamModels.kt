package com.andrewchqng.usbwebcam.webcam

import java.io.OutputStream
import java.nio.ByteBuffer

data class CameraLensInfo(
    val cameraId: String,
    val label: String,
    val isFrontFacing: Boolean,
    val sensorOrientation: Int,
    val focalLengthMm: Float,
    val maxZoom: Float = 4.0f,
    val hasFlash: Boolean = false,
    val supportsFreeformCrop: Boolean = false
)

data class WebcamStreamState(
    val isGadgetActive: Boolean = false,
    val videoNodePath: String? = null,
    val isUvcLoopRunning: Boolean = false,
    val isHostConnected: Boolean = false,
    val isHostStreaming: Boolean = false,
    val negotiatedFormat: String = "Idle",
    val negotiatedWidth: Int = 1280,
    val negotiatedHeight: Int = 720,
    val negotiatedFps: Int = 30,
    val measuredFps: Float = 0f,
    val avgEncodeTimeMs: Float = 0f,
    val avgFrameSizeKb: Float = 0f,
    val boundMaxPacket: Int = 3072,
    val selectedCameraId: String = "0",
    val availableCameras: List<CameraLensInfo> = emptyList(),
    val rotationDegrees: Int = 0,
    val mirrorHorizontal: Boolean = false,
    val jpegQuality: Int = 82,
    val maxInFlightBuffers: Int = 2,
    val targetFpsCap: Int = 0, // 0 = Auto (Host negotiated), or 60 / 30 / 15
    val autoFocusEnabled: Boolean = true,
    val lockConstantFps: Boolean = true,
    val videoStabilization: Boolean = false,
    val zoomRatio: Float = 1.0f,
    val panX: Float = 0.0f, // -1.0f (left) to 1.0f (right), 0.0f = centered
    val panY: Float = 0.0f, // -1.0f (top) to 1.0f (bottom), 0.0f = centered
    val maxZoom: Float = 4.0f,
    val activeCameraSupportsFreeformCrop: Boolean = false,
    val appliedCropRegionDesc: String = "",
    val useNativeCamera2Ptz: Boolean = false,
    val exposureEv: Float = 0.0f,
    val awbMode: Int = 1, // CaptureRequest.CONTROL_AWB_MODE_AUTO
    val awbLock: Boolean = false,
    val aeLock: Boolean = false,
    val antiBandingMode: Int = 3, // CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
    val torchEnabled: Boolean = false,
    val studioQualityProcessing: Boolean = false,
    val isPreviewEnabled: Boolean = false,
    val lastError: String? = null
)

/**
 * Zero-allocation OutputStream backed by a direct ByteBuffer so YuvImage.compressToJpeg
 * writes straight into native-accessible memory with zero GC churn.
 */
internal class DirectByteBufferOutputStream(private val buffer: ByteBuffer) : OutputStream() {
    fun reset() {
        buffer.clear()
    }

    val size: Int
        get() = buffer.position()

    override fun write(b: Int) {
        if (buffer.hasRemaining()) {
            buffer.put(b.toByte())
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val count = minOf(len, buffer.remaining())
        if (count > 0) {
            buffer.put(b, off, count)
        }
    }
}
