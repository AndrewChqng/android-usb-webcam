package com.andrewchqng.usbwebcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.andrewchqng.usbwebcam.settings.ResolutionManagerDialog
import com.andrewchqng.usbwebcam.settings.SettingsScreen
import com.andrewchqng.usbwebcam.shell_utils.RootStateHolder
import com.andrewchqng.usbwebcam.troubleshooting.TroubleshootingScreen
import com.andrewchqng.usbwebcam.ui.main.CameraIspPtzCard
import com.andrewchqng.usbwebcam.ui.main.CameraLensCard
import com.andrewchqng.usbwebcam.ui.main.OrientationEncodingCard
import com.andrewchqng.usbwebcam.ui.main.ResolutionProfileCard
import com.andrewchqng.usbwebcam.ui.main.StatusActionCard
import com.andrewchqng.usbwebcam.ui.main.ViewfinderPreviewCard
import com.andrewchqng.usbwebcam.ui.main.WebRemoteControlCard
import com.andrewchqng.usbwebcam.ui.standalone_screens.HelpScreen
import com.andrewchqng.usbwebcam.ui.standalone_screens.InfoScreen
import com.andrewchqng.usbwebcam.ui.theme.PaddingNormal
import com.andrewchqng.usbwebcam.ui.utils.BasicPage
import com.andrewchqng.usbwebcam.ui.utils.BasicTopBar
import com.andrewchqng.usbwebcam.ui.utils.DarkLightModePreviews

class MainScreen : Screen {
    @Composable
    override fun Content() {
        MainPage()
    }
}

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
    val userPreferences by mainViewModel.userPreferencesStateFlow.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showResolutionDialog by remember { mutableStateOf(false) }

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
        StatusActionCard(
            webcamState = webcamState,
            isBusyConfiguringGadget = uiState.isBusyConfiguringGadget,
            onToggleWebcam = {
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
            }
        )

        if (showResolutionDialog) {
            val discoveredResolutions = remember { mainViewModel.getDiscoveredResolutions() }
            ResolutionManagerDialog(
                availableResolutions = discoveredResolutions,
                selectedResolutions = userPreferences.selectedResolutions,
                defaultResolution = userPreferences.defaultResolution,
                customResolutions = userPreferences.customResolutions,
                currentProfile = userPreferences.uvcResolutionProfileOption,
                onSelectProfile = { mainViewModel.setResolutionProfile(it) },
                onToggleResolution = { key, sel -> mainViewModel.toggleResolution(key, sel) },
                onSetDefaultResolution = { key -> mainViewModel.setDefaultResolution(key) },
                onAddCustomResolution = { w, h -> mainViewModel.addCustomResolution(w, h) },
                onRemoveCustomResolution = { key -> mainViewModel.removeCustomResolution(key) },
                onSelectAll = { keys -> mainViewModel.selectAllResolutions(keys) },
                onDeselectAll = { keys -> mainViewModel.deselectAllResolutions(keys) },
                onDismissRequest = { showResolutionDialog = false }
            )
        }

        ResolutionProfileCard(
            userPreferences = userPreferences,
            isUvcLoopRunning = webcamState.isUvcLoopRunning,
            onSelectProfile = { mainViewModel.setResolutionProfile(it) },
            onOpenCustomDialog = { showResolutionDialog = true }
        )

        CameraLensCard(
            availableCameras = webcamState.availableCameras,
            selectedCameraId = webcamState.selectedCameraId,
            onSelectCamera = { mainViewModel.selectCamera(it) }
        )

        OrientationEncodingCard(
            webcamState = webcamState,
            onSetRotation = { mainViewModel.setRotation(it) },
            onSetMirrorHorizontal = { mainViewModel.setMirrorHorizontal(it) },
            onSetJpegQuality = { mainViewModel.setJpegQuality(it) },
            onSetTargetFpsCap = { mainViewModel.setTargetFpsCap(it) },
            onSetMaxInFlightBuffers = { mainViewModel.setMaxInFlightBuffers(it) }
        )

        CameraIspPtzCard(
            webcamState = webcamState,
            mainViewModel = mainViewModel
        )

        ViewfinderPreviewCard(
            isPreviewEnabled = webcamState.isPreviewEnabled,
            onSetPreviewEnabled = { mainViewModel.setPreviewEnabled(it) },
            onAttachPreviewSurface = { mainViewModel.attachPreviewSurface(it) }
        )

        WebRemoteControlCard(
            webServerState = webServerState,
            snackbarHostState = snackbarHostState,
            coroutineScope = coroutineScope,
            onSetWebServerEnabled = { mainViewModel.setWebServerEnabled(it) }
        )

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
