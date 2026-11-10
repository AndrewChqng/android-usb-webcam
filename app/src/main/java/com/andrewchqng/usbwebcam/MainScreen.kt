package com.andrewchqng.usbwebcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.andrewchqng.usbwebcam.settings.SettingsScreen
import com.andrewchqng.usbwebcam.shell_utils.RootStateHolder
import com.andrewchqng.usbwebcam.troubleshooting.TroubleshootingScreen
import com.andrewchqng.usbwebcam.ui.standalone_screens.HelpScreen
import com.andrewchqng.usbwebcam.ui.standalone_screens.InfoScreen
import com.andrewchqng.usbwebcam.ui.theme.PaddingNormal
import com.andrewchqng.usbwebcam.ui.utils.BasicPage
import com.andrewchqng.usbwebcam.ui.utils.BasicTopBar
import com.andrewchqng.usbwebcam.ui.utils.DarkLightModePreviews
import java.util.Locale

class MainScreen : Screen {
    @Composable
    override fun Content() {
        MainPage()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainPage(
    mainViewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val rootStateHolder = RootStateHolder.getInstance()
    val rootState by rootStateHolder.uiState.collectAsState()
    val uiState by mainViewModel.uiState.collectAsState()
    val webcamState by mainViewModel.webcamState.collectAsState()
    val webServerState by mainViewModel.webServerState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        if (grantedMap[Manifest.permission.CAMERA] == true) {
            mainViewModel.startUsbWebcam()
        }
    }

    val padding = PaddingNormal
    BasicPage(
        snackbarHostState = snackbarHostState,
        topBar = { MainTopBar() },
        padding = PaddingValues(start = padding, end = padding, bottom = padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        scrollable = true
    ) {
        // Status & Primary Action Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (webcamState.isHostStreaming) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
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
                    val mbps = (webcamState.measuredFps * webcamState.avgFrameSizeKb * 8f) / 1024f
                    val totalBytesSec = webcamState.measuredFps * webcamState.avgFrameSizeKb * 1024f
                    val isocLimit = if (webcamState.boundMaxPacket >= 3072) 24_576_000f else 8_192_000f
                    val usbPct = ((totalBytesSec / isocLimit) * 100f).coerceIn(0f, 100f)
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
                        mbps,
                        usbPct,
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
                    onClick = {
                        if (webcamState.isUvcLoopRunning) {
                            mainViewModel.stopUsbWebcam()
                        } else {
                            val hasCamPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasCamPerm) {
                                mainViewModel.startUsbWebcam()
                            } else {
                                val perms = buildList {
                                    add(Manifest.permission.CAMERA)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }.toTypedArray()
                                permissionsLauncher.launch(perms)
                            }
                        }
                    },
                    enabled = !uiState.isBusyConfiguringGadget,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (webcamState.isUvcLoopRunning) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(
                        when {
                            uiState.isBusyConfiguringGadget -> "Configuring USB Gadget..."
                            webcamState.isUvcLoopRunning -> "Stop USB Webcam"
                            else -> "Start USB Webcam"
                        }
                    )
                }
            }
        }

        // PC Web Remote Control & ADB Port Forwarding Card
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
                        onCheckedChange = { mainViewModel.setWebServerEnabled(it) }
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
                                    clipboardManager.setText(AnnotatedString(adbCmd))
                                    coroutineScope.launch {
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
                                    clipboardManager.setText(AnnotatedString(pcUrl))
                                    coroutineScope.launch {
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

        // Camera Lens Selection Card
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
                    for (cam in webcamState.availableCameras) {
                        FilterChip(
                            selected = webcamState.selectedCameraId == cam.cameraId,
                            onClick = { mainViewModel.selectCamera(cam.cameraId) },
                            label = { Text(cam.label) }
                        )
                    }
                }
            }
        }

        // Orientation, Mirror & MJPEG Quality Card
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
                            onClick = { mainViewModel.setRotation(deg) },
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
                        onCheckedChange = { mainViewModel.setMirrorHorizontal(it) }
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
                    onValueChange = { mainViewModel.setJpegQuality(it.toInt()) },
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
                        15 to "15 FPS",
                        10 to "10 FPS (Low)",
                        5 to "5 FPS (Ultra-Low)"
                    )
                    for ((cap, label) in fpsOptions) {
                        FilterChip(
                            selected = webcamState.targetFpsCap == cap,
                            onClick = { mainViewModel.setTargetFpsCap(cap) },
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
                            onClick = { mainViewModel.setMaxInFlightBuffers(bufs) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        // Camera2 ISP, Focus, Zoom & Exposure Card
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Continuous Autofocus", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Turn off to lock focus at infinity/hyperfocal and prevent focus breathing.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = webcamState.autoFocusEnabled,
                        onCheckedChange = { mainViewModel.setAutoFocusEnabled(it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Lock Constant Framerate (AE Priority)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Keeps shutter speed fast so indoor lighting never drops FPS below target.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = webcamState.lockConstantFps,
                        onCheckedChange = { mainViewModel.setLockConstantFps(it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Video Stabilization (EIS)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Keep off for minimum frame latency on a tripod/desk mount.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = webcamState.videoStabilization,
                        onCheckedChange = { mainViewModel.setVideoStabilization(it) }
                    )
                }

                Text(
                    text = String.format(Locale.US, "Digital Zoom: %.1fx", webcamState.zoomRatio),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = webcamState.zoomRatio,
                    onValueChange = { mainViewModel.setZoomRatio(it) },
                    valueRange = 1.0f..webcamState.maxZoom.coerceAtLeast(4.0f)
                )

                if (webcamState.zoomRatio > 1.05f) {
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

                        // X axis (Horizontal Pan)
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

                        // Y axis (Vertical Tilt)
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
                        "Camera2 X/Y pan & tilt controls activate when zoomed in (> 1.0x).",
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Lock Exposure (AE Lock)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Freezes exposure level to prevent lighting shifts while presenting.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = webcamState.aeLock,
                        onCheckedChange = { mainViewModel.setAeLock(it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Studio ISP Quality", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "High-quality studio noise reduction and edge enhancement (Fast low-latency when off).",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = webcamState.studioQualityProcessing,
                        onCheckedChange = { mainViewModel.setStudioQualityProcessing(it) }
                    )
                }

                val currentLens = webcamState.availableCameras.firstOrNull { it.cameraId == webcamState.selectedCameraId }
                if (currentLens?.hasFlash == true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("LED Video Light", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Use device LED flash as desk fill light in dim rooms.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = webcamState.torchEnabled,
                            onCheckedChange = { mainViewModel.setTorchEnabled(it) }
                        )
                    }
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Lock White Balance (AWB Lock)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Freezes color temperature to prevent skin tone color shifts.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = webcamState.awbLock,
                        onCheckedChange = { mainViewModel.setAwbLock(it) }
                    )
                }

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

        // Toggleable Viewfinder Preview Card (Off by default for maximum battery/CPU efficiency)
        Card(modifier = Modifier.fillMaxWidth()) {
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Local Viewfinder Preview",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Keep disabled during normal use to save GPU/display power; streaming continues with screen off.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = webcamState.isPreviewEnabled,
                        onCheckedChange = { mainViewModel.setPreviewEnabled(it) }
                    )
                }

                if (webcamState.isPreviewEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            mainViewModel.attachPreviewSurface(holder.surface)
                                        }

                                        override fun surfaceChanged(
                                            holder: SurfaceHolder,
                                            format: Int,
                                            width: Int,
                                            height: Int
                                        ) {
                                            mainViewModel.attachPreviewSurface(holder.surface)
                                        }

                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            mainViewModel.attachPreviewSurface(null)
                                        }
                                    })
                                }
                            }
                        )
                    }

                    DisposableEffect(Unit) {
                        onDispose {
                            mainViewModel.attachPreviewSurface(null)
                        }
                    }
                }
            }
        }

        LaunchedEffect(rootState.missingRootPrivileges) {
            if (rootState.missingRootPrivileges) {
                snackbarHostState.showSnackbar(
                    message = "Missing root permissions — grant root in Magisk / KernelSU / APatch",
                    duration = SnackbarDuration.Long
                )
            }
        }
    }
}

