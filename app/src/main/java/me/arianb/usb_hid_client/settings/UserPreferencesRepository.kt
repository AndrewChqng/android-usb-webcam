package me.arianb.usb_hid_client.settings

import android.app.Application
import android.content.SharedPreferences
import android.os.Parcelable
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.parcelize.Parcelize
import me.arianb.usb_hid_client.R
import me.arianb.usb_hid_client.hid_utils.CharacterDeviceManager
import me.arianb.usb_hid_client.hid_utils.UsbGadgetPath
import me.arianb.usb_hid_client.hid_utils.UvcVideoDevicePath

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
        "uvc_max_packet_size", UvcMaxPacketOption.Standard1024,
        fromStringPreference = {
            when (it) {
                UvcMaxPacketOption.HighBandwidth3072.key -> UvcMaxPacketOption.HighBandwidth3072
                UvcMaxPacketOption.Medium2048.key -> UvcMaxPacketOption.Medium2048
                else -> UvcMaxPacketOption.Standard1024
            }
        },
        toStringPreference = { it.key }
    )
}

sealed class SealedString(val key: String, @StringRes val id: Int)

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
    data object HighBandwidth3072 : UvcMaxPacketOption("3072", R.string.uvc_maxpacket_3072, 3072)
    data object Medium2048 : UvcMaxPacketOption("2048", R.string.uvc_maxpacket_2048, 2048)
    data object Standard1024 : UvcMaxPacketOption("1024", R.string.uvc_maxpacket_1024, 1024)

    companion object {
        val values: List<UvcMaxPacketOption>
            get() = listOf(HighBandwidth3072, Medium2048, Standard1024)
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
)

@Parcelize
data class GadgetUserPreferences(
    val usbGadgetPath: UsbGadgetPath,
    val uvcVideoDevicePath: UvcVideoDevicePath,
    val createNewGadgetForFunctions: Boolean,
    val disableGadgetFunctionsDuringConfiguration: Boolean,
    val uvcMaxPacketSize: Int = 1024,
) : Parcelable {
    companion object {
        fun fromUserPreferences(userPreferences: UserPreferences): GadgetUserPreferences {
            return GadgetUserPreferences(
                usbGadgetPath = userPreferences.usbGadgetPath,
                uvcVideoDevicePath = userPreferences.uvcVideoDevicePath,
                createNewGadgetForFunctions = userPreferences.createNewGadgetForFunctions,
                disableGadgetFunctionsDuringConfiguration = userPreferences.disableGadgetFunctionsDuringConfiguration,
                uvcMaxPacketSize = userPreferences.uvcMaxPacketOption.bytes,
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
