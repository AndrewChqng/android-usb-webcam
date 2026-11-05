package com.andrewchqng.usbwebcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
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
                    String.format(
                        Locale.US,
                        "Streaming %s %dx%d @ %dfps • %.1f fps actual (%.1f ms/frame, %.1f KB, %dB pkt)",
                        webcamState.negotiatedFormat,
                        webcamState.negotiatedWidth,
                        webcamState.negotiatedHeight,
                        webcamState.negotiatedFps,
                        webcamState.measuredFps,
                        webcamState.avgEncodeTimeMs,
                        webcamState.avgFrameSizeKb,
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

                Text(
                    text = "MJPEG Quality: ${webcamState.jpegQuality}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = webcamState.jpegQuality.toFloat(),
                    onValueChange = { mainViewModel.setJpegQuality(it.toInt()) },
                    valueRange = 50f..95f
                )

                Text(
                    text = "Target FPS Cap",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val fpsOptions = listOf(0 to "Auto (Host)", 60 to "60 FPS", 30 to "30 FPS", 15 to "15 FPS")
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
                    valueRange = 1.0f..4.0f
                )

                Text(
                    text = String.format(Locale.US, "Exposure Compensation: %+.1f EV", webcamState.exposureEv),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = webcamState.exposureEv,
                    onValueChange = { mainViewModel.setExposureEv(it) },
                    valueRange = -2.0f..2.0f
                )
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
