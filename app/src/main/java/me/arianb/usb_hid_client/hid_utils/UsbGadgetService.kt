package me.arianb.usb_hid_client.hid_utils

import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcelable
import android.os.Process
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.parcelize.Parcelize
import me.arianb.usb_hid_client.BuildConfig
import me.arianb.usb_hid_client.getParcelableCompat
import me.arianb.usb_hid_client.settings.GadgetUserPreferences
import timber.log.Timber
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeLines
import kotlin.io.path.writeText
import kotlin.io.path.writer

class UsbGadgetService : RootService() {
    init {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Shell.enableVerboseLogging = true
        }
    }

    private val mMessenger: Messenger by lazy {
        Messenger(
            Handler(Looper.getMainLooper(), MessageHandler())
        )
    }

    internal class MessageHandler : Handler.Callback {
        override fun handleMessage(msg: Message): Boolean {
            Timber.d("Message received in UsbGadgetService, running with UID = ${Process.myUid()}")

            val gadgetUserPreferences: GadgetUserPreferences? = run {
                val bundle = msg.data.apply {
                    classLoader = GadgetUserPreferences::class.java.classLoader
                }
                bundle.getParcelableCompat(GADGET_PREF_BUNDLE_KEY)
            }
            if (gadgetUserPreferences == null) {
                Timber.e("Failed to unmarshal GadgetUserPreferences")
                return false
            }
            Timber.d("GadgetUserPreferences = $gadgetUserPreferences")
            try {
                val usbGadgetManager = UsbGadgetManager(gadgetUserPreferences)
                when (msg.what) {
                    MSG_CREATE -> usbGadgetManager.createCharacterDevices()
                    MSG_DELETE -> usbGadgetManager.deleteCharacterDevices()
                    else -> {
                        Timber.w("Unhandled message: $msg")
                        return false
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Unhandled exception occurred in UsbGadgetService")
            }

            return true
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Timber.d("UsbGadgetService onBind() called")
        return mMessenger.binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Timber.d("UsbGadgetService onUnbind() called")
        return super.onUnbind(intent)
    }

    companion object {
        const val GADGET_PREF_BUNDLE_KEY = "data"

        const val MSG_CREATE = 0
        const val MSG_DELETE = 1
    }
}

internal class UsbGadgetManager(val gadgetUserPreferences: GadgetUserPreferences) {
    private val CONFIG_FS_PATH: Path = Path("/config/usb_gadget")
    private val USB_GADGET_PATH: Path = determineGadgetPath()
    private val UDC_PATH: Path = USB_GADGET_PATH / "UDC"

    private val CONFIGS_PATH: Path = USB_GADGET_PATH / "configs/b.1"
    private val FUNCTIONS_PATH: Path = USB_GADGET_PATH / "functions"
    private val UVC_FUNCTION_PATH: Path = FUNCTIONS_PATH / UVC_FUNCTION_NAME
    private val BACKUP_STATE_FILE: Path = Path("/data/local/tmp/usb_webcam_saved_links.txt")

    private data class UvcFrameConfig(
        val dirName: String,
        val width: Int,
        val height: Int,
        val defaultInterval: Int,
        val intervals: List<Int>,
        val minBitRate: Long,
        val maxBitRate: Long
    ) {
        val maxFrameBufferSize: Int
            get() = width * height * 2
    }

    private val mjpegFrames = listOf(
        UvcFrameConfig(
            dirName = "360p",
            width = 640,
            height = 360,
            defaultInterval = 333333,
            intervals = listOf(166666, 333333, 666666),
            minBitRate = 18_432_000L,
            maxBitRate = 110_592_000L
        ),
        UvcFrameConfig(
            dirName = "720p",
            width = 1280,
            height = 720,
            defaultInterval = 333333,
            intervals = listOf(166666, 333333, 666666),
            minBitRate = 29_491_200L,
            maxBitRate = 221_184_000L
        ),
        UvcFrameConfig(
            dirName = "1080p",
            width = 1920,
            height = 1080,
            defaultInterval = 333333,
            intervals = listOf(166666, 333333, 666666),
            minBitRate = 66_355_200L,
            maxBitRate = 497_664_000L
        )
    )

    private val uncompressedFrames = listOf(
        UvcFrameConfig(
            dirName = "360p",
            width = 640,
            height = 360,
            defaultInterval = 333333,
            intervals = listOf(333333, 666666, 1000000),
            minBitRate = 36_864_000L,
            maxBitRate = 110_592_000L
        ),
        UvcFrameConfig(
            dirName = "480p",
            width = 640,
            height = 480,
            defaultInterval = 333333,
            intervals = listOf(333333, 666666, 1000000),
            minBitRate = 49_152_000L,
            maxBitRate = 147_456_000L
        ),
        UvcFrameConfig(
            dirName = "720p",
            width = 1280,
            height = 720,
            defaultInterval = 666666,
            intervals = listOf(666666, 1000000, 2000000),
            minBitRate = 73_728_000L,
            maxBitRate = 221_184_000L
        )
    )

    private fun determineGadgetPath(): Path {
        val pathsToTry: List<Path> = buildList {
            val prefPath = gadgetUserPreferences.usbGadgetPath
            if (prefPath.path.isNotBlank()) {
                this.add(Path(prefPath.path))
            }
            this.add(CONFIG_FS_PATH / "g1")
            this.add(CONFIG_FS_PATH / "g2")
        }

        for (path in pathsToTry) {
            if (path.isDirectory()) {
                return path
            }
        }

        val gadgetPaths = (runCatching { CONFIG_FS_PATH.listDirectoryEntries() }.getOrNull() ?: emptyList())
            .filter { it.isDirectory() }

        if (gadgetPaths.isEmpty()) {
            return pathsToTry.first()
        } else {
            for (path in gadgetPaths) {
                if ((path / "UDC").isRegularFile()) {
                    return path
                }
            }
            return gadgetPaths.first()
        }
    }

    fun createCharacterDevices() {
        val currentLinks = getCurrentConfigSymlinks()

        // Save original non-UVC function symlinks if we haven't already saved them
        if (!BACKUP_STATE_FILE.exists() && currentLinks.none { it.second.name.startsWith("uvc") }) {
            saveOriginalConfigState(currentLinks)
        }

        try {
            disableGadget()
        } catch (e: IOException) {
            Timber.e(e, "Failed to disable USB gadget before UVC config")
        }

        // Unlink all existing function symlinks in configs/b.1 so uvc.0 is not busy and MTP/ACM don't exhaust DWC3 FIFOs
        currentLinks.forEach { (linkPath, _) ->
            runCatching { linkPath.deleteIfExists() }
        }

        // Configure UVC ConfigFS tree under /config/usb_gadget/g1/functions/uvc.0
        try {
            configureUvcFunction()
        } catch (e: Exception) {
            Timber.e(e, "Error configuring UVC ConfigFS function")
        }

        // Configure IAD (Interface Association Descriptor) device class for clean composite UVC + ADB enumeration
        runCatching {
            (USB_GADGET_PATH / "bDeviceClass").writeText("0xef\n")
            (USB_GADGET_PATH / "bDeviceSubClass").writeText("0x02\n")
            (USB_GADGET_PATH / "bDeviceProtocol").writeText("0x01\n")
        }

        // Link uvc.0 (and optionally ffs.adb so ADB stays alive) into configs/b.1
        try {
            CONFIGS_PATH.createDirectories()
            val uvcLink = CONFIGS_PATH / "f1"
            uvcLink.deleteIfExists()
            uvcLink.createSymbolicLinkPointingTo(UVC_FUNCTION_PATH)

            val keepAdb = !gadgetUserPreferences.disableGadgetFunctionsDuringConfiguration
            val adbFuncPath = FUNCTIONS_PATH / "ffs.adb"
            if (keepAdb && adbFuncPath.isDirectory()) {
                val adbLink = CONFIGS_PATH / "f2"
                adbLink.deleteIfExists()
                adbLink.createSymbolicLinkPointingTo(adbFuncPath)
            }

            runCatching {
                val configStrPath = CONFIGS_PATH / "strings/0x409/configuration"
                if (configStrPath.exists()) {
                    configStrPath.writeText(if (keepAdb) "uvc_adb\n" else "uvc\n")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to link UVC function to config")
        }

        try {
            enableGadget()
        } catch (e: IOException) {
            Timber.e(e, "Failed to enable USB gadget after UVC config")
        }
    }

    private fun cleanExistingUvcSymlinks(uvcPath: Path) {
        val symlinksToDelete = listOf(
            uvcPath / "streaming/class/fs/h",
            uvcPath / "streaming/class/hs/h",
            uvcPath / "streaming/class/ss/h",
            uvcPath / "streaming/header/h/m",
            uvcPath / "streaming/header/h/u",
            uvcPath / "streaming/header/h/h",
            uvcPath / "control/class/fs/h",
            uvcPath / "control/class/ss/h"
        )
        symlinksToDelete.forEach { link ->
            runCatching { link.deleteIfExists() }
        }
    }

    @Throws(IOException::class)
    private fun configureUvcFunction() {
        UVC_FUNCTION_PATH.createDirectories()
        cleanExistingUvcSymlinks(UVC_FUNCTION_PATH)

        val maxPacket = gadgetUserPreferences.uvcMaxPacketSize.coerceIn(1024, 3072)
        runCatching { (UVC_FUNCTION_PATH / "streaming_maxpacket").writeText("$maxPacket\n") }
        runCatching { (UVC_FUNCTION_PATH / "streaming_maxburst").writeText("1\n") }
        runCatching { (UVC_FUNCTION_PATH / "streaming_interval").writeText("1\n") }

        // Control descriptors
        val ctrlHeader = UVC_FUNCTION_PATH / "control/header/h"
        ctrlHeader.createDirectories()
        createSymlinkSafe(UVC_FUNCTION_PATH / "control/class/fs/h", ctrlHeader)
        createSymlinkSafe(UVC_FUNCTION_PATH / "control/class/ss/h", ctrlHeader)

        // Format 1: MJPEG
        val mjpegRoot = UVC_FUNCTION_PATH / "streaming/mjpeg/m"
        mjpegRoot.createDirectories()
        for (frame in mjpegFrames) {
            configureFrameDir(mjpegRoot / frame.dirName, frame)
        }
        runCatching { (mjpegRoot / "bmaControls").writeText("0x04\n") }

        // Format 2: Uncompressed YUYV
        val uncompRoot = UVC_FUNCTION_PATH / "streaming/uncompressed/u"
        uncompRoot.createDirectories()
        for (frame in uncompressedFrames) {
            configureFrameDir(uncompRoot / frame.dirName, frame)
        }

        // Streaming header & speed class links
        val streamHeader = UVC_FUNCTION_PATH / "streaming/header/h"
        streamHeader.createDirectories()
        createSymlinkSafe(streamHeader / "m", mjpegRoot)
        createSymlinkSafe(streamHeader / "u", uncompRoot)

        createSymlinkSafe(UVC_FUNCTION_PATH / "streaming/class/fs/h", streamHeader)
        createSymlinkSafe(UVC_FUNCTION_PATH / "streaming/class/hs/h", streamHeader)
        createSymlinkSafe(UVC_FUNCTION_PATH / "streaming/class/ss/h", streamHeader)
    }

    private fun configureFrameDir(frameDir: Path, cfg: UvcFrameConfig) {
        frameDir.createDirectories()
        (frameDir / "wWidth").writeText("${cfg.width}\n")
        (frameDir / "wHeight").writeText("${cfg.height}\n")
        (frameDir / "dwMinBitRate").writeText("${cfg.minBitRate}\n")
        (frameDir / "dwMaxBitRate").writeText("${cfg.maxBitRate}\n")
        (frameDir / "dwMaxVideoFrameBufferSize").writeText("${cfg.maxFrameBufferSize}\n")
        (frameDir / "dwDefaultFrameInterval").writeText("${cfg.defaultInterval}\n")
        val intervalsText = cfg.intervals.joinToString(separator = "\n", postfix = "\n")
        (frameDir / "dwFrameInterval").writeText(intervalsText)
    }

    private fun createSymlinkSafe(link: Path, target: Path) {
        runCatching {
            link.deleteIfExists()
            link.createSymbolicLinkPointingTo(target)
        }.onFailure {
            Timber.w(it, "Failed to create symlink $link -> $target")
        }
    }

    private fun getCurrentConfigSymlinks(): List<Pair<Path, Path>> {
        val entries: List<Path> = runCatching { CONFIGS_PATH.listDirectoryEntries() }.getOrNull() ?: emptyList()
        return entries.filter { it.isSymbolicLink() }.mapNotNull {
            runCatching { Pair(it, it.toRealPath()) }.getOrNull()
        }
    }

    private fun saveOriginalConfigState(links: List<Pair<Path, Path>>) {
        runCatching {
            val lines = mutableListOf<String>()
            val devClass = runCatching { (USB_GADGET_PATH / "bDeviceClass").readText().trim() }.getOrDefault("0x00")
            val devSubClass = runCatching { (USB_GADGET_PATH / "bDeviceSubClass").readText().trim() }.getOrDefault("0x00")
            val devProto = runCatching { (USB_GADGET_PATH / "bDeviceProtocol").readText().trim() }.getOrDefault("0x00")
            lines.add("CLASS|$devClass|$devSubClass|$devProto")
            for ((link, target) in links) {
                lines.add("LINK|${link}|${target}")
            }
            BACKUP_STATE_FILE.writeLines(lines)
        }.onFailure {
            Timber.w(it, "Failed to save original USB config state")
        }
    }

    fun deleteCharacterDevices() {
        try {
            disableGadget()
        } catch (e: IOException) {
            Timber.w(e, "Failed to disable USB gadget during UVC teardown")
        }

        // Remove current symlinks in configs/b.1
        getCurrentConfigSymlinks().forEach { (linkPath, _) ->
            runCatching { linkPath.deleteIfExists() }
        }

        cleanExistingUvcSymlinks(UVC_FUNCTION_PATH)

        // Restore saved original links (or fallback to ffs.adb)
        var restoredAny = false
        if (BACKUP_STATE_FILE.exists()) {
            runCatching {
                val lines = BACKUP_STATE_FILE.readLines()
                for (line in lines) {
                    val parts = line.split("|")
                    if (parts.size == 4 && parts[0] == "CLASS") {
                        runCatching { (USB_GADGET_PATH / "bDeviceClass").writeText("${parts[1]}\n") }
                        runCatching { (USB_GADGET_PATH / "bDeviceSubClass").writeText("${parts[2]}\n") }
                        runCatching { (USB_GADGET_PATH / "bDeviceProtocol").writeText("${parts[3]}\n") }
                    } else if (parts.size == 3 && parts[0] == "LINK") {
                        val linkPath = Path(parts[1])
                        val targetPath = Path(parts[2])
                        if (targetPath.exists()) {
                            linkPath.deleteIfExists()
                            linkPath.createSymbolicLinkPointingTo(targetPath)
                            restoredAny = true
                        }
                    }
                }
                BACKUP_STATE_FILE.deleteIfExists()
            }.onFailure {
                Timber.w(it, "Failed to restore saved USB gadget links")
            }
        }

        if (!restoredAny) {
            val adbFunc = FUNCTIONS_PATH / "ffs.adb"
            if (adbFunc.isDirectory()) {
                runCatching {
                    (CONFIGS_PATH / "f1").createSymbolicLinkPointingTo(adbFunc)
                }
            }
        }

        try {
            enableGadget()
        } catch (e: IOException) {
            Timber.w(e, "Failed to re-enable USB gadget after UVC teardown")
        }
    }

    @Throws(IOException::class)
    private fun disableGadget() {
        UDC_PATH.writer(options = arrayOf(StandardOpenOption.SYNC)).use {
            it.write("\n")
        }
    }

    @Throws(IOException::class)
    private fun enableGadget() {
        val udc = getUDC()
        UDC_PATH.writer(options = arrayOf(StandardOpenOption.SYNC)).use {
            it.write(udc)
        }
    }

    @Throws(IOException::class)
    fun getUDC(): String {
        val udcList: List<Path> = Path("/sys/class/udc").listDirectoryEntries()
        Timber.d("UDC value from file listing is: $udcList")

        val udcPath: Path = if (udcList.isEmpty()) {
            Path("")
        } else if (udcList.size == 1) {
            udcList.first()
        } else {
            val filteredList = udcList.filter { it.isSymbolicLink() }
            if (filteredList.isEmpty()) {
                udcList.first()
            } else {
                filteredList.first()
            }
        }

        return udcPath.name
    }

    companion object {
        const val UVC_FUNCTION_NAME = "uvc.0"
    }
}

@JvmInline
@Parcelize
value class UsbGadgetPath(val path: String) : Parcelable
