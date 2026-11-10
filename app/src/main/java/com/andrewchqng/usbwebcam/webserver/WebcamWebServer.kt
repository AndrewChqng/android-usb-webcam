package com.andrewchqng.usbwebcam.webserver

import android.app.Application
import com.andrewchqng.usbwebcam.hid_utils.CharacterDeviceManager
import com.andrewchqng.usbwebcam.settings.GadgetUserPreferences
import com.andrewchqng.usbwebcam.settings.UserPreferencesRepository
import com.andrewchqng.usbwebcam.shell_utils.RootStateHolder
import com.andrewchqng.usbwebcam.webcam.WebcamController
import com.andrewchqng.usbwebcam.webcam.WebcamForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.Locale

data class WebServerState(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val urls: List<String> = emptyList(),
    val error: String? = null
)

class WebcamWebServer private constructor(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val webcamController = WebcamController.getInstance(application)
    private val rootStateHolder = RootStateHolder.getInstance()
    private val characterDeviceManager = CharacterDeviceManager.getInstance(application)

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    @Synchronized
    fun start(requestedPort: Int = 8080) {
        if (_state.value.isRunning && serverSocket?.isClosed == false) {
            return
        }

        stop()

        serverJob = scope.launch {
            var boundSocket: ServerSocket? = null
            var actualPort = requestedPort

            // Try requested port first, then fallback to next 10 ports if already occupied
            for (offset in 0..10) {
                val candidatePort = requestedPort + offset
                try {
                    val ss = ServerSocket(candidatePort)
                    boundSocket = ss
                    actualPort = candidatePort
                    break
                } catch (e: Exception) {
                    Timber.w("Port $candidatePort unavailable: ${e.message}")
                }
            }

            if (boundSocket == null) {
                Timber.e("Failed to bind any web server port starting from $requestedPort")
                _state.update {
                    it.copy(
                        isRunning = false,
                        error = "Could not bind port $requestedPort (already in use)"
                    )
                }
                return@launch
            }

            serverSocket = boundSocket
            val urls = buildAddressList(actualPort)
            _state.update {
                it.copy(
                    isRunning = true,
                    port = actualPort,
                    urls = urls,
                    error = null
                )
            }
            Timber.i("Webcam Web Server listening on port $actualPort (URLs: $urls)")

            try {
                while (boundSocket.isBound && !boundSocket.isClosed) {
                    val clientSocket = try {
                        boundSocket.accept()
                    } catch (e: SocketException) {
                        break // Socket was closed by stop()
                    }
                    scope.launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Web server accept loop exited")
            } finally {
                runCatching { boundSocket.close() }
                _state.update { it.copy(isRunning = false) }
            }
        }
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        _state.update { it.copy(isRunning = false) }
    }

    fun restart(port: Int = 8080) {
        stop()
        start(port)
    }

    private fun buildAddressList(port: Int): List<String> {
        val result = mutableListOf<String>()
        result.add("http://localhost:$port")
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && host.isNotEmpty()) {
                            result.add("http://$host:$port")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to list network interfaces for web server")
        }
        return result
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 4000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val outputStream = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase(Locale.US)
            val path = parts[1].substringBefore('?')

            // Read request headers
            val headers = mutableMapOf<String, String>()
            var line: String? = reader.readLine()
            var contentLength = 0
            while (!line.isNullOrEmpty()) {
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase(Locale.US)
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
                line = reader.readLine()
            }

            // Read request body if present
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(buf, readTotal, contentLength - readTotal)
                    if (r < 0) break
                    readTotal += r
                }
                String(buf, 0, readTotal)
            } else ""

            if (method == "OPTIONS") {
                sendResponse(outputStream, 204, "No Content", "text/plain", "")
                return
            }

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    sendResponse(outputStream, 200, "OK", "text/html; charset=UTF-8", INDEX_HTML)
                }
                method == "GET" && path == "/api/status" -> {
                    val json = buildStatusJson()
                    sendResponse(outputStream, 200, "OK", "application/json; charset=UTF-8", json.toString())
                }
                method == "POST" && path == "/api/action" -> {
                    handleAction(body)
                    sendResponse(outputStream, 200, "OK", "application/json; charset=UTF-8", """{"status":"ok"}""")
                }
                method == "POST" && path == "/api/settings" -> {
                    handleSettings(body)
                    sendResponse(outputStream, 200, "OK", "application/json; charset=UTF-8", """{"status":"ok"}""")
                }
                else -> {
                    sendResponse(outputStream, 404, "Not Found", "text/plain", "Not Found")
                }
            }
        } catch (e: Exception) {
            // Client disconnect or socket timeout is normal
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun sendResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        out.flush()
    }

    private fun buildStatusJson(): JSONObject {
        val s = webcamController.state.value
        val json = JSONObject()

        json.put("isUvcLoopRunning", s.isUvcLoopRunning)
        json.put("isGadgetActive", s.isGadgetActive)
        json.put("isHostConnected", s.isHostConnected)
        json.put("isHostStreaming", s.isHostStreaming)
        json.put("videoNodePath", s.videoNodePath ?: "")
        json.put("format", s.negotiatedFormat)
        json.put("width", s.negotiatedWidth)
        json.put("height", s.negotiatedHeight)
        json.put("fps", s.negotiatedFps)
        json.put("measuredFps", s.measuredFps.toDouble())
        json.put("avgEncodeTimeMs", s.avgEncodeTimeMs.toDouble())
        json.put("avgFrameSizeKb", s.avgFrameSizeKb.toDouble())
        json.put("boundMaxPacket", s.boundMaxPacket)

        json.put("selectedCameraId", s.selectedCameraId)
        val camArray = JSONArray()
        for (cam in s.availableCameras) {
            val camObj = JSONObject()
            camObj.put("id", cam.cameraId)
            camObj.put("label", cam.label)
            camObj.put("isFront", cam.isFrontFacing)
            camObj.put("maxZoom", cam.maxZoom.toDouble())
            camObj.put("hasFlash", cam.hasFlash)
            camArray.put(camObj)
        }
        json.put("availableCameras", camArray)

        json.put("rotation", s.rotationDegrees)
        json.put("mirror", s.mirrorHorizontal)
        json.put("jpegQuality", s.jpegQuality)
        json.put("maxInFlightBuffers", s.maxInFlightBuffers)
        json.put("targetFpsCap", s.targetFpsCap)

        json.put("zoomRatio", s.zoomRatio.toDouble())
        json.put("panX", s.panX.toDouble())
        json.put("panY", s.panY.toDouble())
        json.put("maxZoom", s.maxZoom.toDouble())

        json.put("autoFocus", s.autoFocusEnabled)
        json.put("lockConstantFps", s.lockConstantFps)
        json.put("videoStabilization", s.videoStabilization)
        json.put("exposureEv", s.exposureEv.toDouble())
        json.put("aeLock", s.aeLock)
        json.put("awbMode", s.awbMode)
        json.put("awbLock", s.awbLock)
        json.put("antiBandingMode", s.antiBandingMode)
        json.put("torch", s.torchEnabled)
        json.put("studioQuality", s.studioQualityProcessing)
        json.put("lastError", s.lastError ?: "")

        val urlsArray = JSONArray()
        for (u in _state.value.urls) {
            urlsArray.put(u)
        }
        json.put("urls", urlsArray)
        json.put("port", _state.value.port)

        return json
    }

    private fun handleAction(body: String) {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return
        when (json.optString("action")) {
            "start" -> {
                scope.launch { startWebcam() }
            }
            "stop" -> {
                scope.launch { stopWebcam() }
            }
            "recenter" -> {
                webcamController.resetPan()
            }
            "resetIsp" -> {
                webcamController.resetIspControls()
            }
        }
    }

    private fun handleSettings(body: String) {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return

        if (json.has("zoomRatio")) {
            webcamController.setZoomRatio(json.optDouble("zoomRatio").toFloat())
        }
        if (json.has("panX")) {
            webcamController.setPanX(json.optDouble("panX").toFloat())
        }
        if (json.has("panY")) {
            webcamController.setPanY(json.optDouble("panY").toFloat())
        }
        if (json.has("cameraId")) {
            webcamController.selectCamera(json.optString("cameraId"))
        }
        if (json.has("rotation")) {
            webcamController.setRotation(json.optInt("rotation"))
        }
        if (json.has("mirror")) {
            webcamController.setMirrorHorizontal(json.optBoolean("mirror"))
        }
        if (json.has("jpegQuality")) {
            webcamController.setJpegQuality(json.optInt("jpegQuality"))
        }
        if (json.has("maxInFlightBuffers")) {
            webcamController.setMaxInFlightBuffers(json.optInt("maxInFlightBuffers"))
        }
        if (json.has("targetFpsCap")) {
            webcamController.setTargetFpsCap(json.optInt("targetFpsCap"))
        }
        if (json.has("autoFocus")) {
            webcamController.setAutoFocusEnabled(json.optBoolean("autoFocus"))
        }
        if (json.has("lockConstantFps")) {
            webcamController.setLockConstantFps(json.optBoolean("lockConstantFps"))
        }
        if (json.has("videoStabilization")) {
            webcamController.setVideoStabilization(json.optBoolean("videoStabilization"))
        }
        if (json.has("exposureEv")) {
            webcamController.setExposureEv(json.optDouble("exposureEv").toFloat())
        }
        if (json.has("aeLock")) {
            webcamController.setAeLock(json.optBoolean("aeLock"))
        }
        if (json.has("awbMode")) {
            webcamController.setAwbMode(json.optInt("awbMode"))
        }
        if (json.has("awbLock")) {
            webcamController.setAwbLock(json.optBoolean("awbLock"))
        }
        if (json.has("antiBandingMode")) {
            webcamController.setAntiBandingMode(json.optInt("antiBandingMode"))
        }
        if (json.has("torch")) {
            webcamController.setTorchEnabled(json.optBoolean("torch"))
        }
        if (json.has("studioQuality")) {
            webcamController.setStudioQualityProcessing(json.optBoolean("studioQuality"))
        }
    }

    suspend fun startWebcam() {
        if (!rootStateHolder.hasRootPermissions()) {
            Timber.w("Cannot start USB webcam: missing root permissions")
            return
        }
        try {
            val prefs = UserPreferencesRepository.getInstance(application).userPreferencesFlow.value
            val gadgetPrefs = GadgetUserPreferences.fromUserPreferences(prefs)
            val videoNode = characterDeviceManager.createCharacterDevices(gadgetPrefs)
            if (videoNode != null) {
                val actualMaxPacket = characterDeviceManager.getBoundStreamingMaxPacket(gadgetPrefs)
                WebcamForegroundService.start(application, videoNode, actualMaxPacket)
            }
        } catch (e: Exception) {
            Timber.e(e, "WebcamWebServer: Failed to start USB webcam")
        }
    }

    suspend fun stopWebcam() {
        try {
            WebcamForegroundService.stop(application)
            webcamController.stopUvcSession()
            if (rootStateHolder.hasRootPermissions()) {
                val prefs = UserPreferencesRepository.getInstance(application).userPreferencesFlow.value
                val gadgetPrefs = GadgetUserPreferences.fromUserPreferences(prefs)
                characterDeviceManager.deleteCharacterDevices(gadgetPrefs)
            }
        } catch (e: Exception) {
            Timber.e(e, "WebcamWebServer: Failed to stop USB webcam")
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: WebcamWebServer? = null

        fun getInstance(application: Application): WebcamWebServer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebcamWebServer(application).also { INSTANCE = it }
            }
        }

        private const val INDEX_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Android USB Webcam Control</title>
  <style>
    :root {
      --bg: #121214;
      --card-bg: #1a1a20;
      --card-border: #2c2c38;
      --text-main: #f0f0f4;
      --text-muted: #9e9ea8;
      --accent: #7c4dff;
      --accent-hover: #966eff;
      --accent-light: rgba(124, 77, 255, 0.15);
      --success: #00e676;
      --danger: #ff5252;
      --cyan: #00e5ff;
      --radius: 12px;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Oxygen, Ubuntu, Cantarell, sans-serif; }
    body { background: var(--bg); color: var(--text-main); line-height: 1.5; padding: 16px; min-height: 100vh; }
    .container { max-width: 1000px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
    
    /* Top Header */
    header { display: flex; justify-content: space-between; align-items: center; background: var(--card-bg); border: 1px solid var(--card-border); padding: 16px 20px; border-radius: var(--radius); flex-wrap: wrap; gap: 12px; }
    .header-title { display: flex; align-items: center; gap: 10px; }
    .header-title h1 { font-size: 1.25rem; font-weight: 700; color: #fff; }
    .badge { font-size: 0.75rem; font-weight: 600; padding: 4px 10px; border-radius: 999px; text-transform: uppercase; letter-spacing: 0.5px; }
    .badge.idle { background: #2f2f3a; color: var(--text-muted); }
    .badge.ready { background: rgba(0, 229, 255, 0.15); color: var(--cyan); border: 1px solid var(--cyan); }
    .badge.streaming { background: rgba(0, 230, 118, 0.15); color: var(--success); border: 1px solid var(--success); }
    
    /* Guide Banner */
    .adb-banner { background: #1f1b2d; border: 1px solid #4a347f; border-radius: var(--radius); padding: 14px 18px; display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
    .adb-text { font-size: 0.85rem; color: #d0c8f5; }
    .adb-cmd { font-family: monospace; background: #12101c; color: var(--cyan); padding: 4px 8px; border-radius: 6px; font-weight: 600; border: 1px solid #3d2b6b; user-select: all; }
    .btn-copy { background: var(--accent); color: #fff; border: none; padding: 6px 14px; border-radius: 6px; cursor: pointer; font-size: 0.8rem; font-weight: 600; transition: background 0.15s; }
    .btn-copy:hover { background: var(--accent-hover); }

    /* Grid Layout */
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(310px, 1fr)); gap: 16px; }
    .card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: var(--radius); padding: 18px; display: flex; flex-direction: column; gap: 14px; }
    .card-title { font-size: 1rem; font-weight: 700; color: #fff; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--card-border); padding-bottom: 10px; }
    
    /* Telemetry row */
    .stat-row { display: flex; justify-content: space-between; font-size: 0.85rem; border-bottom: 1px dashed #282835; padding: 4px 0; }
    .stat-label { color: var(--text-muted); }
    .stat-val { font-weight: 600; color: #fff; font-family: monospace; }
    
    /* Controls */
    .control-group { display: flex; flex-direction: column; gap: 6px; }
    .control-header { display: flex; justify-content: space-between; font-size: 0.85rem; font-weight: 600; }
    .control-header span:last-child { color: var(--accent); font-family: monospace; }
    
    input[type=range] { width: 100%; height: 6px; background: #2f2f3d; border-radius: 3px; outline: none; -webkit-appearance: none; cursor: pointer; }
    input[type=range]::-webkit-slider-thumb { -webkit-appearance: none; width: 18px; height: 18px; border-radius: 50%; background: var(--accent); cursor: pointer; box-shadow: 0 0 6px rgba(124, 77, 255, 0.5); }
    
    /* Toggle switch */
    .toggle-row { display: flex; justify-content: space-between; align-items: center; font-size: 0.85rem; padding: 2px 0; }
    .switch { position: relative; display: inline-block; width: 38px; height: 20px; }
    .switch input { opacity: 0; width: 0; height: 0; }
    .slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: #2f2f3a; border-radius: 20px; transition: .2s; }
    .slider:before { position: absolute; content: ""; height: 14px; width: 14px; left: 3px; bottom: 3px; background-color: white; border-radius: 50%; transition: .2s; }
    input:checked + .slider { background-color: var(--accent); }
    input:checked + .slider:before { transform: translateX(18px); }

    /* Button groups */
    .btn-row { display: flex; flex-wrap: wrap; gap: 6px; }
    .pill-btn { background: #252530; color: var(--text-muted); border: 1px solid #363645; padding: 5px 12px; border-radius: 6px; font-size: 0.8rem; cursor: pointer; transition: all 0.15s; font-weight: 500; }
    .pill-btn:hover { background: #323242; color: #fff; }
    .pill-btn.active { background: var(--accent); color: #fff; border-color: var(--accent); }
    
    /* D-Pad */
    .dpad-container { display: flex; flex-direction: column; align-items: center; gap: 6px; margin: 4px 0; }
    .dpad-row { display: flex; gap: 6px; }
    .dpad-btn { width: 50px; height: 40px; background: #252532; border: 1px solid #3b3b4f; border-radius: 6px; color: #fff; font-size: 1.1rem; cursor: pointer; display: flex; align-items: center; justify-content: center; user-select: none; transition: background 0.1s; }
    .dpad-btn:active { background: var(--accent); }
    .dpad-center { width: 50px; height: 40px; background: #2b2b3a; border: 1px solid #4a4a60; font-size: 0.75rem; font-weight: 600; color: #bbb; border-radius: 6px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
    .dpad-center:hover { background: #3b3b50; color: #fff; }

    /* Action Buttons */
    .btn-primary { background: var(--accent); color: #fff; border: none; padding: 10px 16px; border-radius: 8px; font-size: 0.9rem; font-weight: 600; cursor: pointer; transition: background 0.15s; }
    .btn-primary:hover { background: var(--accent-hover); }
    .btn-danger { background: var(--danger); color: #fff; border: none; padding: 10px 16px; border-radius: 8px; font-size: 0.9rem; font-weight: 600; cursor: pointer; }
    .btn-outline { background: transparent; color: var(--text-muted); border: 1px solid var(--card-border); padding: 8px 12px; border-radius: 6px; font-size: 0.8rem; cursor: pointer; transition: all 0.15s; }
    .btn-outline:hover { background: #22222d; color: #fff; }

    select { background: #252530; color: #fff; border: 1px solid #363645; padding: 6px 10px; border-radius: 6px; font-size: 0.85rem; width: 100%; outline: none; }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="header-title">
        <span style="font-size: 1.5rem;">📱</span>
        <div>
          <h1>Android USB Webcam Studio</h1>
          <span id="txt-substatus" style="font-size: 0.8rem; color: var(--text-muted);">ConfigFS UVC + V4L2 Hardware Controller</span>
        </div>
      </div>
      <div style="display: flex; gap: 10px; align-items: center;">
        <span id="badge-status" class="badge idle">Idle</span>
        <button id="btn-toggle-webcam" class="btn-primary" onclick="toggleWebcam()">Start Webcam</button>
      </div>
    </header>

    <div class="adb-banner">
      <div class="adb-text">
        ⚡ <b>Direct PC USB Connection:</b> In terminal on your PC, run: <span class="adb-cmd" id="adb-code">adb forward tcp:8080 tcp:8080</span>
      </div>
      <button class="btn-copy" onclick="copyAdbCmd()">Copy ADB Command</button>
    </div>

    <div class="grid">
      <!-- Live Telemetry Card -->
      <div class="card">
        <div class="card-title">
          <span>📊 Live Stream Telemetry</span>
          <span id="txt-fps-pill" style="font-size: 0.75rem; color: var(--cyan); font-family: monospace;">0.0 FPS</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">Negotiated Mode</span>
          <span class="stat-val" id="stat-mode">Idle</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">Stream Resolution</span>
          <span class="stat-val" id="stat-res">1280x720</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">Measured Delivery FPS</span>
          <span class="stat-val" id="stat-fps">0.0 fps</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">Frame Latency (Encode)</span>
          <span class="stat-val" id="stat-lat">0.0 ms</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">Average Frame Size</span>
          <span class="stat-val" id="stat-size">0.0 KB</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">USB Max Packet Payload</span>
          <span class="stat-val" id="stat-pkt">3072 B</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">V4L2 Device Node</span>
          <span class="stat-val" id="stat-node">/dev/video2</span>
        </div>
      </div>

      <!-- PTZ Card -->
      <div class="card">
        <div class="card-title">
          <span>🔍 Pan, Tilt & Zoom (PTZ)</span>
          <button class="btn-outline" style="padding: 2px 8px;" onclick="recenterPan()">Recenter</button>
        </div>

        <div class="control-group">
          <div class="control-header">
            <span>Digital Zoom</span>
            <span id="lbl-zoom">1.0x</span>
          </div>
          <input type="range" id="rng-zoom" min="1.0" max="4.0" step="0.1" value="1.0" oninput="onZoomInput(this.value)">
          <div class="btn-row" style="margin-top: 4px;">
            <button class="pill-btn" onclick="setZoomVal(1.0)">1.0x</button>
            <button class="pill-btn" onclick="setZoomVal(1.5)">1.5x</button>
            <button class="pill-btn" onclick="setZoomVal(2.0)">2.0x</button>
            <button class="pill-btn" onclick="setZoomVal(3.0)">3.0x</button>
            <button class="pill-btn" onclick="setZoomVal(4.0)">Max</button>
          </div>
        </div>

        <div class="control-group">
          <div class="control-header">
            <span>Horizontal Pan (X)</span>
            <span id="lbl-panx">0%</span>
          </div>
          <input type="range" id="rng-panx" min="-1.0" max="1.0" step="0.02" value="0.0" oninput="onPanXInput(this.value)">
        </div>

        <div class="control-group">
          <div class="control-header">
            <span>Vertical Tilt (Y)</span>
            <span id="lbl-pany">0%</span>
          </div>
          <input type="range" id="rng-pany" min="-1.0" max="1.0" step="0.02" value="0.0" oninput="onPanYInput(this.value)">
        </div>

        <div class="dpad-container">
          <div class="dpad-row">
            <button class="dpad-btn" onclick="stepPan(0, -0.1)" title="Tilt Up">▲</button>
          </div>
          <div class="dpad-row">
            <button class="dpad-btn" onclick="stepPan(-0.1, 0)" title="Pan Left">◀</button>
            <button class="dpad-center" onclick="recenterPan()" title="Center">Center</button>
            <button class="dpad-btn" onclick="stepPan(0.1, 0)" title="Pan Right">▶</button>
          </div>
          <div class="dpad-row">
            <button class="dpad-btn" onclick="stepPan(0, 0.1)" title="Tilt Down">▼</button>
          </div>
        </div>
      </div>

      <!-- Camera Lens & Video Encoding Card -->
      <div class="card">
        <div class="card-title">
          <span>📷 Camera Lens & Orientation</span>
        </div>

        <div class="control-group">
          <div class="control-header"><span>Lens Selection</span></div>
          <div class="btn-row" id="cam-lenses-container"></div>
        </div>

        <div class="control-group">
          <div class="control-header"><span>Rotation</span></div>
          <div class="btn-row">
            <button class="pill-btn" id="rot-0" onclick="setRotation(0)">0°</button>
            <button class="pill-btn" id="rot-90" onclick="setRotation(90)">90°</button>
            <button class="pill-btn" id="rot-180" onclick="setRotation(180)">180°</button>
            <button class="pill-btn" id="rot-270" onclick="setRotation(270)">270°</button>
          </div>
        </div>

        <div class="toggle-row">
          <span>Mirror Horizontally</span>
          <label class="switch">
            <input type="checkbox" id="chk-mirror" onchange="postSettings({mirror: this.checked})">
            <span class="slider"></span>
          </label>
        </div>

        <div class="control-group">
          <div class="control-header">
            <span>MJPEG Compression Quality</span>
            <span id="lbl-quality">82%</span>
          </div>
          <input type="range" id="rng-quality" min="1" max="100" step="1" value="82" oninput="onQualityInput(this.value)">
        </div>

        <div class="control-group">
          <div class="control-header"><span>Target FPS Cap</span></div>
          <div class="btn-row">
            <button class="pill-btn" id="fpscap-0" onclick="setFpsCap(0)">Auto</button>
            <button class="pill-btn" id="fpscap-60" onclick="setFpsCap(60)">60</button>
            <button class="pill-btn" id="fpscap-30" onclick="setFpsCap(30)">30</button>
            <button class="pill-btn" id="fpscap-15" onclick="setFpsCap(15)">15</button>
            <button class="pill-btn" id="fpscap-10" onclick="setFpsCap(10)">10</button>
            <button class="pill-btn" id="fpscap-5" onclick="setFpsCap(5)">5</button>
          </div>
        </div>

        <div class="control-group">
          <div class="control-header"><span>V4L2 Queue Depth</span></div>
          <div class="btn-row">
            <button class="pill-btn" id="buf-1" onclick="setBufDepth(1)">1 buf (Low Latency)</button>
            <button class="pill-btn" id="buf-2" onclick="setBufDepth(2)">2 bufs (Balanced)</button>
            <button class="pill-btn" id="buf-3" onclick="setBufDepth(3)">3 bufs (Smooth)</button>
          </div>
        </div>
      </div>

      <!-- ISP, Focus & Lighting Card -->
      <div class="card">
        <div class="card-title">
          <span>⚙️ Camera2 ISP & Lighting</span>
          <button class="btn-outline" style="padding: 2px 8px;" onclick="resetIsp()">Reset ISP</button>
        </div>

        <div class="toggle-row">
          <span>Continuous Autofocus</span>
          <label class="switch">
            <input type="checkbox" id="chk-af" onchange="postSettings({autoFocus: this.checked})">
            <span class="slider"></span>
          </label>
        </div>

        <div class="toggle-row">
          <span>Constant Framerate (AE Priority)</span>
          <label class="switch">
            <input type="checkbox" id="chk-constfps" onchange="postSettings({lockConstantFps: this.checked})">
            <span class="slider"></span>
          </label>
        </div>

        <div class="toggle-row">
          <span>Video Stabilization (EIS)</span>
          <label class="switch">
            <input type="checkbox" id="chk-eis" onchange="postSettings({videoStabilization: this.checked})">
            <span class="slider"></span>
          </label>
        </div>

        <div class="control-group">
          <div class="control-header">
            <span>Exposure EV</span>
            <span id="lbl-ev">0.0 EV</span>
          </div>
          <input type="range" id="rng-ev" min="-2.0" max="2.0" step="0.1" value="0.0" oninput="onEvInput(this.value)">
        </div>

        <div class="toggle-row">
          <span>Lock Exposure (AE Lock)</span>
          <label class="switch">
            <input type="checkbox" id="chk-aelock" onchange="postSettings({aeLock: this.checked})">
            <span class="slider"></span>
          </label>
        </div>

        <div class="toggle-row">
          <span>Studio Quality ISP (Noise Reduction)</span>
          <label class="switch">
            <input type="checkbox" id="chk-studio" onchange="postSettings({studioQuality: this.checked})">
            <span class="slider"></span>
          </label>
        </div>

        <div class="toggle-row" id="row-torch" style="display: none;">
          <span>LED Fill Light / Torch</span>
          <label class="switch">
            <input type="checkbox" id="chk-torch" onchange="postSettings({torch: this.checked})">
            <span class="slider"></span>
          </label>
        </div>

        <div class="control-group">
          <div class="control-header"><span>White Balance (AWB)</span></div>
          <select id="sel-awb" onchange="postSettings({awbMode: parseInt(this.value)})">
            <option value="1">Auto</option>
            <option value="5">Daylight (5500K)</option>
            <option value="6">Cloudy (6500K)</option>
            <option value="2">Incandescent (2800K)</option>
            <option value="3">Fluorescent (4000K)</option>
          </select>
        </div>

        <div class="toggle-row">
          <span>Lock White Balance (AWB Lock)</span>
          <label class="switch">
            <input type="checkbox" id="chk-awblock" onchange="postSettings({awbLock: this.checked})">
            <span class="slider"></span>
          </label>
        </div>

        <div class="control-group">
          <div class="control-header"><span>Anti-Banding / Anti-Flicker</span></div>
          <select id="sel-ab" onchange="postSettings({antiBandingMode: parseInt(this.value)})">
            <option value="3">Auto</option>
            <option value="1">50 Hz (UK / Europe / Asia)</option>
            <option value="2">60 Hz (US / Japan)</option>
            <option value="0">Off</option>
          </select>
        </div>
      </div>
    </div>
  </div>

  <script>
    let currentStatus = null;
    let isDragging = false;
    let dragTimeout = null;
    let debounceTimer = null;

    function copyAdbCmd() {
      const code = document.getElementById('adb-code').innerText;
      navigator.clipboard.writeText(code).then(() => {
        const btn = document.querySelector('.btn-copy');
        const orig = btn.innerText;
        btn.innerText = 'Copied!';
        btn.style.background = '#00e676';
        setTimeout(() => { btn.innerText = orig; btn.style.background = ''; }, 1500);
      });
    }

    function debouncedPostSettings(settings) {
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(() => {
        postSettings(settings);
      }, 50);
    }

    function postSettings(settings) {
      fetch('/api/settings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(settings)
      }).catch(console.error);
    }

    function postAction(act) {
      fetch('/api/action', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: act })
      }).then(() => fetchStatus()).catch(console.error);
    }

    function toggleWebcam() {
      if (currentStatus && currentStatus.isUvcLoopRunning) {
        postAction('stop');
      } else {
        postAction('start');
      }
    }

    function recenterPan() {
      document.getElementById('rng-panx').value = 0.0;
      document.getElementById('rng-pany').value = 0.0;
      document.getElementById('lbl-panx').innerText = '0%';
      document.getElementById('lbl-pany').innerText = '0%';
      postAction('recenter');
    }

    function resetIsp() {
      postAction('resetIsp');
    }

    function onZoomInput(val) {
      markInteracting();
      document.getElementById('lbl-zoom').innerText = parseFloat(val).toFixed(1) + 'x';
      debouncedPostSettings({ zoomRatio: parseFloat(val) });
    }

    function setZoomVal(val) {
      markInteracting();
      document.getElementById('rng-zoom').value = val;
      document.getElementById('lbl-zoom').innerText = parseFloat(val).toFixed(1) + 'x';
      postSettings({ zoomRatio: parseFloat(val) });
    }

    function onPanXInput(val) {
      markInteracting();
      const pct = Math.round(val * 100);
      document.getElementById('lbl-panx').innerText = (pct > 0 ? '+' : '') + pct + '%';
      debouncedPostSettings({ panX: parseFloat(val) });
    }

    function onPanYInput(val) {
      markInteracting();
      const pct = Math.round(val * 100);
      document.getElementById('lbl-pany').innerText = (pct > 0 ? '+' : '') + pct + '%';
      debouncedPostSettings({ panY: parseFloat(val) });
    }

    function stepPan(dx, dy) {
      markInteracting();
      let curX = parseFloat(document.getElementById('rng-panx').value) || 0;
      let curY = parseFloat(document.getElementById('rng-pany').value) || 0;
      curX = Math.max(-1.0, Math.min(1.0, curX + dx));
      curY = Math.max(-1.0, Math.min(1.0, curY + dy));
      document.getElementById('rng-panx').value = curX;
      document.getElementById('rng-pany').value = curY;
      onPanXInput(curX);
      onPanYInput(curY);
    }

    function onQualityInput(val) {
      markInteracting();
      document.getElementById('lbl-quality').innerText = val + '%';
      debouncedPostSettings({ jpegQuality: parseInt(val) });
    }

    function onEvInput(val) {
      markInteracting();
      const f = parseFloat(val);
      document.getElementById('lbl-ev').innerText = (f > 0 ? '+' : '') + f.toFixed(1) + ' EV';
      debouncedPostSettings({ exposureEv: f });
    }

    function setRotation(deg) {
      postSettings({ rotation: deg });
    }

    function setFpsCap(cap) {
      postSettings({ targetFpsCap: cap });
    }

    function setBufDepth(d) {
      postSettings({ maxInFlightBuffers: d });
    }

    function markInteracting() {
      isDragging = true;
      clearTimeout(dragTimeout);
      dragTimeout = setTimeout(() => { isDragging = false; }, 1200);
    }

    function fetchStatus() {
      fetch('/api/status')
        .then(r => r.json())
        .then(data => {
          currentStatus = data;
          updateUi(data);
        })
        .catch(console.error);
    }

    function updateUi(data) {
      // Top status badge & button
      const badge = document.getElementById('badge-status');
      const toggleBtn = document.getElementById('btn-toggle-webcam');
      if (data.isHostStreaming) {
        badge.className = 'badge streaming';
        badge.innerText = 'Streaming';
        toggleBtn.className = 'btn-danger';
        toggleBtn.innerText = 'Stop Webcam';
      } else if (data.isUvcLoopRunning) {
        badge.className = 'badge ready';
        badge.innerText = 'Gadget Ready';
        toggleBtn.className = 'btn-danger';
        toggleBtn.innerText = 'Stop Webcam';
      } else {
        badge.className = 'badge idle';
        badge.innerText = 'Idle';
        toggleBtn.className = 'btn-primary';
        toggleBtn.innerText = 'Start Webcam';
      }

      // Telemetry
      document.getElementById('txt-fps-pill').innerText = data.measuredFps.toFixed(1) + ' FPS';
      document.getElementById('stat-mode').innerText = data.format;
      document.getElementById('stat-res').innerText = data.width + 'x' + data.height + ' @ ' + data.fps + 'fps';
      document.getElementById('stat-fps').innerText = data.measuredFps.toFixed(1) + ' fps';
      document.getElementById('stat-lat').innerText = data.avgEncodeTimeMs.toFixed(1) + ' ms';
      document.getElementById('stat-size').innerText = data.avgFrameSizeKb.toFixed(1) + ' KB';
      document.getElementById('stat-pkt').innerText = data.boundMaxPacket + ' B';
      document.getElementById('stat-node').innerText = data.videoNodePath || 'None';

      if (isDragging) return; // Do not overwrite sliders while user is manipulating them

      // Sliders & Zoom
      const rngZoom = document.getElementById('rng-zoom');
      rngZoom.max = Math.max(4.0, data.maxZoom || 4.0);
      rngZoom.value = data.zoomRatio;
      document.getElementById('lbl-zoom').innerText = data.zoomRatio.toFixed(1) + 'x';

      document.getElementById('rng-panx').value = data.panX;
      const pctX = Math.round(data.panX * 100);
      document.getElementById('lbl-panx').innerText = (pctX > 0 ? '+' : '') + pctX + '%';

      document.getElementById('rng-pany').value = data.panY;
      const pctY = Math.round(data.panY * 100);
      document.getElementById('lbl-pany').innerText = (pctY > 0 ? '+' : '') + pctY + '%';

      // Lens buttons
      const lensesContainer = document.getElementById('cam-lenses-container');
      lensesContainer.innerHTML = '';
      let activeLensHasFlash = false;
      (data.availableCameras || []).forEach(cam => {
        const btn = document.createElement('button');
        btn.className = 'pill-btn' + (cam.id === data.selectedCameraId ? ' active' : '');
        btn.innerText = cam.label;
        btn.onclick = () => postSettings({ cameraId: cam.id });
        lensesContainer.appendChild(btn);
        if (cam.id === data.selectedCameraId && cam.hasFlash) {
          activeLensHasFlash = true;
        }
      });

      // Rotation pills
      [0, 90, 180, 270].forEach(deg => {
        const btn = document.getElementById('rot-' + deg);
        if (btn) btn.className = 'pill-btn' + (data.rotation === deg ? ' active' : '');
      });

      document.getElementById('chk-mirror').checked = !!data.mirror;

      document.getElementById('rng-quality').value = data.jpegQuality;
      document.getElementById('lbl-quality').innerText = data.jpegQuality + '%';

      // FPS cap pills
      [0, 60, 30, 15, 10, 5].forEach(cap => {
        const btn = document.getElementById('fpscap-' + cap);
        if (btn) btn.className = 'pill-btn' + (data.targetFpsCap === cap ? ' active' : '');
      });

      // V4L2 queue depth pills
      [1, 2, 3].forEach(d => {
        const btn = document.getElementById('buf-' + d);
        if (btn) btn.className = 'pill-btn' + (data.maxInFlightBuffers === d ? ' active' : '');
      });

      // ISP toggles
      document.getElementById('chk-af').checked = !!data.autoFocus;
      document.getElementById('chk-constfps').checked = !!data.lockConstantFps;
      document.getElementById('chk-eis').checked = !!data.videoStabilization;
      document.getElementById('chk-aelock').checked = !!data.aeLock;
      document.getElementById('chk-studio').checked = !!data.studioQuality;
      document.getElementById('chk-awblock').checked = !!data.awbLock;

      document.getElementById('rng-ev').value = data.exposureEv;
      const evVal = data.exposureEv;
      document.getElementById('lbl-ev').innerText = (evVal > 0 ? '+' : '') + evVal.toFixed(1) + ' EV';

      document.getElementById('sel-awb').value = data.awbMode;
      document.getElementById('sel-ab').value = data.antiBandingMode;

      const torchRow = document.getElementById('row-torch');
      if (activeLensHasFlash) {
        torchRow.style.display = 'flex';
        document.getElementById('chk-torch').checked = !!data.torch;
      } else {
        torchRow.style.display = 'none';
      }
    }

    // Auto refresh every 750ms
    fetchStatus();
    setInterval(fetchStatus, 750);
  </script>
</body>
</html>"""
    }
}
