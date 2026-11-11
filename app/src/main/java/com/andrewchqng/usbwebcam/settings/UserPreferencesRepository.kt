package com.andrewchqng.usbwebcam.settings

import android.app.Application
import android.content.SharedPreferences
import android.os.Parcelable
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.parcelize.Parcelize
import com.andrewchqng.usbwebcam.R
import com.andrewchqng.usbwebcam.hid_utils.CharacterDeviceManager
import com.andrewchqng.usbwebcam.hid_utils.UsbGadgetPath
import com.andrewchqng.usbwebcam.hid_utils.UvcVideoDevicePath

sealed class AppPreference(val preference: PreferenceKey<*>) {
    data object OnboardingDoneKey : BooleanPreferenceKey("onboarding_done", false)
    data object AppThemeKey : ObjectPreferenceKey<AppTheme>(
        "app_theme", AppTheme.System,
        fromStringPreference = {
            when (it) {
                AppTheme.System.key -> AppTheme.System
                AppTheme.DarkMode.key -> AppTheme.DarkMode
                AppTheme.LightMode.key -> AppTheme.LightMode
                else -> AppTheme.System
            }
        },
        toStringPreference = { it.key }
    )

    data object DynamicColorKey : BooleanPreferenceKey("dynamic_color", false)
    data object ExperimentalMode : BooleanPreferenceKey("experimental_mode", false)

    data object UsbGadgetPathPref : ObjectPreferenceKey<UsbGadgetPath>(
        "usb_gadget_path", UsbGadgetPath("/config/usb_gadget/g1"),
        fromStringPreference = { UsbGadgetPath(it) },
        toStringPreference = { it.path }
    )

    data object UvcVideoDevicePathPref : ObjectPreferenceKey<UvcVideoDevicePath>(
        "uvc_video_device_path", CharacterDeviceManager.Companion.DevicePaths.DEFAULT_UVC_DEVICE_PATH,
        fromStringPreference = { UvcVideoDevicePath(it) },
        toStringPreference = { it.path }
    )

    data object CreateNewGadgetForFunctions : BooleanPreferenceKey("create_new_gadget_for_functions", false)

    // When true, disables ADB alongside UVC (pure UVC-only mode). Default false (links UVC + ADB).
    data object DisableGadgetFunctionsDuringConfiguration :
        BooleanPreferenceKey("disable_gadget_functions_during_config", false)

    data object UvcMaxPacketSizePref : ObjectPreferenceKey<UvcMaxPacketOption>(
        "uvc_max_packet_size_v3", UvcMaxPacketOption.HighBandwidth3072,
        fromStringPreference = {
            when (it) {
                UvcMaxPacketOption.Standard1024.key -> UvcMaxPacketOption.Standard1024
                UvcMaxPacketOption.Medium2048.key -> UvcMaxPacketOption.Medium2048
                else -> UvcMaxPacketOption.HighBandwidth3072
            }
        },
        toStringPreference = { it.key }
    )

    data object UvcMaxBurstPref : ObjectPreferenceKey<UvcMaxBurstOption>(
        "uvc_max_burst", UvcMaxBurstOption.Burst0,
        fromStringPreference = {
            when (it) {
                UvcMaxBurstOption.Burst4.key -> UvcMaxBurstOption.Burst4
                UvcMaxBurstOption.Burst8.key -> UvcMaxBurstOption.Burst8
                UvcMaxBurstOption.Burst15.key -> UvcMaxBurstOption.Burst15
                else -> UvcMaxBurstOption.Burst0
            }
        },
        toStringPreference = { it.key }
    )

    data object BoostUsbIrqPriority : BooleanPreferenceKey("boost_usb_irq_priority", true)

