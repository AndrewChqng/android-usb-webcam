package com.andrewchqng.usbwebcam.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import com.andrewchqng.usbwebcam.R
import com.andrewchqng.usbwebcam.settings.AppSettings.AppThemePreference
import com.andrewchqng.usbwebcam.settings.AppSettings.BoostUsbIrqPreference
import com.andrewchqng.usbwebcam.settings.AppSettings.DynamicColors
import com.andrewchqng.usbwebcam.settings.AppSettings.ExperimentalMode
import com.andrewchqng.usbwebcam.settings.AppSettings.PreferenceCategory
import com.andrewchqng.usbwebcam.settings.AppSettings.PureUvcOnlyModePreference
import com.andrewchqng.usbwebcam.settings.AppSettings.UsbGadgetPath
import com.andrewchqng.usbwebcam.settings.AppSettings.UvcMaxBurstPreference
import com.andrewchqng.usbwebcam.settings.AppSettings.UvcMaxPacketPreference
import com.andrewchqng.usbwebcam.settings.AppSettings.UvcVideoDevicePath
import com.andrewchqng.usbwebcam.ui.theme.PaddingNormal
import com.andrewchqng.usbwebcam.ui.theme.isDynamicColorAvailable
import com.andrewchqng.usbwebcam.ui.utils.BasicPage
import com.andrewchqng.usbwebcam.ui.utils.DarkLightModePreviews
import com.andrewchqng.usbwebcam.ui.utils.SimpleNavTopBar

class SettingsScreen : Screen {
    @Composable
    override fun Content() {
        SettingsPage()
    }
}

