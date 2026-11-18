package com.andrewchqng.usbwebcam.ui.main

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andrewchqng.usbwebcam.webserver.WebServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun WebRemoteControlCard(
    webServerState: WebServerState,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
    onSetWebServerEnabled: (Boolean) -> Unit
) {
    val clipboard = LocalClipboard.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (webServerState.isRunning) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PC Web Remote Control",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (webServerState.isRunning) {
                            "Active on port ${webServerState.port}"
                        } else {
                            "Server stopped"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (webServerState.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = webServerState.isRunning,
                    onCheckedChange = onSetWebServerEnabled
                )
            }

            Text(
                text = "Manage PTZ zoom, pan, tilt, exposure, focus, and stream settings from your PC browser without touching your phone.",
                style = MaterialTheme.typography.bodyMedium
            )

            if (webServerState.isRunning) {
                val adbCmd = "adb forward tcp:${webServerState.port} tcp:${webServerState.port}"
                val pcUrl = "http://localhost:${webServerState.port}"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "USB ADB Port Forwarding:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = adbCmd,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ADB Command", adbCmd)))
                                    snackbarHostState.showSnackbar(
                                        message = "Copied '$adbCmd' to clipboard",
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Copy", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PC Browser URL: $pcUrl",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Web Remote URL", pcUrl)))
                                    snackbarHostState.showSnackbar(
                                        message = "Copied '$pcUrl' to clipboard",
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Copy URL", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    val nonLocalUrls = webServerState.urls.filterNot { it.contains("localhost") }
                    if (nonLocalUrls.isNotEmpty()) {
                        Text(
                            text = "Wi-Fi LAN: ${nonLocalUrls.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                var showGuide by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "How to use ADB port forwarding",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = { showGuide = !showGuide },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(if (showGuide) "Hide Guide" else "Show Guide", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (showGuide) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "1. Connect your phone to your PC via USB cable with USB Debugging enabled.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "2. Open a terminal (PowerShell, Command Prompt, or bash) on your PC and run:\n    $adbCmd",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "3. Open $pcUrl in any browser on your PC (Chrome, Firefox, Edge, Safari).",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "💡 Tip: You can adjust zoom, pan/tilt, exposure, and focus directly with your PC mouse and keyboard while streaming in Zoom, Teams, Meet, or OBS!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