    data object UvcExposedFormatsPref : ObjectPreferenceKey<UvcExposedFormatsOption>(
        "uvc_exposed_formats_v4", UvcExposedFormatsOption.Both,
        fromStringPreference = {
            when (it) {
                UvcExposedFormatsOption.MjpegOnly.key -> UvcExposedFormatsOption.MjpegOnly
                UvcExposedFormatsOption.YuyvOnly.key -> UvcExposedFormatsOption.YuyvOnly
                else -> UvcExposedFormatsOption.Both
            }
        },
        toStringPreference = { it.key }
    )

    data object UvcResolutionProfilePref : ObjectPreferenceKey<UvcResolutionProfileOption>(
        "uvc_resolution_profile_v1", UvcResolutionProfileOption.Max1080p,
        fromStringPreference = { UvcResolutionProfileOption.fromKey(it) },
        toStringPreference = { it.key }
    )

    data object SelectedResolutionsPref : ObjectPreferenceKey<Set<String>>(
        "selected_resolutions_v1",
        setOf("1920x1080", "1280x720", "640x480", "640x360"),
        fromStringPreference = { str ->
            if (str.isBlank()) setOf("1920x1080", "1280x720", "640x480", "640x360")
            else str.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        },
        toStringPreference = { it.joinToString(",") }
    )

    data object DefaultResolutionPref : ObjectPreferenceKey<String>(
        "default_resolution_v1", "1920x1080",
        fromStringPreference = { it },
        toStringPreference = { it }
    )

    data object CustomResolutionsPref : ObjectPreferenceKey<Set<String>>(
        "custom_resolutions_v1",
        emptySet(),
        fromStringPreference = { str ->
            if (str.isBlank()) emptySet()
            else str.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        },
        toStringPreference = { it.joinToString(",") }
    )

    data object CustomUsbProductNamePref : ObjectPreferenceKey<String>(
        "custom_usb_product_name", "",
        fromStringPreference = { it },
        toStringPreference = { it }
    )

    data object CustomUsbManufacturerNamePref : ObjectPreferenceKey<String>(
        "custom_usb_manufacturer_name", "",
        fromStringPreference = { it },
        toStringPreference = { it }
    )

    data object JpegQualityPref : IntPreferenceKey("jpeg_quality_v1", 82)
    data object MaxInFlightBuffersPref : IntPreferenceKey("max_in_flight_buffers_v1", 1)
    data object WebServerEnabledPref : BooleanPreferenceKey("web_server_enabled", true)
    data object WebServerPortPref : IntPreferenceKey("web_server_port", 8080)
}

sealed class SealedString(val key: String, @StringRes val id: Int)

sealed class UvcExposedFormatsOption(key: String, @StringRes id: Int) : SealedString(key, id) {
    data object Both : UvcExposedFormatsOption("both", R.string.uvc_format_both)
    data object MjpegOnly : UvcExposedFormatsOption("mjpeg", R.string.uvc_format_mjpeg_only)
    data object YuyvOnly : UvcExposedFormatsOption("yuyv", R.string.uvc_format_yuyv_only)

    companion object {
        val values: List<UvcExposedFormatsOption>
            get() = listOf(Both, MjpegOnly, YuyvOnly)
    }
}

sealed class UvcResolutionProfileOption(key: String, @StringRes id: Int, val profileId: Int) : SealedString(key, id) {
    data object Max1080p : UvcResolutionProfileOption("max_1080p", R.string.uvc_res_max_1080p, 0)
    data object Max1440p : UvcResolutionProfileOption("max_1440p", R.string.uvc_res_max_1440p, 1)
    data object Max4K : UvcResolutionProfileOption("max_4k", R.string.uvc_res_max_4k, 2)
    data object Max720p : UvcResolutionProfileOption("max_720p", R.string.uvc_res_max_720p, 3)
    data object Portrait916 : UvcResolutionProfileOption("portrait_9_16", R.string.uvc_res_portrait_9_16, 7)
    data object Only1080p : UvcResolutionProfileOption("only_1080p", R.string.uvc_res_only_1080p, 4)
    data object Only720p : UvcResolutionProfileOption("only_720p", R.string.uvc_res_only_720p, 5)
    data object Only4K : UvcResolutionProfileOption("only_4k", R.string.uvc_res_only_4k, 6)
    data object Custom : UvcResolutionProfileOption("custom", R.string.uvc_res_custom, 8)