@Composable
fun SettingsPage() {
    val padding = PaddingNormal

    BasicPage(
        topBar = { SettingsTopBar() },
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(padding, Alignment.Top),
        scrollable = true
    ) {
        PreferenceCategory(
            title = stringResource(R.string.theme_header),
        ) {
            AppThemePreference()

            if (isDynamicColorAvailable()) {
                DynamicColors()
            }
        }

        PreferenceCategory(
            title = stringResource(R.string.uvc_gadget_header),
        ) {
            AppSettings.UvcResolutionProfilePreference()
            AppSettings.UvcExposedFormatsPreference()
            AppSettings.UvcMaxPacketPreference()
            AppSettings.UvcMaxBurstPreference()
            AppSettings.BoostUsbIrqPreference()
            AppSettings.PureUvcOnlyModePreference()
            AppSettings.CustomUsbProductNamePreference()
            AppSettings.CustomUsbManufacturerNamePreference()
        }

        PreferenceCategory(
            title = stringResource(R.string.device_specific_quirks_header),
            showDivider = false
        ) {
            UsbGadgetPath()
            UvcVideoDevicePath()
            ExperimentalMode()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar() {
    SimpleNavTopBar(
        title = stringResource(R.string.settings)
    )
}

private object AppSettings {
    @Composable
    fun PreferenceCategory(
        title: String,
        showDivider: Boolean = true,
        preferences: @Composable (() -> Unit)
    ) {
        val paddingModifier = Modifier.padding(horizontal = PaddingNormal)

        PreferenceCategory(
            title = title,
            modifier = paddingModifier,
            showDivider = showDivider,
            preferences = preferences,
        )
    }

    @Composable
    fun AppThemePreference(
        enabled: Boolean = true,
        settingsViewModel: SettingsViewModel = viewModel()
    ) {
        val preferencesState by settingsViewModel.userPreferencesFlow.collectAsState()

        val selectedTheme = preferencesState.appTheme
        val options = AppTheme.values
        BasicListPreference(
            title = stringResource(R.string.app_theme_title),
            options = options,
            enabled = enabled,
            selected = selectedTheme,
            onPreferenceClicked = { thisAppTheme ->
                settingsViewModel.setPreference(AppPreference.AppThemeKey, thisAppTheme)
            }
        )
    }

    @Composable
    fun DynamicColors() {
        SwitchPreference(
            title = stringResource(R.string.dynamic_colors_title),
            preference = AppPreference.DynamicColorKey
        )
    }

    @Composable
    fun PureUvcOnlyModePreference() {
        SwitchPreference(
            title = stringResource(R.string.disable_gadget_functions_during_config),
            summary = stringResource(R.string.disable_gadget_functions_during_config_summary),
            preference = AppPreference.DisableGadgetFunctionsDuringConfiguration
        )
    }

    @Composable
    fun BoostUsbIrqPreference() {
        SwitchPreference(
            title = stringResource(R.string.boost_usb_irq_title),
            summary = stringResource(R.string.boost_usb_irq_summary),
            preference = AppPreference.BoostUsbIrqPriority
        )
    }

    @Composable
    fun UvcMaxPacketPreference(
        enabled: Boolean = true,
        settingsViewModel: SettingsViewModel = viewModel()
    ) {
        val preferencesState by settingsViewModel.userPreferencesFlow.collectAsState()
        val selected = preferencesState.uvcMaxPacketOption
        val options = UvcMaxPacketOption.values
        BasicListPreference(
            title = stringResource(R.string.uvc_maxpacket_title),
            options = options,
            enabled = enabled,
            selected = selected,
            onPreferenceClicked = { opt ->
                settingsViewModel.setPreference(AppPreference.UvcMaxPacketSizePref, opt)
            }
        )
    }

    @Composable
    fun UvcMaxBurstPreference(
        enabled: Boolean = true,
        settingsViewModel: SettingsViewModel = viewModel()
    ) {
        val preferencesState by settingsViewModel.userPreferencesFlow.collectAsState()
        val selected = preferencesState.uvcMaxBurstOption
        val options = UvcMaxBurstOption.values
        BasicListPreference(
            title = stringResource(R.string.uvc_maxburst_title),
            options = options,
            enabled = enabled,
            selected = selected,
            onPreferenceClicked = { opt ->
                settingsViewModel.setPreference(AppPreference.UvcMaxBurstPref, opt)
            }
        )
    }

    @Composable
    fun ExperimentalMode() {
        SwitchPreference(
            title = stringResource(R.string.experimental_mode_title),
            summary = stringResource(R.string.experimental_mode_summary),
            preference = AppPreference.ExperimentalMode
        )
    }

    @Composable
    fun UvcResolutionProfilePreference(
        enabled: Boolean = true,
        settingsViewModel: SettingsViewModel = viewModel()
    ) {
        val preferencesState by settingsViewModel.userPreferencesFlow.collectAsState()
        val selected = preferencesState.uvcResolutionProfileOption
        val options = UvcResolutionProfileOption.values
        BasicListPreference(
            title = stringResource(R.string.uvc_resolution_profile_title),
            options = options,
            enabled = enabled,
            selected = selected,
            onPreferenceClicked = { opt ->
                settingsViewModel.setPreference(AppPreference.UvcResolutionProfilePref, opt)
            }
        )
    }

    @Composable
    fun UvcExposedFormatsPreference(
        enabled: Boolean = true,
        settingsViewModel: SettingsViewModel = viewModel()
    ) {
        val preferencesState by settingsViewModel.userPreferencesFlow.collectAsState()
        val selected = preferencesState.uvcExposedFormatsOption
        val options = UvcExposedFormatsOption.values
        BasicListPreference(
            title = stringResource(R.string.uvc_exposed_formats_title),
            options = options,
            enabled = enabled,
            selected = selected,
            onPreferenceClicked = { opt ->
                settingsViewModel.setPreference(AppPreference.UvcExposedFormatsPref, opt)
            }
        )
    }

    @Composable
    fun CustomUsbProductNamePreference(
        enabled: Boolean = true,
        settingsViewModel: SettingsViewModel = viewModel()
    ) {
        TextDialogPreference(
            title = stringResource(R.string.custom_usb_product_title),
            summary = stringResource(R.string.custom_usb_product_summary),
            enabled = enabled,
            preference = AppPreference.CustomUsbProductNamePref,
            property = UserPreferences::customUsbProductName,
            settingsViewModel = settingsViewModel
        )
    }

    @Composable
    fun CustomUsbManufacturerNamePreference(
        enabled: Boolean = true,
        settingsViewModel: SettingsViewModel = viewModel()
    ) {
        TextDialogPreference(
            title = stringResource(R.string.custom_usb_mfg_title),
            summary = stringResource(R.string.custom_usb_mfg_summary),
            enabled = enabled,
            preference = AppPreference.CustomUsbManufacturerNamePref,
            property = UserPreferences::customUsbManufacturerName,
            settingsViewModel = settingsViewModel
        )
    }

    @Composable
    fun UsbGadgetPath() {
        TextDialogPreference(
            title = stringResource(R.string.usb_gadget_path_title),
            preference = AppPreference.UsbGadgetPathPref,
            property = UserPreferences::usbGadgetPath
        )
    }

    @Composable
    fun UvcVideoDevicePath() {
        TextDialogPreference(
            title = stringResource(R.string.uvc_video_device_path_title),
            preference = AppPreference.UvcVideoDevicePathPref,
            property = UserPreferences::uvcVideoDevicePath
        )
    }
}

@DarkLightModePreviews
@Composable
private fun SettingsScreenPreview() {
    Navigator(SettingsScreen())
}
