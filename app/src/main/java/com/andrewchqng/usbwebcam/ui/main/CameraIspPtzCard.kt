package com.andrewchqng.usbwebcam.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andrewchqng.usbwebcam.MainViewModel
import com.andrewchqng.usbwebcam.webcam.WebcamStreamState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun IspSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraIspPtzCard(
    webcamState: WebcamStreamState,
    mainViewModel: MainViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Camera2 ISP, Focus & Exposure",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            IspSwitchRow(
                title = "Continuous Autofocus",
                subtitle = "Turn off to lock focus at infinity/hyperfocal and prevent focus breathing.",
                checked = webcamState.autoFocusEnabled,
                onCheckedChange = { mainViewModel.setAutoFocusEnabled(it) }
            )

            IspSwitchRow(
                title = "Lock Constant Framerate (AE Priority)",
                subtitle = "Keeps shutter speed fast so indoor lighting never drops FPS below target.",
                checked = webcamState.lockConstantFps,
                onCheckedChange = { mainViewModel.setLockConstantFps(it) }
            )

            IspSwitchRow(
                title = "Video Stabilization (EIS)",
                subtitle = "Keep off for minimum frame latency on a tripod/desk mount.",
                checked = webcamState.videoStabilization,
                onCheckedChange = { mainViewModel.setVideoStabilization(it) }
            )

            IspSwitchRow(
                title = "Native Camera2 API PTZ",
                subtitle = if (webcamState.useNativeCamera2Ptz) {
                    "Uses Camera2 SCALER_CROP_REGION (hardware ISP crop; stays on NEON 1:1 path)."
                } else {
                    "Uses software ROI crop in native C++ (works on CENTER_ONLY sensors)."
                },
                checked = webcamState.useNativeCamera2Ptz,
                onCheckedChange = { mainViewModel.setUseNativeCamera2Ptz(it) }
            )

            Text(
                text = String.format(Locale.US, "Digital Zoom: %.1fx", webcamState.zoomRatio),
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = webcamState.zoomRatio,
                onValueChange = { mainViewModel.setZoomRatio(it) },
                valueRange = 1.0f..webcamState.maxZoom.coerceAtLeast(4.0f)
            )

            if (webcamState.useNativeCamera2Ptz || webcamState.zoomRatio > 1.05f) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Camera2 PTZ Crop Offset (X / Y)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (webcamState.panX != 0f || webcamState.panY != 0f) {
                            TextButton(
                                onClick = { mainViewModel.resetPan() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Recenter", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    if (webcamState.useNativeCamera2Ptz) {
                        if (!webcamState.activeCameraSupportsFreeformCrop) {
                            Text(
                                "Sensor reports CENTER_ONLY cropping (HAL may ignore off-center pan/tilt).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (webcamState.appliedCropRegionDesc.isNotEmpty()) {
                            Text(
                                "HAL Crop: ${webcamState.appliedCropRegionDesc}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val panXPercent = (webcamState.panX * 100f).roundToInt()
                    val xDesc = when {
                        panXPercent > 0 -> "Right +$panXPercent%"
                        panXPercent < 0 -> "Left $panXPercent%"
                        else -> "Center (0%)"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Crop X (Horizontal Pan)", style = MaterialTheme.typography.bodySmall)
                        Text(xDesc, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    Slider(
                        value = webcamState.panX,
                        onValueChange = { mainViewModel.setPanX(it) },
                        valueRange = -1.0f..1.0f
                    )

                    val panYPercent = (webcamState.panY * 100f).roundToInt()
                    val yDesc = when {
                        panYPercent > 0 -> "Down +$panYPercent%"
                        panYPercent < 0 -> "Up $panYPercent%"
                        else -> "Center (0%)"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Crop Y (Vertical Tilt)", style = MaterialTheme.typography.bodySmall)
                        Text(yDesc, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    Slider(
                        value = webcamState.panY,
                        onValueChange = { mainViewModel.setPanY(it) },
                        valueRange = -1.0f..1.0f
                    )
                }
            } else {
                Text(
                    "Software X/Y pan & tilt controls activate when zoomed in (> 1.0x).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = String.format(Locale.US, "Exposure Compensation: %+.1f EV", webcamState.exposureEv),
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = webcamState.exposureEv,
                onValueChange = { mainViewModel.setExposureEv(it) },
                valueRange = -2.0f..2.0f
            )

            IspSwitchRow(
                title = "Lock Exposure (AE Lock)",
                subtitle = "Freezes exposure level to prevent lighting shifts while presenting.",
                checked = webcamState.aeLock,
                onCheckedChange = { mainViewModel.setAeLock(it) }
            )

            IspSwitchRow(
                title = "Studio ISP Quality",
                subtitle = "High-quality studio noise reduction and edge enhancement (Fast low-latency when off).",
                checked = webcamState.studioQualityProcessing,
                onCheckedChange = { mainViewModel.setStudioQualityProcessing(it) }
            )

            val currentLens = webcamState.availableCameras.firstOrNull { it.cameraId == webcamState.selectedCameraId }
            if (currentLens?.hasFlash == true) {
                IspSwitchRow(
                    title = "LED Video Light",
                    subtitle = "Use device LED flash as desk fill light in dim rooms.",
                    checked = webcamState.torchEnabled,
                    onCheckedChange = { mainViewModel.setTorchEnabled(it) }
                )
            }

            Text(
                text = "White Balance (AWB)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val awbOptions = listOf(
                    1 to "Auto",
                    5 to "Daylight (5500K)",
                    6 to "Cloudy (6500K)",
                    2 to "Incandescent (2800K)",
                    3 to "Fluorescent (4000K)"
                )
                for ((mode, label) in awbOptions) {
                    FilterChip(
                        selected = webcamState.awbMode == mode,
                        onClick = { mainViewModel.setAwbMode(mode) },
                        label = { Text(label) }
                    )
                }
            }

            IspSwitchRow(
                title = "Lock White Balance (AWB Lock)",
                subtitle = "Freezes color temperature to prevent skin tone color shifts.",
                checked = webcamState.awbLock,
                onCheckedChange = { mainViewModel.setAwbLock(it) }
            )

            Text(
                text = "Anti-Flicker / Anti-Banding",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val abOptions = listOf(
                    3 to "Auto",
                    1 to "50 Hz (UK/EU/Asia)",
                    2 to "60 Hz (US/JP)",
                    0 to "Off"
                )
                for ((mode, label) in abOptions) {
                    FilterChip(
                        selected = webcamState.antiBandingMode == mode,
                        onClick = { mainViewModel.setAntiBandingMode(mode) },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            OutlinedButton(
                onClick = { mainViewModel.resetIspControls() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset Camera & ISP Controls")
            }
        }
    }
}