    companion object {
        val values: List<UvcResolutionProfileOption>
            get() = listOf(Max1080p, Max1440p, Max4K, Max720p, Portrait916, Only1080p, Only720p, Only4K, Custom)

        fun fromKey(key: String): UvcResolutionProfileOption {
            return values.firstOrNull { it.key == key } ?: Max1080p
        }
    }
}

sealed class AppTheme(key: String, @StringRes id: Int) : SealedString(key, id) {
    data object System : AppTheme("system", R.string.app_theme_system)
    data object LightMode : AppTheme("light", R.string.app_theme_light_mode)
    data object DarkMode : AppTheme("dark", R.string.app_theme_dark_mode)

    companion object {
        val values: List<AppTheme>
            get() = listOf(System, LightMode, DarkMode)
    }
}

sealed class UvcMaxPacketOption(key: String, @StringRes id: Int, val bytes: Int) : SealedString(key, id) {
    data object Standard1024 : UvcMaxPacketOption("1024", R.string.uvc_maxpacket_1024, 1024)
    data object Medium2048 : UvcMaxPacketOption("2048", R.string.uvc_maxpacket_2048, 2048)
    data object HighBandwidth3072 : UvcMaxPacketOption("3072", R.string.uvc_maxpacket_3072, 3072)

    companion object {
        val values: List<UvcMaxPacketOption>
            get() = listOf(Standard1024, Medium2048, HighBandwidth3072)
    }
}

sealed class UvcMaxBurstOption(key: String, @StringRes id: Int, val burst: Int) : SealedString(key, id) {
    data object Burst0 : UvcMaxBurstOption("0", R.string.uvc_maxburst_0, 0)
    data object Burst4 : UvcMaxBurstOption("4", R.string.uvc_maxburst_4, 4)
    data object Burst8 : UvcMaxBurstOption("8", R.string.uvc_maxburst_8, 8)
    data object Burst15 : UvcMaxBurstOption("15", R.string.uvc_maxburst_15, 15)

    companion object {
        val values: List<UvcMaxBurstOption>
            get() = listOf(Burst0, Burst4, Burst8, Burst15)
    }
}

data class UserPreferences(
    val isOnboardingDone: Boolean,
    val appTheme: AppTheme,
    val isDynamicColorEnabled: Boolean,
    val isExperimentalModeEnabled: Boolean,
    val usbGadgetPath: UsbGadgetPath,
    val uvcVideoDevicePath: UvcVideoDevicePath,
    val createNewGadgetForFunctions: Boolean,
    val disableGadgetFunctionsDuringConfiguration: Boolean,
    val uvcMaxPacketOption: UvcMaxPacketOption,
    val uvcMaxBurstOption: UvcMaxBurstOption,
    val boostUsbIrqPriority: Boolean,
    val uvcExposedFormatsOption: UvcExposedFormatsOption = UvcExposedFormatsOption.Both,
    val uvcResolutionProfileOption: UvcResolutionProfileOption = UvcResolutionProfileOption.Max1080p,
    val selectedResolutions: Set<String> = setOf("1920x1080", "1280x720", "640x480", "640x360"),
    val defaultResolution: String = "1920x1080",
    val customResolutions: Set<String> = emptySet(),
    val customUsbProductName: String = "",
    val customUsbManufacturerName: String = "",
    val jpegQuality: Int = 82,
    val maxInFlightBuffers: Int = 1,
    val webServerEnabled: Boolean = true,
    val webServerPort: Int = 8080,
)

