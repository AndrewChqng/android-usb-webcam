package com.andrewchqng.usbwebcam.settings

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.lifecycle.AndroidViewModel

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val userPreferencesRepository = UserPreferencesRepository.getInstance(application)
    val userPreferencesFlow = userPreferencesRepository.userPreferencesFlow

    fun <T> getPreference(key: PreferenceKey<T>): T =
        userPreferencesRepository.getPreference(key)

    fun <T> setPreference(key: PreferenceKey<T>, value: T) =
        userPreferencesRepository.setPreference(key, value)

    fun <T> resetPreferenceToDefault(key: PreferenceKey<T>) =
        userPreferencesRepository.resetPreferenceToDefault(key)

    fun selectResolutionProfile(profile: UvcResolutionProfileOption) =
        userPreferencesRepository.selectResolutionProfile(profile)

    fun toggleResolution(resKey: String, isSelected: Boolean) =
        userPreferencesRepository.toggleResolution(resKey, isSelected)

    fun setDefaultResolution(resKey: String) =
        userPreferencesRepository.setDefaultResolution(resKey)

    fun addCustomResolution(width: Int, height: Int): Boolean =
        userPreferencesRepository.addCustomResolution(width, height)

    fun removeCustomResolution(key: String) =
        userPreferencesRepository.removeCustomResolution(key)

    fun getDiscoveredResolutions(): List<ResolutionItem> {
        val app = getApplication<Application>()
        val camManager = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val discovered = if (camManager != null) {
            ResolutionItem.discoverSensorResolutions(camManager)
        } else {
            ResolutionItem.BASELINE_RESOLUTIONS
        }.toMutableSet()

        val customKeys = userPreferencesFlow.value.customResolutions
        for (k in customKeys) {
            ResolutionItem.fromKey(k, isCustom = true)?.let { discovered.add(it) }
        }

        return discovered.sorted()
    }
}
