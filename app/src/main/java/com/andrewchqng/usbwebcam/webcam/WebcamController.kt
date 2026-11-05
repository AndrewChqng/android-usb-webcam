package com.andrewchqng.usbwebcam.webcam

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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
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
import kotlin.math.roundToInt

data class CameraLensInfo(
    val cameraId: String,
    val label: String,
    val isFrontFacing: Boolean,
    val sensorOrientation: Int,
    val focalLengthMm: Float,
    val maxZoom: Float = 4.0f
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
    val boundMaxPacket: Int = 1024,
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
    val exposureEv: Float = 0.0f,
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

    // Telemetry & pacing state
    private var frameCountWindow = 0
    private var encodeTimeSumNs = 0L
    private var frameBytesSum = 0L
    private var lastFpsTimestampNs = 0L
    private var lastSubmittedFrameNs = 0L

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
                val maxDigitalZoom = (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4.0f)
                    .coerceIn(1.0f, 8.0f)

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
                        focalLengthMm = focal,
                        maxZoom = maxDigitalZoom
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

    fun startUvcSession(videoDevicePath: String, maxPayloadSize: Int = 1024): Boolean {
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
                boundMaxPacket = maxPayloadSize,
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
                        measuredFps = 0f,
                        avgEncodeTimeMs = 0f,
                        avgFrameSizeKb = 0f
                    )
                }
            }
        }

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
                measuredFps = 0f,
                avgEncodeTimeMs = 0f,
                avgFrameSizeKb = 0f
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
                    measuredFps = 0f,
                    avgEncodeTimeMs = 0f,
                    avgFrameSizeKb = 0f
                )
            }
            if (!_state.value.isPreviewEnabled || previewSurface == null) {
                stopCameraCapture()
            }
        }
    }

    fun selectCamera(cameraId: String) {
        if (_state.value.selectedCameraId == cameraId) return
        _state.update { it.copy(selectedCameraId = cameraId, zoomRatio = 1.0f) }
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

    fun setMaxInFlightBuffers(count: Int) {
        _state.update { it.copy(maxInFlightBuffers = count.coerceIn(1, 4)) }
    }

    fun setTargetFpsCap(fpsCap: Int) {
        if (_state.value.targetFpsCap == fpsCap) return
        _state.update { it.copy(targetFpsCap = fpsCap) }
        updateRepeatingRequestOnFly()
    }

    fun setAutoFocusEnabled(enabled: Boolean) {
        if (_state.value.autoFocusEnabled == enabled) return
        _state.update { it.copy(autoFocusEnabled = enabled) }
        updateRepeatingRequestOnFly()
    }

    fun setLockConstantFps(locked: Boolean) {
        if (_state.value.lockConstantFps == locked) return
        _state.update { it.copy(lockConstantFps = locked) }
        updateRepeatingRequestOnFly()
    }

    fun setVideoStabilization(enabled: Boolean) {
        if (_state.value.videoStabilization == enabled) return
        _state.update { it.copy(videoStabilization = enabled) }
        updateRepeatingRequestOnFly()
    }

    fun setZoomRatio(zoom: Float) {
        val clamped = zoom.coerceIn(1.0f, 4.0f)
        _state.update { it.copy(zoomRatio = clamped) }
        updateRepeatingRequestOnFly()
    }

    fun setExposureEv(ev: Float) {
        val clamped = ev.coerceIn(-2.0f, 2.0f)
        _state.update { it.copy(exposureEv = clamped) }
        updateRepeatingRequestOnFly()
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

            // Exact match first (enables zero-scale NEON/memcpy fast path)
            sizes.firstOrNull { it.width == targetW && it.height == targetH }?.let { return it }

            // Otherwise closest matching resolution >= target size
            sizes.filter { it.width >= targetW && it.height >= targetH }
                .minByOrNull { (it.width - targetW) + (it.height - targetH) }
                ?: sizes.minByOrNull { abs(it.width - targetW) + abs(it.height - targetH) }
                ?: Size(targetW, targetH)
        }.getOrDefault(Size(targetW, targetH))
    }

    private fun chooseFpsRange(cameraId: String, desiredFps: Int, lockConstant: Boolean): Range<Int>? {
        return runCatching {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
            if (lockConstant) {
                ranges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }
                    ?: ranges.filter { it.upper == desiredFps }.maxByOrNull { it.lower }
                    ?: ranges.filter { it.upper >= desiredFps }.minByOrNull { it.upper - it.lower }
                    ?: ranges.maxByOrNull { it.upper }
            } else {
                ranges.filter { it.upper == desiredFps }.minByOrNull { it.lower }
                    ?: ranges.firstOrNull { it.upper >= desiredFps }
                    ?: ranges.maxByOrNull { it.upper }
            }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startCameraCapture(targetW: Int, targetH: Int, targetFps: Int) {
        stopCameraCapture()

        // Use URGENT_DISPLAY priority (-8) so Android keeps the frame encoder on big cores even with screen off
        val thread = HandlerThread("UvcCamera2Thread", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler

        val camId = _state.value.selectedCameraId
        val captureSize = chooseOptimalSize(camId, targetW, targetH)
        Timber.i("Starting Camera2 ($camId) capture at ${captureSize.width}x${captureSize.height} for UVC ${targetW}x${targetH}@${targetFps}fps")

        // 4 ImageReader buffers allow smooth triple-buffered camera ISP output while 1 frame is being compressed
        val reader = ImageReader.newInstance(
            captureSize.width,
            captureSize.height,
            ImageFormat.YUV_420_888,
            4
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

    private fun applyCameraParameters(
        builder: CaptureRequest.Builder,
        camId: String,
        s: WebcamStreamState
    ) {
        val effectiveFps = if (s.targetFpsCap > 0) minOf(s.negotiatedFps, s.targetFpsCap) else s.negotiatedFps

        // Low-latency ISP pipeline flags
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)

        // FPS & Auto-Exposure range
        chooseFpsRange(camId, effectiveFps, s.lockConstantFps)?.let { fpsRange ->
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        }

        // Autofocus mode
        if (s.autoFocusEnabled) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) // Infinity / hyperfocal
        }

        // Video stabilization
        if (s.videoStabilization) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        } else {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }

        // Exposure compensation (EV) & Zoom
        runCatching {
            val chars = cameraManager.getCameraCharacteristics(camId)
            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
            if (evRange != null && evStep > 0f) {
                val index = (s.exposureEv / evStep).roundToInt().coerceIn(evRange.lower, evRange.upper)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, s.zoomRatio)
            } else {
                val activeRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeRect != null && s.zoomRatio > 1.0f) {
                    val cropW = (activeRect.width() / s.zoomRatio).roundToInt()
                    val cropH = (activeRect.height() / s.zoomRatio).roundToInt()
                    val cropX = (activeRect.width() - cropW) / 2
                    val cropY = (activeRect.height() - cropH) / 2
                    builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
                }
            }
        }
    }

    @Synchronized
    private fun updateRepeatingRequestOnFly() {
        val cam = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val handler = cameraHandler ?: return
        val s = _state.value

        try {
            val requestBuilder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                val currentPreview = previewSurface
                if (s.isPreviewEnabled && currentPreview != null && currentPreview.isValid) {
                    addTarget(currentPreview)
                }
                applyCameraParameters(this, s.selectedCameraId, s)
            }
            session.setRepeatingRequest(requestBuilder.build(), null, handler)
        } catch (e: Exception) {
            Timber.w(e, "Failed to update Camera2 repeating request on the fly")
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
        val s = _state.value
        if (s.isPreviewEnabled && currentPreview != null && currentPreview.isValid) {
            surfaces.add(currentPreview)
        }

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(readerSurface)
                if (surfaces.size > 1 && currentPreview != null) {
                    addTarget(currentPreview)
                }
                applyCameraParameters(this, camId, s.copy(negotiatedFps = targetFps))
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

        val startNs = System.nanoTime()

        // 1. Optional user FPS cap pacing
        if (currentState.targetFpsCap > 0 && lastSubmittedFrameNs != 0L) {
            val minIntervalNs = (1_000_000_000L / currentState.targetFpsCap) * 90 / 100
            if (startNs - lastSubmittedFrameNs < minIntervalNs) {
                return
            }
        }

        // 2. Check if V4L2 ring buffer can accept a frame BEFORE doing any YUV/JPEG encoding!
        val maxInFlight = currentState.maxInFlightBuffers
        if (!UvcNative.nativeCanAcceptFrame(maxInFlight)) {
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

        val lens = currentState.availableCameras.firstOrNull { it.cameraId == currentState.selectedCameraId }
        val totalRotation = currentState.rotationDegrees
        val mirror = currentState.mirrorHorizontal || (lens?.isFrontFacing == true && !currentState.mirrorHorizontal)

        val pushed: Boolean
        val payloadBytes: Int
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

            payloadBytes = jpegOutputStream.size
            pushed = UvcNative.nativePushMjpegFrame(directJpegBuffer, payloadBytes, maxInFlight)
        } else {
            payloadBytes = dstW * dstH * 2
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
                mirror,
                maxInFlight
            )
        }

        if (pushed) {
            val endNs = System.nanoTime()
            lastSubmittedFrameNs = endNs
            frameCountWindow++
            encodeTimeSumNs += (endNs - startNs)
            frameBytesSum += payloadBytes

            if (lastFpsTimestampNs == 0L) {
                lastFpsTimestampNs = endNs
            } else {
                val elapsedNs = endNs - lastFpsTimestampNs
                if (elapsedNs >= 1_000_000_000L) {
                    val fps = (frameCountWindow * 1_000_000_000f) / elapsedNs
                    val avgMs = (encodeTimeSumNs.toFloat() / frameCountWindow) / 1_000_000f
                    val avgKb = (frameBytesSum.toFloat() / frameCountWindow) / 1024f
                    frameCountWindow = 0
                    encodeTimeSumNs = 0L
                    frameBytesSum = 0L
                    lastFpsTimestampNs = endNs
                    _state.update {
                        it.copy(
                            measuredFps = fps,
                            avgEncodeTimeMs = avgMs,
                            avgFrameSizeKb = avgKb
                        )
                    }
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
        encodeTimeSumNs = 0L
        frameBytesSum = 0L
        lastFpsTimestampNs = 0L
        lastSubmittedFrameNs = 0L
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