@Parcelize
data class GadgetUserPreferences(
    val usbGadgetPath: UsbGadgetPath,
    val uvcVideoDevicePath: UvcVideoDevicePath,
    val createNewGadgetForFunctions: Boolean,
    val disableGadgetFunctionsDuringConfiguration: Boolean,
    val uvcMaxPacketSize: Int = 3072,
    val uvcMaxBurst: Int = 0,
    val boostUsbIrqPriority: Boolean = true,
    val exposedFormats: String = "both",
    val resolutionProfile: String = "max_1080p",
    val resolutionProfileId: Int = 0,
    val customProductName: String = "",
    val customManufacturerName: String = "",
    val selectedResolutions: Set<String> = setOf("1920x1080", "1280x720", "640x480", "640x360"),
    val defaultResolution: String = "1920x1080",
    val resolvedWidths: IntArray = intArrayOf(1920, 1280, 640, 640),
    val resolvedHeights: IntArray = intArrayOf(1080, 720, 480, 360),
    val defaultFrameIndex: Int = 1,
) : Parcelable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GadgetUserPreferences

        if (usbGadgetPath != other.usbGadgetPath) return false
        if (uvcVideoDevicePath != other.uvcVideoDevicePath) return false
        if (createNewGadgetForFunctions != other.createNewGadgetForFunctions) return false
        if (disableGadgetFunctionsDuringConfiguration != other.disableGadgetFunctionsDuringConfiguration) return false
        if (uvcMaxPacketSize != other.uvcMaxPacketSize) return false
        if (uvcMaxBurst != other.uvcMaxBurst) return false
        if (boostUsbIrqPriority != other.boostUsbIrqPriority) return false
        if (exposedFormats != other.exposedFormats) return false
        if (resolutionProfile != other.resolutionProfile) return false
        if (resolutionProfileId != other.resolutionProfileId) return false
        if (customProductName != other.customProductName) return false
        if (customManufacturerName != other.customManufacturerName) return false
        if (selectedResolutions != other.selectedResolutions) return false
        if (defaultResolution != other.defaultResolution) return false
        if (!resolvedWidths.contentEquals(other.resolvedWidths)) return false
        if (!resolvedHeights.contentEquals(other.resolvedHeights)) return false
        if (defaultFrameIndex != other.defaultFrameIndex) return false

        return true
    }

    override fun hashCode(): Int {
        var result = usbGadgetPath.hashCode()
        result = 31 * result + uvcVideoDevicePath.hashCode()
        result = 31 * result + createNewGadgetForFunctions.hashCode()
        result = 31 * result + disableGadgetFunctionsDuringConfiguration.hashCode()
        result = 31 * result + uvcMaxPacketSize
        result = 31 * result + uvcMaxBurst
        result = 31 * result + boostUsbIrqPriority.hashCode()
        result = 31 * result + exposedFormats.hashCode()
        result = 31 * result + resolutionProfile.hashCode()
        result = 31 * result + resolutionProfileId
        result = 31 * result + customProductName.hashCode()
        result = 31 * result + customManufacturerName.hashCode()
        result = 31 * result + selectedResolutions.hashCode()
        result = 31 * result + defaultResolution.hashCode()
        result = 31 * result + resolvedWidths.contentHashCode()
        result = 31 * result + resolvedHeights.contentHashCode()
        result = 31 * result + defaultFrameIndex
        return result
    }

    companion object {
        fun fromUserPreferences(userPreferences: UserPreferences): GadgetUserPreferences {
            val profile = userPreferences.uvcResolutionProfileOption
            val items: List<ResolutionItem> = if (profile == UvcResolutionProfileOption.Custom) {
                val parsed = userPreferences.selectedResolutions.mapNotNull { ResolutionItem.fromKey(it) }
                if (parsed.isNotEmpty()) {
                    parsed.sorted()
                } else {
                    listOf(ResolutionItem(1920, 1080))
                }
            } else {
                val (presetItems, _) = ResolutionItem.getPresetResolutions(profile.key)
                presetItems
            }

            val capped = items.take(16)
            val widths = capped.map { it.width }.toIntArray()
            val heights = capped.map { it.height }.toIntArray()

            val defaultKey = if (profile == UvcResolutionProfileOption.Custom) {
                userPreferences.defaultResolution
            } else {
                val (_, presetDef) = ResolutionItem.getPresetResolutions(profile.key)
                presetDef.key
            }

            val defIndex = capped.indexOfFirst { it.key == defaultKey }
            val resolvedDefaultFrameIndex = if (defIndex >= 0) defIndex + 1 else 1

            return GadgetUserPreferences(
                usbGadgetPath = userPreferences.usbGadgetPath,
                uvcVideoDevicePath = userPreferences.uvcVideoDevicePath,
                createNewGadgetForFunctions = userPreferences.createNewGadgetForFunctions,
                disableGadgetFunctionsDuringConfiguration = userPreferences.disableGadgetFunctionsDuringConfiguration,
                uvcMaxPacketSize = userPreferences.uvcMaxPacketOption.bytes,
                uvcMaxBurst = userPreferences.uvcMaxBurstOption.burst,
                boostUsbIrqPriority = userPreferences.boostUsbIrqPriority,
                exposedFormats = userPreferences.uvcExposedFormatsOption.key,
                resolutionProfile = userPreferences.uvcResolutionProfileOption.key,
                resolutionProfileId = userPreferences.uvcResolutionProfileOption.profileId,
                customProductName = userPreferences.customUsbProductName,
                customManufacturerName = userPreferences.customUsbManufacturerName,
                selectedResolutions = userPreferences.selectedResolutions,
                defaultResolution = userPreferences.defaultResolution,
                resolvedWidths = widths,
                resolvedHeights = heights,
                defaultFrameIndex = resolvedDefaultFrameIndex,
            )
        }
    }
}

