package com.andrewchqng.usbwebcam.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andrewchqng.usbwebcam.webcam.CameraLensInfo
import com.andrewchqng.usbwebcam.webcam.WebcamStreamState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraLensCard(
    availableCameras: List<CameraLensInfo>,
    selectedCameraId: String,
    onSelectCamera: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Camera Lens",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (cam in availableCameras) {
                    FilterChip(
                        selected = selectedCameraId == cam.cameraId,
                        onClick = { onSelectCamera(cam.cameraId) },
                        label = { Text(cam.label) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrientationEncodingCard(
    webcamState: WebcamStreamState,
    onSetRotation: (Int) -> Unit,
    onSetMirrorHorizontal: (Boolean) -> Unit,
    onSetJpegQuality: (Int) -> Unit,
    onSetTargetFpsCap: (Int) -> Unit,
    onSetMaxInFlightBuffers: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Orientation & Encoding",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (deg in listOf(0, 90, 180, 270)) {
                    FilterChip(
                        selected = webcamState.rotationDegrees == deg,
                        onClick = { onSetRotation(deg) },
                        label = { Text("${deg}°") }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mirror Horizontally", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = webcamState.mirrorHorizontal,
                    onCheckedChange = onSetMirrorHorizontal
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MJPEG Compression Quality",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${webcamState.jpegQuality}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Slider(
                value = webcamState.jpegQuality.toFloat(),
                onValueChange = { onSetJpegQuality(it.toInt()) },
                valueRange = 1f..100f
            )

            if (webcamState.isHostStreaming && webcamState.negotiatedFormat.equals("YUYV", ignoreCase = true)) {
                Text(
                    text = "Active: Raw uncompressed YUYV (Kamoso/PipeWire). MJPEG quality slider applies to MJPEG streams (OBS, Windows Camera, Chrome, Meet).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "Target FPS Cap",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val fpsOptions = listOf(
                    0 to "Auto (Host)",
                    60 to "60 FPS",
                    30 to "30 FPS",
                    24 to "24 FPS",
                    15 to "15 FPS",
                    10 to "10 FPS (Low)",
                    5 to "5 FPS (Ultra-Low)"
                )
                for ((cap, label) in fpsOptions) {
                    FilterChip(
                        selected = webcamState.targetFpsCap == cap,
                        onClick = { onSetTargetFpsCap(cap) },
                        label = { Text(label) }
                    )
                }
            }

            Text(
                text = "V4L2 Ring Queue Mode (Anti-Tearing / Latency)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val queueModes = listOf(
                    1 to "Ultra-Low Latency (1 buf)",
                    2 to "Tear-Free Balanced (2 bufs)",
                    3 to "Max Smoothness (3 bufs)"
                )
                for ((bufs, label) in queueModes) {
                    FilterChip(
                        selected = webcamState.maxInFlightBuffers == bufs,
                        onClick = { onSetMaxInFlightBuffers(bufs) },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}
