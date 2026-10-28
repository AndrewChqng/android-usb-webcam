package me.arianb.usb_hid_client.settings

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
import me.arianb.usb_hid_client.R
import me.arianb.usb_hid_client.settings.AppSettings.AppThemePreference
import me.arianb.usb_hid_client.settings.AppSettings.DynamicColors
import me.arianb.usb_hid_client.settings.AppSettings.ExperimentalMode
import me.arianb.usb_hid_client.settings.AppSettings.PreferenceCategory
import me.arianb.usb_hid_client.settings.AppSettings.PureUvcOnlyModePreference
import me.arianb.usb_hid_client.settings.AppSettings.UsbGadgetPath
import me.arianb.usb_hid_client.settings.AppSettings.UvcMaxPacketPreference
import me.arianb.usb_hid_client.settings.AppSettings.UvcVideoDevicePath
import me.arianb.usb_hid_client.ui.theme.PaddingNormal
import me.arianb.usb_hid_client.ui.theme.isDynamicColorAvailable
import me.arianb.usb_hid_client.ui.utils.BasicPage
import me.arianb.usb_hid_client.ui.utils.DarkLightModePreviews
import me.arianb.usb_hid_client.ui.utils.SimpleNavTopBar

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
            PureUvcOnlyModePreference()
            UvcMaxPacketPreference()
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
    fun ExperimentalMode() {
        SwitchPreference(
            title = stringResource(R.string.experimental_mode_title),
            summary = stringResource(R.string.experimental_mode_summary),
            preference = AppPreference.ExperimentalMode
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