class UserPreferencesRepository private constructor(application: Application) {
    private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)

    private val _userPreferencesFlow = MutableStateFlow(userPreferences)
    val userPreferencesFlow: StateFlow<UserPreferences> = _userPreferencesFlow

    private fun <T> PreferenceKey<T>.getValue() = this.getValue(sharedPreferences)
    private fun <T> PreferenceKey<T>.setValue(value: T) = this.setValue(sharedPreferences, value)
    private fun <T> PreferenceKey<T>.resetToDefault() = this.resetToDefault(sharedPreferences)

    private val userPreferences: UserPreferences
        get() {
            return UserPreferences(
                isOnboardingDone = AppPreference.OnboardingDoneKey.getValue(),
                appTheme = AppPreference.AppThemeKey.getValue(),
                isDynamicColorEnabled = AppPreference.DynamicColorKey.getValue(),
                isExperimentalModeEnabled = AppPreference.ExperimentalMode.getValue(),
                usbGadgetPath = AppPreference.UsbGadgetPathPref.getValue(),
                uvcVideoDevicePath = AppPreference.UvcVideoDevicePathPref.getValue(),
                createNewGadgetForFunctions = AppPreference.CreateNewGadgetForFunctions.getValue(),
                disableGadgetFunctionsDuringConfiguration = AppPreference.DisableGadgetFunctionsDuringConfiguration.getValue(),
                uvcMaxPacketOption = AppPreference.UvcMaxPacketSizePref.getValue(),
                uvcMaxBurstOption = AppPreference.UvcMaxBurstPref.getValue(),
                boostUsbIrqPriority = AppPreference.BoostUsbIrqPriority.getValue(),
                uvcExposedFormatsOption = AppPreference.UvcExposedFormatsPref.getValue(),
                uvcResolutionProfileOption = AppPreference.UvcResolutionProfilePref.getValue(),
                selectedResolutions = AppPreference.SelectedResolutionsPref.getValue(),
                defaultResolution = AppPreference.DefaultResolutionPref.getValue(),
                customResolutions = AppPreference.CustomResolutionsPref.getValue(),
                customUsbProductName = AppPreference.CustomUsbProductNamePref.getValue(),
                customUsbManufacturerName = AppPreference.CustomUsbManufacturerNamePref.getValue(),
                jpegQuality = AppPreference.JpegQualityPref.getValue(),
                maxInFlightBuffers = AppPreference.MaxInFlightBuffersPref.getValue(),
                webServerEnabled = AppPreference.WebServerEnabledPref.getValue(),
                webServerPort = AppPreference.WebServerPortPref.getValue(),
            )
        }

    fun <T> getPreference(key: PreferenceKey<T>): T =
        key.getValue()

    fun <T> setPreference(key: PreferenceKey<T>, value: T) {
        key.setValue(value)
        _userPreferencesFlow.update { userPreferences }
    }

    fun <T> resetPreferenceToDefault(key: PreferenceKey<T>) {
        key.resetToDefault()
        _userPreferencesFlow.update { userPreferences }
    }

    fun selectResolutionProfile(profile: UvcResolutionProfileOption) {
        setPreference(AppPreference.UvcResolutionProfilePref, profile)
        if (profile != UvcResolutionProfileOption.Custom) {
            val (presetItems, presetDefault) = ResolutionItem.getPresetResolutions(profile.key)
            setPreference(AppPreference.SelectedResolutionsPref, presetItems.map { it.key }.toSet())
            setPreference(AppPreference.DefaultResolutionPref, presetDefault.key)
        }
    }

    fun toggleResolution(resKey: String, isSelected: Boolean) {
        val current = getPreference(AppPreference.SelectedResolutionsPref).toMutableSet()
        if (isSelected) {
            current.add(resKey)
        } else {
            if (current.size > 1) {
                current.remove(resKey)
            }
        }
        setPreference(AppPreference.SelectedResolutionsPref, current)
        setPreference(AppPreference.UvcResolutionProfilePref, UvcResolutionProfileOption.Custom)

        val defaultRes = getPreference(AppPreference.DefaultResolutionPref)
        if (!current.contains(defaultRes)) {
            current.firstOrNull()?.let {
                setPreference(AppPreference.DefaultResolutionPref, it)
            }
        }
    }

    fun setDefaultResolution(resKey: String) {
        val current = getPreference(AppPreference.SelectedResolutionsPref).toMutableSet()
        current.add(resKey)
        setPreference(AppPreference.SelectedResolutionsPref, current)
        setPreference(AppPreference.DefaultResolutionPref, resKey)
    }

    fun addCustomResolution(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || width > 7680 || height > 7680) return false
        val key = "${width}x${height}"
        val custom = getPreference(AppPreference.CustomResolutionsPref).toMutableSet()
        custom.add(key)
        setPreference(AppPreference.CustomResolutionsPref, custom)

        val selected = getPreference(AppPreference.SelectedResolutionsPref).toMutableSet()
        selected.add(key)
        setPreference(AppPreference.SelectedResolutionsPref, selected)
        setPreference(AppPreference.UvcResolutionProfilePref, UvcResolutionProfileOption.Custom)
        return true
    }

    fun removeCustomResolution(key: String) {
        val custom = getPreference(AppPreference.CustomResolutionsPref).toMutableSet()
        custom.remove(key)
        setPreference(AppPreference.CustomResolutionsPref, custom)

        val selected = getPreference(AppPreference.SelectedResolutionsPref).toMutableSet()
        if (selected.contains(key) && selected.size > 1) {
            selected.remove(key)
            setPreference(AppPreference.SelectedResolutionsPref, selected)
            if (getPreference(AppPreference.DefaultResolutionPref) == key) {
                selected.firstOrNull()?.let {
                    setPreference(AppPreference.DefaultResolutionPref, it)
                }
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: UserPreferencesRepository? = null

        fun getInstance(application: Application): UserPreferencesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserPreferencesRepository(application).also { INSTANCE = it }
            }
        }
    }
}