private typealias MenuItem = Pair<Screen, String>

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar() {
    val navigator = LocalNavigator.currentOrThrow
    var showDropdownMenu by remember { mutableStateOf(false) }

    BasicTopBar(
        title = stringResource(R.string.app_name),
        actions = {
            IconButton(onClick = { showDropdownMenu = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Overflow Menu",
                )
                DropdownMenu(
                    expanded = showDropdownMenu,
                    onDismissRequest = { showDropdownMenu = false }
                ) {
                    val menuItems = arrayOf(
                        MenuItem(SettingsScreen(), stringResource(R.string.settings)),
                        MenuItem(TroubleshootingScreen(), stringResource(R.string.troubleshooting_title)),
                        MenuItem(HelpScreen(), stringResource(R.string.help)),
                        MenuItem(InfoScreen(), stringResource(R.string.info))
                    )
                    for (item in menuItems) {
                        DropdownMenuItem(
                            text = { Text(item.second) },
                            onClick = {
                                val thisScreen = item.first
                                for (screen in navigator.items.reversed()) {
                                    if (screen::class == thisScreen::class) {
                                        return@DropdownMenuItem
                                    }
                                }
                                navigator.push(thisScreen)
                                showDropdownMenu = false
                            }
                        )
                    }
                }
            }
        }
    )
}

@DarkLightModePreviews
@Composable
private fun MainScreenPreview() {
    Navigator(MainScreen())
}
