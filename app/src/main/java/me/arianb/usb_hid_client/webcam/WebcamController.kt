package me.arianb.usb_hid_client.webcam

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.abs

data class CameraLensInfo(
    val cameraId: String,
    val label: String,
    val isFrontFacing: Boolean,
    val sensorOrientation: Int,
    val focalLengthMm: Float
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
    val selectedCameraId: String = "0",
    val availableCameras: List<CameraLensInfo> = emptyList(),
    val rotationDegrees: Int = 0,
    val mirrorHorizontal: Boolean = false,
    val jpegQuality: Int = 85,
    val isPreviewEnabled: Boolean = false,
    val lastError: String? = null
)

/**
 * Zero-allocation OutputStream backed by a direct ByteBuffer so YuvImage.compressToJpeg
 * writes straight into native-accessible memory with zero GC churn.
 */
private class DirectByteBufferOutputStream(private val buffer: ByteBuffer) : OutputStream() {
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

class WebcamController private constructor(private val application: Application) : UvcEventCallback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cameraManager = application.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _state = MutableStateFlow(WebcamStreamState())
    val state: StateFlow<WebcamStreamState> = _state.asStateFlow()

    private var uvcLoopJob: Job? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    // Reusable pre-allocated buffers for zero-GC frame encoding
    private var nv21Buffer: ByteArray = ByteArray(0)
    private val directJpegBuffer: ByteBuffer = ByteBuffer.allocateDirect(1920 * 1080 * 2)
    private val jpegOutputStream = DirectByteBufferOutputStream(directJpegBuffer)

    // FPS tracking
    private var frameCountWindow = 0
    private var lastFpsTimestampNs = 0L

    init {
        refreshCameraList()
    }

