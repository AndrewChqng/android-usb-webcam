package com.andrewchqng.usbwebcam.webserver

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraManager
import com.andrewchqng.usbwebcam.hid_utils.CharacterDeviceManager
import com.andrewchqng.usbwebcam.settings.AppPreference
import com.andrewchqng.usbwebcam.settings.GadgetUserPreferences
import com.andrewchqng.usbwebcam.settings.ResolutionItem
import com.andrewchqng.usbwebcam.settings.UvcResolutionProfileOption
import com.andrewchqng.usbwebcam.settings.UserPreferencesRepository
import com.andrewchqng.usbwebcam.shell_utils.RootStateHolder
import com.andrewchqng.usbwebcam.webcam.WebcamController
import com.andrewchqng.usbwebcam.webcam.WebcamForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    private fun loadWebAsset(fileName: String): String =
        application.assets.open("web/$fileName").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private val indexHtml: String by lazy { loadWebAsset("index.html") }
    private val stylesCss: String by lazy { loadWebAsset("styles.css") }
    private val appJs: String by lazy { loadWebAsset("app.js") }

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
                (method == "GET" || method == "HEAD") && (path == "/" || path == "/index.html") -> {
                    sendResponse(outputStream, 200, "OK", "text/html; charset=UTF-8", if (method == "HEAD") "" else indexHtml)
                }
                (method == "GET" || method == "HEAD") && path == "/styles.css" -> {
                    sendResponse(outputStream, 200, "OK", "text/css; charset=UTF-8", if (method == "HEAD") "" else stylesCss)
                }
                (method == "GET" || method == "HEAD") && path == "/app.js" -> {
                    sendResponse(outputStream, 200, "OK", "application/javascript; charset=UTF-8", if (method == "HEAD") "" else appJs)
                }
                (method == "GET" || method == "HEAD") && path == "/api/status" -> {
                    val json = buildStatusJson()
                    sendResponse(outputStream, 200, "OK", "application/json; charset=UTF-8", if (method == "HEAD") "" else json.toString())
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
        json.put("useNativeCamera2Ptz", s.useNativeCamera2Ptz)

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

        val userPrefs = UserPreferencesRepository.getInstance(application).userPreferencesFlow.value
        json.put("resolutionProfile", userPrefs.uvcResolutionProfileOption.key)
        json.put("defaultResolution", userPrefs.defaultResolution)

        val selArray = JSONArray()
        for (r in userPrefs.selectedResolutions) {
            selArray.put(r)
        }
        json.put("selectedResolutions", selArray)

        val customArray = JSONArray()
        for (c in userPrefs.customResolutions) {
            customArray.put(c)
        }
        json.put("customResolutions", customArray)

        val camManager = application.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val discovered = if (camManager != null) {
            ResolutionItem.discoverSensorResolutions(camManager)
        } else {
            ResolutionItem.BASELINE_RESOLUTIONS
        }.toMutableSet()
        for (k in userPrefs.customResolutions) {
            ResolutionItem.fromKey(k, isCustom = true)?.let { discovered.add(it) }
        }
        val availArray = JSONArray()
        for (item in discovered.sorted()) {
            val itemObj = JSONObject()
            itemObj.put("key", item.key)
            itemObj.put("width", item.width)
            itemObj.put("height", item.height)
            itemObj.put("aspectRatio", item.aspectRatio)
            itemObj.put("displayName", item.displayName)
            itemObj.put("isCustom", userPrefs.customResolutions.contains(item.key))
            availArray.put(itemObj)
        }
        json.put("availableResolutions", availArray)

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
            "restart" -> {
                scope.launch {
                    stopWebcam()
                    delay(400)
                    startWebcam()
                }
            }
            "recenter" -> {
                webcamController.resetPan()
            }
            "resetIsp" -> {
                webcamController.resetIspControls()
            }
            "toggleResolution" -> {
                val res = json.optString("resolution")
                val sel = json.optBoolean("selected")
                if (res.isNotEmpty()) {
                    UserPreferencesRepository.getInstance(application).toggleResolution(res, sel)
                }
            }
            "setDefaultResolution" -> {
                val res = json.optString("resolution")
                if (res.isNotEmpty()) {
                    UserPreferencesRepository.getInstance(application).setDefaultResolution(res)
                }
            }
            "addCustomResolution" -> {
                val w = json.optInt("width")
                val h = json.optInt("height")
                if (w > 0 && h > 0) {
                    UserPreferencesRepository.getInstance(application).addCustomResolution(w, h)
                }
            }
            "removeCustomResolution" -> {
                val res = json.optString("resolution")
                if (res.isNotEmpty()) {
                    UserPreferencesRepository.getInstance(application).removeCustomResolution(res)
                }
            }
            "selectAllResolutions" -> {
                val arr = json.optJSONArray("resolutions")
                if (arr != null) {
                    val keys = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
                    UserPreferencesRepository.getInstance(application).selectAllResolutions(keys)
                }
            }
            "deselectAllResolutions" -> {
                val arr = json.optJSONArray("resolutions")
                if (arr != null) {
                    val keys = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
                    UserPreferencesRepository.getInstance(application).deselectAllResolutions(keys)
                }
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
        if (json.has("resolutionProfile")) {
            val key = json.optString("resolutionProfile")
            val opt = UvcResolutionProfileOption.fromKey(key)
            UserPreferencesRepository.getInstance(application).selectResolutionProfile(opt)
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
        if (json.has("useNativeCamera2Ptz")) {
            webcamController.setUseNativeCamera2Ptz(json.optBoolean("useNativeCamera2Ptz"))
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
    }
}
