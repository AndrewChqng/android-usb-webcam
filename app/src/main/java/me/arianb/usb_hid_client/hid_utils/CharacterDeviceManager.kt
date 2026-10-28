package me.arianb.usb_hid_client.hid_utils

import android.app.Application
import android.content.Intent
import android.os.Parcelable
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.parcelize.Parcelize
import me.arianb.usb_hid_client.settings.GadgetUserPreferences
import me.arianb.usb_hid_client.shell_utils.RootStateHolder
import timber.log.Timber
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class CharacterDeviceManager private constructor(private val application: Application) {
    private val rootStateHolder = RootStateHolder.getInstance()
    private val mConnection = UsbGadgetServiceConnection()

    @Throws(TimeoutCancellationException::class)
    private suspend fun ensureServiceIsBound(
        timeout: Duration = 5000.milliseconds,
        pollInterval: Duration = 250.milliseconds
    ) {
        if (!mConnection.isBound) {
            Intent(application, UsbGadgetService::class.java).also { intent ->
                RootService.bind(intent, mConnection)
            }
        }

        withTimeout(timeout) {
            while (!mConnection.isBound) {
                Timber.d("not bound yet, sleeping for a bit before trying again...")
                delay(pollInterval)
            }
            Timber.d("UsbGadgetService is bound now!")
        }
    }

    private suspend fun useService(block: suspend (UsbGadgetServiceConnection) -> Unit) {
        try {
            ensureServiceIsBound()
        } catch (e: TimeoutCancellationException) {
            Timber.e("Failed to bind service within timeout duration: $e")
            return
        }

        block(mConnection)

        if (mConnection.isBound) {
            RootService.unbind(mConnection)
        }
    }

    suspend fun createCharacterDevices(gadgetUserPreferences: GadgetUserPreferences): String? {
        useService {
            it.createGadget(gadgetUserPreferences)
        }

        return withContext(Dispatchers.IO) {
            fixSelinuxPermissions()

            var detectedNode: String? = null
            try {
                withTimeout(4000) {
                    while (detectedNode == null) {
                        detectedNode = discoverUvcVideoDevicePath(gadgetUserPreferences.uvcVideoDevicePath.path)
                        if (detectedNode == null) {
                            delay(200)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Timber.e("Timed out waiting for UVC /dev/videoX character device to appear.")
            }

            val finalNode = detectedNode ?: gadgetUserPreferences.uvcVideoDevicePath.path.ifBlank { "/dev/video2" }
            DevicePaths.setUvcDevicePath(UvcVideoDevicePath(finalNode))
            if (File(finalNode).exists() || Shell.cmd("test -e $finalNode").exec().isSuccess) {
                fixCharacterDevicePermissions(finalNode)
                finalNode
            } else {
                null
            }
        }
    }

    /**
     * Scans /sys/class/video4linux/video* to find the V4L2 video output node created by the USB UVC gadget.
     */
    fun discoverUvcVideoDevicePath(preferredOverride: String = ""): String? {
        if (preferredOverride.isNotBlank() && preferredOverride != AUTO_DETECT_VIDEO_PATH) {
            if (Shell.cmd("test -e $preferredOverride").exec().isSuccess) {
                return preferredOverride
            }
        }

        // Find any video4linux node whose sysfs symlink points to usb_gadget / dwc3 / gadget
        val findCmd = """
            for v in /sys/class/video4linux/video*; do
                [ -e "${'$'}v" ] || continue
                link_target=${'$'}(readlink -f "${'$'}v" 2>/dev/null)
                vname=${'$'}(cat "${'$'}v/name" 2>/dev/null)
                case "${'$'}link_target ${'$'}vname" in
                    *gadget*|*dwc3*|*udc*|*uvc*)
                        echo "/dev/${'$'}(basename "${'$'}v")"
                        exit 0
                        ;;
                esac
            done
        """.trimIndent()

        val result = Shell.cmd(findCmd).exec()
        val found = result.out.firstOrNull { it.startsWith("/dev/video") }?.trim()
        if (!found.isNullOrEmpty()) {
            Timber.i("Discovered UVC V4L2 node: $found")
            DevicePaths.setUvcDevicePath(UvcVideoDevicePath(found))
            return found
        }
        return null
    }

    private fun fixSelinuxPermissions() {
        val sepolicyCmd = rootStateHolder.sepolicyCommand ?: return
        for (policy in SELINUX_POLICIES) {
            Shell.cmd("$sepolicyCmd '$policy'").exec()
        }
    }

    fun fixCharacterDevicePermissions(device: DevicePath) = fixCharacterDevicePermissions(device.path)

    fun fixCharacterDevicePermissions(device: String) {
        fixSelinuxPermissions()

        val appUID: Int = application.applicationInfo.uid
        Shell.cmd(
            "chown '${appUID}:${appUID}' $device",
            "chmod 660 $device",
            "chcon 'u:object_r:device:s0:${getSelinuxCategories()}' $device"
        ).exec()
    }

    private fun getSelinuxCategories(): String {
        val appDataDirPath: String = application.applicationInfo.dataDir
        val commandResult = Shell.cmd("stat -c %C $appDataDirPath").exec()
        val selinuxContextString = commandResult.out.joinToString(separator = "\n").trim()
        val categories = selinuxContextString.substringAfterLast(':')
        if (categories == selinuxContextString) {
            Timber.wtf("Failed to get app's selinux context")
        }
        return categories
    }

    suspend fun deleteCharacterDevices(gadgetUserPreferences: GadgetUserPreferences) {
        useService {
            it.deleteGadget(gadgetUserPreferences)
        }
    }

    @ModifiesStateDirectly
    fun characterDeviceMissing(charDevicePath: DevicePath): Boolean {
        return !charDevicePath.exists()
    }

    @ModifiesStateDirectly
    fun anyCharacterDeviceMissing(): Boolean {
        val detected = discoverUvcVideoDevicePath()
        if (detected != null) {
            return false
        }
        return !DevicePaths.uvcVideoDevice.value.exists()
    }

    companion object {
        const val AUTO_DETECT_VIDEO_PATH = "auto"

        object DevicePaths {
            val DEFAULT_UVC_DEVICE_PATH = UvcVideoDevicePath(AUTO_DETECT_VIDEO_PATH)

            private val _uvcVideoDevice = MutableStateFlow(UvcVideoDevicePath("/dev/video2"))
            val uvcVideoDevice: StateFlow<UvcVideoDevicePath> = _uvcVideoDevice

            fun setUvcDevicePath(path: UvcVideoDevicePath) {
                _uvcVideoDevice.value = path
            }

            val all: List<DevicePath>
                get() = listOf(uvcVideoDevice.value)
        }

        // Allow app domain to open, read, write, ioctl, and mmap the V4L2 UVC character device
        private val SELINUX_POLICIES = listOf(
            "allow appdomain device chr_file { getattr open read write ioctl map }",
            "allow appdomain video_device chr_file { getattr open read write ioctl map }",
            "allow untrusted_app device chr_file { getattr open read write ioctl map }",
            "allow untrusted_app video_device chr_file { getattr open read write ioctl map }",
            "allow untrusted_app_all device chr_file { getattr open read write ioctl map }",
            "allow untrusted_app_all video_device chr_file { getattr open read write ioctl map }"
        )

        @Volatile
        private var INSTANCE: CharacterDeviceManager? = null
        fun getInstance(application: Application): CharacterDeviceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CharacterDeviceManager(application).also { INSTANCE = it }
            }
        }
    }
}

@Retention(value = AnnotationRetention.BINARY)
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Ensure to update state when calling this method. You will usually want to be calling the ViewModel wrappers instead"
)
annotation class ModifiesStateDirectly

interface DevicePath {
    val path: String

    fun exists(): Boolean = path != CharacterDeviceManager.AUTO_DETECT_VIDEO_PATH && File(path).exists()
}

@JvmInline
@Parcelize
value class UvcVideoDevicePath(override val path: String) : DevicePath, Parcelable
