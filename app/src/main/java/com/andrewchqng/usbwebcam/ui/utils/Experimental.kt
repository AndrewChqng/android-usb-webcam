package com.andrewchqng.usbwebcam.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andrewchqng.usbwebcam.settings.SettingsViewModel

@Composable
fun Experimental(
    content: @Composable () -> Unit,
) {
    // TODO: maybe add red tint or something to differentiate experimental stuff?
    if (isExperimentalModeEnabled()) {
        content()
    }
}

@Composable
fun isExperimentalModeEnabled(settingsViewModel: SettingsViewModel = viewModel()): Boolean {
    val userPreferences by settingsViewModel.userPreferencesFlow.collectAsState()

    return userPreferences.isExperimentalModeEnabled
}
