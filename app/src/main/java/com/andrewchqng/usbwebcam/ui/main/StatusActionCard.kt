package com.andrewchqng.usbwebcam.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andrewchqng.usbwebcam.webcam.Camera2ConfigHelper
import com.andrewchqng.usbwebcam.webcam.WebcamStreamState
import java.util.Locale

@Composable
fun StatusActionCard(
    webcamState: WebcamStreamState,
    isBusyConfiguringGadget: Boolean,
    onToggleWebcam: () -> Unit
) {
    val busMetrics = Camera2ConfigHelper.calculateUsbBusMetrics(
        measuredFps = webcamState.measuredFps,
        avgFrameSizeKb = webcamState.avgFrameSizeKb,
        boundMaxPacket = webcamState.boundMaxPacket,
        isHostStreaming = webcamState.isHostStreaming
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                busMetrics.isSaturated -> MaterialTheme.colorScheme.errorContainer
                webcamState.isHostStreaming -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = when {
                busMetrics.isSaturated -> MaterialTheme.colorScheme.onErrorContainer
                webcamState.isHostStreaming -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (webcamState.isUvcLoopRunning) "UVC Gadget Ready" else "UVC Gadget Inactive",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = webcamState.videoNodePath ?: "No /dev/videoX",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            val streamSummary = if (webcamState.isHostStreaming) {
                String.format(
                    Locale.US,
                    "Streaming %s %dx%d @ %dfps\nActual: %.1f fps (%.1f ms/frame, %.1f KB)\nBandwidth: %.2f Mbps (%.0f%% USB bus utilization, %dB pkt)",
                    webcamState.negotiatedFormat,
                    webcamState.negotiatedWidth,
                    webcamState.negotiatedHeight,
                    webcamState.negotiatedFps,
                    webcamState.measuredFps,
                    webcamState.avgEncodeTimeMs,
                    webcamState.avgFrameSizeKb,
                    busMetrics.mbps,
                    busMetrics.utilizationPct,
                    webcamState.boundMaxPacket
                )
            } else if (webcamState.isUvcLoopRunning) {
                "Waiting for USB host application to open webcam... (USB pkt: ${webcamState.boundMaxPacket}B)"
            } else {
                "Press Start to expose USB Video Class (UVC) webcam to host PC."
            }

            Text(
                text = streamSummary,
                style = MaterialTheme.typography.bodyMedium
            )

            webcamState.lastError?.let { err ->
                Text(
                    text = "Error: $err",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = onToggleWebcam,
                enabled = !isBusyConfiguringGadget,
                modifier = Modifier.fillMaxWidth(),
                colors = if (webcamState.isUvcLoopRunning) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(
                    when {
                        isBusyConfiguringGadget -> "Configuring USB Gadget..."
                        webcamState.isUvcLoopRunning -> "Stop USB Webcam"
                        else -> "Start USB Webcam"
                    }
                )
            }
        }
    }
}
