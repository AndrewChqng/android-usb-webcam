package com.andrewchqng.usbwebcam

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.andrewchqng.usbwebcam.hid_utils.CharacterDeviceManager
import com.andrewchqng.usbwebcam.hid_utils.DevicePath
import com.andrewchqng.usbwebcam.hid_utils.ModifiesStateDirectly
import com.andrewchqng.usbwebcam.settings.AppPreference
import com.andrewchqng.usbwebcam.settings.GadgetUserPreferences
import com.andrewchqng.usbwebcam.settings.ResolutionItem
import com.andrewchqng.usbwebcam.settings.UvcResolutionProfileOption
import com.andrewchqng.usbwebcam.settings.UserPreferencesRepository
import com.andrewchqng.usbwebcam.shell_utils.RootStateHolder
import com.andrewchqng.usbwebcam.webcam.WebcamController
import com.andrewchqng.usbwebcam.webcam.WebcamForegroundService
import com.andrewchqng.usbwebcam.webcam.WebcamStreamState
import com.andrewchqng.usbwebcam.webserver.WebcamWebServer
import com.andrewchqng.usbwebcam.webserver.WebServerState
import timber.log.Timber

data class MyUiState(
    val missingCharacterDevice: Boolean = true,
    val isCharacterDevicePermissionsBroken: String? = null,
    val isDeviceUnplugged: Boolean = false,
    val isBusyConfiguringGadget: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MyUiState())
    val uiState: StateFlow<MyUiState> = _uiState

    private val characterDeviceManager = CharacterDeviceManager.getInstance(application)
    private val rootStateHolder = RootStateHolder.getInstance()
    private val userPreferencesRepository = UserPreferencesRepository.getInstance(application)
    val userPreferencesStateFlow = userPreferencesRepository.userPreferencesFlow
    private val webcamController = WebcamController.getInstance(application)
    private val webServer = WebcamWebServer.getInstance(application)

    val webcamState: StateFlow<WebcamStreamState> = webcamController.state
    val webServerState: StateFlow<WebServerState> = webServer.state

    init {
        webcamController.refreshCameraList()
        val prefs = userPreferencesStateFlow.value
        if (prefs.webServerEnabled) {
            webServer.start(prefs.webServerPort)
        }
    }

    fun setWebServerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            UserPreferencesRepository.getInstance(getApplication()).setPreference(
                AppPreference.WebServerEnabledPref,
                enabled
            )
            if (enabled) {
                val port = userPreferencesStateFlow.value.webServerPort
                webServer.start(port)
            } else {
                webServer.stop()
            }
        }
    }

    fun restartWebServer() {
        val port = userPreferencesStateFlow.value.webServerPort
        webServer.restart(port)
    }

    fun startUsbWebcam() {
        if (!rootStateHolder.hasRootPermissions()) {
            Timber.w("Cannot start USB webcam: missing root permissions")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusyConfiguringGadget = true) }
            try {
                val prefs = userPreferencesStateFlow.value
                val gadgetPrefs = GadgetUserPreferences.fromUserPreferences(prefs)
                val videoNode = characterDeviceManager.createCharacterDevices(gadgetPrefs)
                val missing = (videoNode == null)
                _uiState.update { it.copy(missingCharacterDevice = missing, isBusyConfiguringGadget = false) }

                if (videoNode != null) {
                    val actualMaxPacket = characterDeviceManager.getBoundStreamingMaxPacket(gadgetPrefs)
                    WebcamForegroundService.start(
                        getApplication(),
                        videoNode,
                        actualMaxPacket
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start USB webcam")
                _uiState.update { it.copy(isBusyConfiguringGadget = false) }
            }
        }
    }

    fun stopUsbWebcam() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusyConfiguringGadget = true) }
            try {
                WebcamForegroundService.stop(getApplication())
                webcamController.stopUvcSession()
                if (rootStateHolder.hasRootPermissions()) {
                    val gadgetPrefs = GadgetUserPreferences.fromUserPreferences(userPreferencesStateFlow.value)
                    characterDeviceManager.deleteCharacterDevices(gadgetPrefs)
                }
            } finally {
                _uiState.update { it.copy(missingCharacterDevice = true, isBusyConfiguringGadget = false) }
            }
        }
    }

    fun createCharacterDevices() = startUsbWebcam()

    fun deleteCharacterDevices() = stopUsbWebcam()

    fun fixCharacterDevicePermissions(device: String) {
        if (!rootStateHolder.hasRootPermissions()) {
            Timber.w("Can't fix character device permissions, missing root permissions")
            return
        }
        characterDeviceManager.fixCharacterDevicePermissions(device)
    }

    fun setResolutionProfile(profile: UvcResolutionProfileOption) {
        userPreferencesRepository.selectResolutionProfile(profile)
    }

    fun toggleResolution(resKey: String, isSelected: Boolean) {
        userPreferencesRepository.toggleResolution(resKey, isSelected)
    }

    fun setDefaultResolution(resKey: String) {
        userPreferencesRepository.setDefaultResolution(resKey)
    }

    fun addCustomResolution(width: Int, height: Int): Boolean {
        return userPreferencesRepository.addCustomResolution(width, height)
    }

    fun removeCustomResolution(resKey: String) {
        userPreferencesRepository.removeCustomResolution(resKey)
    }

    fun getDiscoveredResolutions(): List<ResolutionItem> {
        val app = getApplication<Application>()
        val camManager = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val discovered = if (camManager != null) {
            ResolutionItem.discoverSensorResolutions(camManager)
        } else {
            ResolutionItem.BASELINE_RESOLUTIONS
        }.toMutableSet()

        val customKeys = userPreferencesStateFlow.value.customResolutions
        for (k in customKeys) {
            ResolutionItem.fromKey(k, isCustom = true)?.let { discovered.add(it) }
        }

        return discovered.sorted()
    }

    @OptIn(ModifiesStateDirectly::class)
    fun characterDeviceMissing(charDevicePath: DevicePath): Boolean {
        val result = characterDeviceManager.characterDeviceMissing(charDevicePath)
        _uiState.update { it.copy(missingCharacterDevice = result) }
        return result
    }

    @OptIn(ModifiesStateDirectly::class)
    fun anyCharacterDeviceMissing(): Boolean {
        val result = characterDeviceManager.anyCharacterDeviceMissing()
        _uiState.update { it.copy(missingCharacterDevice = result) }
        return result
    }

    fun selectCamera(cameraId: String) = webcamController.selectCamera(cameraId)
    fun setRotation(degrees: Int) = webcamController.setRotation(degrees)
    fun setMirrorHorizontal(mirror: Boolean) = webcamController.setMirrorHorizontal(mirror)
    fun setJpegQuality(quality: Int) = webcamController.setJpegQuality(quality)
    fun setMaxInFlightBuffers(count: Int) = webcamController.setMaxInFlightBuffers(count)
    fun setTargetFpsCap(fpsCap: Int) = webcamController.setTargetFpsCap(fpsCap)
    fun setAutoFocusEnabled(enabled: Boolean) = webcamController.setAutoFocusEnabled(enabled)
    fun setLockConstantFps(locked: Boolean) = webcamController.setLockConstantFps(locked)
    fun setVideoStabilization(enabled: Boolean) = webcamController.setVideoStabilization(enabled)
    fun setZoomRatio(zoom: Float) = webcamController.setZoomRatio(zoom)
    fun setPanX(x: Float) = webcamController.setPanX(x)
    fun setPanY(y: Float) = webcamController.setPanY(y)
    fun resetPan() = webcamController.resetPan()
    fun setExposureEv(ev: Float) = webcamController.setExposureEv(ev)
    fun setAwbMode(mode: Int) = webcamController.setAwbMode(mode)
    fun setAwbLock(locked: Boolean) = webcamController.setAwbLock(locked)
    fun setAeLock(locked: Boolean) = webcamController.setAeLock(locked)
    fun setAntiBandingMode(mode: Int) = webcamController.setAntiBandingMode(mode)
    fun setTorchEnabled(enabled: Boolean) = webcamController.setTorchEnabled(enabled)
    fun setStudioQualityProcessing(enabled: Boolean) = webcamController.setStudioQualityProcessing(enabled)
    fun resetIspControls() = webcamController.resetIspControls()
    fun setPreviewEnabled(enabled: Boolean) = webcamController.setPreviewEnabled(enabled)
    fun attachPreviewSurface(surface: Surface?) = webcamController.attachPreviewSurface(surface)
}