    fun refreshCameraList() {
        val lenses = mutableListOf<CameraLensInfo>()
        runCatching {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
                    continue
                }
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val isFront = (facing == CameraCharacteristics.LENS_FACING_FRONT)
                val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focal = focalLengths?.firstOrNull() ?: 0f

                val facingLabel = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "Ext"
                    else -> "Cam"
                }
                val focalStr = if (focal > 0f) " (${String.format("%.1f", focal)}mm)" else ""
                lenses.add(
                    CameraLensInfo(
                        cameraId = id,
                        label = "$facingLabel #$id$focalStr",
                        isFrontFacing = isFront,
                        sensorOrientation = orientation,
                        focalLengthMm = focal
                    )
                )
            }
        }.onFailure {
            Timber.e(it, "Failed to enumerate Camera2 devices")
        }

        _state.update { current ->
            val selected = if (lenses.any { it.cameraId == current.selectedCameraId }) {
                current.selectedCameraId
            } else {
                lenses.firstOrNull()?.cameraId ?: "0"
            }
            current.copy(availableCameras = lenses, selectedCameraId = selected)
        }
    }

    fun startUvcSession(videoDevicePath: String, maxPayloadSize: Int = 3072): Boolean {
        stopUvcSession()

        val fdOrErr = UvcNative.nativeOpen(videoDevicePath, maxPayloadSize)
        if (fdOrErr < 0) {
            val errMsg = "Failed to open $videoDevicePath (errno=${-fdOrErr})"
            Timber.e(errMsg)
            _state.update {
                it.copy(
                    isGadgetActive = false,
                    isUvcLoopRunning = false,
                    lastError = errMsg
                )
            }
            return false
        }

        _state.update {
            it.copy(
                isGadgetActive = true,
                videoNodePath = videoDevicePath,
                isUvcLoopRunning = true,
                lastError = null
            )
        }

        uvcLoopJob = scope.launch {
            try {
                UvcNative.nativeRunEventLoop(this@WebcamController)
            } finally {
                stopCameraCapture()
                _state.update {
                    it.copy(
                        isUvcLoopRunning = false,
                        isHostStreaming = false,
                        isHostConnected = false,
                        negotiatedFormat = "Idle",
                        measuredFps = 0f
                    )
                }
            }
        }

        // If preview is enabled even before host starts streaming, open camera for preview
        if (_state.value.isPreviewEnabled && previewSurface != null) {
            restartCameraIfNeeded()
        }

        return true
    }

    fun stopUvcSession() {
        stopCameraCapture()
        UvcNative.nativeClose()
        uvcLoopJob?.cancel()
        uvcLoopJob = null

        _state.update {
            it.copy(
                isUvcLoopRunning = false,
                isHostConnected = false,
                isHostStreaming = false,
                negotiatedFormat = "Idle",
                measuredFps = 0f
            )
        }
    }

    override fun onHostConnectionChanged(isConnected: Boolean) {
        Timber.i("UVC host connection changed: $isConnected")
        _state.update { it.copy(isHostConnected = isConnected) }
    }

    override fun onStreamStateChanged(isStreaming: Boolean, isMjpeg: Boolean, width: Int, height: Int, fps: Int) {
        Timber.i("UVC stream state changed: streaming=$isStreaming isMjpeg=$isMjpeg ${width}x${height}@${fps}fps")
        if (isStreaming) {
            _state.update {
                it.copy(
                    isHostConnected = true,
                    isHostStreaming = true,
                    negotiatedFormat = if (isMjpeg) "MJPEG" else "YUYV",
                    negotiatedWidth = width,
                    negotiatedHeight = height,
                    negotiatedFps = fps,
                    lastError = null
                )
            }
            startCameraCapture(width, height, fps)
        } else {
            _state.update {
                it.copy(
                    isHostStreaming = false,
                    negotiatedFormat = "Idle",
                    measuredFps = 0f
                )
            }
            if (!_state.value.isPreviewEnabled || previewSurface == null) {
                stopCameraCapture()
            }
        }
    }

    fun selectCamera(cameraId: String) {
        if (_state.value.selectedCameraId == cameraId) return
        _state.update { it.copy(selectedCameraId = cameraId) }
        restartCameraIfNeeded()
    }

    fun setRotation(degrees: Int) {
        val norm = ((degrees % 360) + 360) % 360
        _state.update { it.copy(rotationDegrees = norm) }
    }

    fun setMirrorHorizontal(mirror: Boolean) {
        _state.update { it.copy(mirrorHorizontal = mirror) }
    }

    fun setJpegQuality(quality: Int) {
        _state.update { it.copy(jpegQuality = quality.coerceIn(50, 98)) }
    }

    fun setPreviewEnabled(enabled: Boolean) {
        if (_state.value.isPreviewEnabled == enabled) return
        _state.update { it.copy(isPreviewEnabled = enabled) }
        restartCameraIfNeeded()
    }

    fun attachPreviewSurface(surface: Surface?) {
        if (previewSurface === surface) return
        previewSurface = surface
        if (_state.value.isPreviewEnabled) {
            restartCameraIfNeeded()
        }
    }

    private fun restartCameraIfNeeded() {
        val s = _state.value
        if (s.isHostStreaming || (s.isPreviewEnabled && previewSurface != null)) {
            startCameraCapture(s.negotiatedWidth, s.negotiatedHeight, s.negotiatedFps)
        } else {
            stopCameraCapture()
        }
    }

    private fun chooseOptimalSize(cameraId: String, targetW: Int, targetH: Int): Size {
        return runCatching {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(targetW, targetH)

            // Exact match first
            sizes.firstOrNull { it.width == targetW && it.height == targetH }?.let { return it }

            // Otherwise closest 16:9 or matching aspect ratio >= target size
            sizes.minByOrNull { size ->
                abs(size.width - targetW) + abs(size.height - targetH)
            } ?: Size(targetW, targetH)
        }.getOrDefault(Size(targetW, targetH))
    }

    private fun chooseFpsRange(cameraId: String, targetFps: Int): Range<Int>? {
        return runCatching {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
            ranges.firstOrNull { it.lower == targetFps && it.upper == targetFps }
                ?: ranges.firstOrNull { it.upper == targetFps }
                ?: ranges.maxByOrNull { it.upper }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startCameraCapture(targetW: Int, targetH: Int, targetFps: Int) {
        stopCameraCapture()

        val thread = HandlerThread("UvcCamera2Thread").apply { start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler

        val camId = _state.value.selectedCameraId
        val captureSize = chooseOptimalSize(camId, targetW, targetH)
        Timber.i("Starting Camera2 ($camId) capture at ${captureSize.width}x${captureSize.height} for UVC ${targetW}x${targetH}@${targetFps}fps")

        val reader = ImageReader.newInstance(
            captureSize.width,
            captureSize.height,
            ImageFormat.YUV_420_888,
            3
        )
        imageReader = reader

        reader.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                processCameraFrame(image)
            } catch (t: Throwable) {
                Timber.w(t, "Error processing camera frame")
            } finally {
                image.close()
            }
        }, handler)

        try {
            cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, reader.surface, camId, targetFps, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Timber.w("Camera2 disconnected")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Timber.e("Camera2 error: $error")
                    camera.close()
                    cameraDevice = null
                    _state.update { it.copy(lastError = "Camera2 error ($error)") }
                }
            }, handler)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open Camera2 ($camId)")
            _state.update { it.copy(lastError = "Camera open failed: ${e.message}") }
        }
    }

    private fun createCaptureSession(
        camera: CameraDevice,
        readerSurface: Surface,
        camId: String,
        targetFps: Int,
        handler: Handler
    ) {
        val surfaces = mutableListOf(readerSurface)
        val currentPreview = previewSurface
        if (_state.value.isPreviewEnabled && currentPreview != null && currentPreview.isValid) {
            surfaces.add(currentPreview)
        }

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(readerSurface)
                if (surfaces.size > 1 && currentPreview != null) {
                    addTarget(currentPreview)
                }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                chooseFpsRange(camId, targetFps)?.let { fpsRange ->
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                }
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, handler)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to start repeating Camera2 request")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Timber.e("CameraCaptureSession configuration failed")
                    _state.update { it.copy(lastError = "Camera session config failed") }
                }
            }, handler)
        } catch (e: Exception) {
            Timber.e(e, "Exception creating capture session")
        }
    }

    private fun processCameraFrame(image: android.media.Image) {
        val currentState = _state.value
        if (!currentState.isHostStreaming) {
            return
        }

        val planes = image.planes
        if (planes.size < 3) return

        val srcW = image.width
        val srcH = image.height
        val dstW = currentState.negotiatedWidth
        val dstH = currentState.negotiatedHeight
        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        // Calculate total rotation combining camera sensor orientation and user rotation offset
        val lens = currentState.availableCameras.firstOrNull { it.cameraId == currentState.selectedCameraId }
        val totalRotation = currentState.rotationDegrees
        val mirror = currentState.mirrorHorizontal || (lens?.isFrontFacing == true && !currentState.mirrorHorizontal)

        val pushed: Boolean
        if (currentState.negotiatedFormat == "MJPEG") {
            val neededNv21Size = dstW * dstH * 3 / 2
            if (nv21Buffer.size != neededNv21Size) {
                nv21Buffer = ByteArray(neededNv21Size)
            }

            UvcNative.nativeYuv420ToNv21(
                planes[0].buffer,
                planes[1].buffer,
                planes[2].buffer,
                srcW,
                srcH,
                yRowStride,
                uvRowStride,
                uvPixelStride,
                dstW,
                dstH,
                totalRotation,
                mirror,
                nv21Buffer
            )

            jpegOutputStream.reset()
            val yuvImage = YuvImage(nv21Buffer, ImageFormat.NV21, dstW, dstH, null)
            yuvImage.compressToJpeg(Rect(0, 0, dstW, dstH), currentState.jpegQuality, jpegOutputStream)

            pushed = UvcNative.nativePushMjpegFrame(directJpegBuffer, jpegOutputStream.size)
        } else {
            pushed = UvcNative.nativePushYuvToYuyvFrame(
                planes[0].buffer,
                planes[1].buffer,
                planes[2].buffer,
                srcW,
                srcH,
                yRowStride,
                uvRowStride,
                uvPixelStride,
                totalRotation,
                mirror
            )
        }

        if (pushed) {
            frameCountWindow++
            val now = System.nanoTime()
            if (lastFpsTimestampNs == 0L) {
                lastFpsTimestampNs = now
            } else {
                val elapsedNs = now - lastFpsTimestampNs
                if (elapsedNs >= 1_000_000_000L) {
                    val fps = (frameCountWindow * 1_000_000_000f) / elapsedNs
                    frameCountWindow = 0
                    lastFpsTimestampNs = now
                    _state.update { it.copy(measuredFps = fps) }
                }
            }
        }
    }

    @Synchronized
    fun stopCameraCapture() {
        runCatching { captureSession?.close() }
        captureSession = null

        runCatching { cameraDevice?.close() }
        cameraDevice = null

        runCatching { imageReader?.close() }
        imageReader = null

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        frameCountWindow = 0
        lastFpsTimestampNs = 0L
    }

    companion object {
        @Volatile
        private var INSTANCE: WebcamController? = null

        fun getInstance(application: Application): WebcamController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebcamController(application).also { INSTANCE = it }
            }
        }
    }
}
