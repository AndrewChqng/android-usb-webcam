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

    private fun execRoot(vararg commands: String): Boolean {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", commands.joinToString("\n")))
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            val err = proc.errorStream.bufferedReader().readText().trim()
            Timber.w("Root cmd exited with $exitCode: $err")
        }
        return exitCode == 0
    }

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
            dirName = "f1_360p",
            width = 640,
            height = 360,
            defaultInterval = 333333,
            intervals = listOf(166666, 333333, 666666),
            minBitRate = 18_432_000L,
            maxBitRate = 110_592_000L
        ),
        UvcFrameConfig(
            dirName = "f2_720p",
            width = 1280,
            height = 720,
            defaultInterval = 333333,
            intervals = listOf(166666, 333333, 666666),
            minBitRate = 29_491_200L,
            maxBitRate = 221_184_000L
        ),
        UvcFrameConfig(
            dirName = "f3_1080p",
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
            dirName = "f1_360p",
            width = 640,
            height = 360,
            defaultInterval = 333333,
            intervals = listOf(333333, 666666, 1000000),
            minBitRate = 36_864_000L,
            maxBitRate = 110_592_000L
        ),
        UvcFrameConfig(
            dirName = "f2_480p",
            width = 640,
            height = 480,
            defaultInterval = 333333,
            intervals = listOf(333333, 666666, 1000000),
            minBitRate = 49_152_000L,
            maxBitRate = 147_456_000L
        ),
        UvcFrameConfig(
            dirName = "f3_720p",
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
        execRoot("rm -f ${USB_GADGET_PATH}/os_desc/b.1")

        val keepAdb = !gadgetUserPreferences.disableGadgetFunctionsDuringConfiguration

        // Unlink existing uvc symlinks (so uvc.0 is not EBUSY) and non-ADB symlinks (so MTP/ACM don't exhaust DWC3 FIFOs).
        // Keep existing ffs.adb symlink intact when keepAdb is true so adbd's FunctionFS state stays FFS_ACTIVE!
        var hasActiveAdbSymlink = false
        for ((linkPath, targetPath) in currentLinks) {
            val isAdb = targetPath.name == "ffs.adb"
            if (keepAdb && isAdb) {
                hasActiveAdbSymlink = true
            } else {
                runCatching { linkPath.deleteIfExists() }
            }
        }

        // Configure UVC ConfigFS tree under /config/usb_gadget/g1/functions/uvc.0
        val maxPacket = gadgetUserPreferences.uvcMaxPacketSize.coerceIn(1024, 3072)
        try {
            configureUvcFunction(maxPacket)
        } catch (e: Exception) {
            Timber.e(e, "Error configuring UVC ConfigFS function")
        }

        // Configure IAD (Interface Association Descriptor) device class for clean composite UVC + ADB enumeration
        execRoot(
            "echo 0xef > ${USB_GADGET_PATH}/bDeviceClass",
            "echo 0x02 > ${USB_GADGET_PATH}/bDeviceSubClass",
            "echo 0x01 > ${USB_GADGET_PATH}/bDeviceProtocol"
        )

        // Link uvc.0 (and ffs.adb if not already linked) into configs/b.1
        try {
            CONFIGS_PATH.createDirectories()
            val uvcLink = CONFIGS_PATH / UVC_FUNCTION_NAME
            uvcLink.deleteIfExists()
            execRoot("ln -sf ${UVC_FUNCTION_PATH} ${uvcLink}")

            val adbFuncPath = FUNCTIONS_PATH / "ffs.adb"
            if (keepAdb && !hasActiveAdbSymlink && adbFuncPath.isDirectory()) {
                val adbLink = CONFIGS_PATH / "ffs.adb"
                adbLink.deleteIfExists()
                execRoot("ln -sf ${adbFuncPath} ${adbLink}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to link UVC function to config")
        }

        // Enable gadget; if 3072 maxpacket fails DWC3 FIFO allocation, fallback to 1024 automatically
        var boundOk = tryEnableGadget()
        if (!boundOk && maxPacket > 1024) {
            Timber.w("UDC bind with maxpacket=$maxPacket failed; retrying with 1024")
            runCatching {
                (CONFIGS_PATH / UVC_FUNCTION_NAME).deleteIfExists()
                execRoot(
                    "echo 1024 > ${UVC_FUNCTION_PATH}/streaming_maxpacket",
                    "echo 0 > ${UVC_FUNCTION_PATH}/streaming_maxburst",
                    "ln -sf ${UVC_FUNCTION_PATH} ${CONFIGS_PATH}/${UVC_FUNCTION_NAME}"
                )
            }
            boundOk = tryEnableGadget()
        }

        if (!boundOk) {
            Timber.e("UDC failed to bind with UVC; rolling back to original USB config so ADB is not lost")
            deleteCharacterDevices()
        }
    }

    private fun tryEnableGadget(): Boolean {
        return try {
            enableGadget()
            val boundUdc = runCatching { UDC_PATH.readText().trim() }.getOrDefault("")
            Timber.i("Bound UDC after enableGadget(): '$boundUdc'")
            boundUdc.isNotEmpty() && boundUdc != "none"
        } catch (e: IOException) {
            Timber.e(e, "Failed to enable USB gadget")
            false
        }
    }

    private fun cleanExistingUvcSymlinks(uvcPath: Path) {
        // 1. Delete all symlinks in streaming/class/{fs,hs,ss} and control/class/{fs,ss}
        val classDirs = listOf(
            uvcPath / "streaming/class/fs",
            uvcPath / "streaming/class/hs",
            uvcPath / "streaming/class/ss",
            uvcPath / "control/class/fs",
            uvcPath / "control/class/ss"
        )
        for (dir in classDirs) {
            runCatching {
                if (dir.isDirectory()) {
                    dir.listDirectoryEntries().filter { it.isSymbolicLink() }.forEach { it.deleteIfExists() }
                }
            }
        }

        // 2. Delete all symlinks in streaming/header/*
        val streamHeaderRoot = uvcPath / "streaming/header"
        runCatching {
            if (streamHeaderRoot.isDirectory()) {
                for (hdr in streamHeaderRoot.listDirectoryEntries().filter { it.isDirectory() }) {
                    hdr.listDirectoryEntries().filter { it.isSymbolicLink() }.forEach { it.deleteIfExists() }
                }
            }
        }

        // 3. Delete any pre-existing frame directories in streaming/mjpeg/* and streaming/uncompressed/*
        val formatRoots = listOf(
            uvcPath / "streaming/mjpeg",
            uvcPath / "streaming/uncompressed"
        )
        for (fmtRoot in formatRoots) {
            runCatching {
                if (fmtRoot.isDirectory()) {
                    for (fmtDir in fmtRoot.listDirectoryEntries().filter { it.isDirectory() }) {
                        for (frameDir in fmtDir.listDirectoryEntries().filter { it.isDirectory() }) {
                            frameDir.deleteIfExists()
                        }
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun configureUvcFunction(maxPacket: Int) {
        UVC_FUNCTION_PATH.createDirectories()
        cleanExistingUvcSymlinks(UVC_FUNCTION_PATH)

        runCatching { (UVC_FUNCTION_PATH / "streaming_maxpacket").writeText("$maxPacket\n") }
        runCatching { (UVC_FUNCTION_PATH / "streaming_maxburst").writeText("0\n") }
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
        runCatching { (frameDir / "wWidth").writeText("${cfg.width}\n") }
        runCatching { (frameDir / "wHeight").writeText("${cfg.height}\n") }
        runCatching { (frameDir / "dwMinBitRate").writeText("${cfg.minBitRate}\n") }
        runCatching { (frameDir / "dwMaxBitRate").writeText("${cfg.maxBitRate}\n") }
        runCatching { (frameDir / "dwMaxVideoFrameBufferSize").writeText("${cfg.maxFrameBufferSize}\n") }
        runCatching { (frameDir / "dwDefaultFrameInterval").writeText("${cfg.defaultInterval}\n") }
        val intervalsText = cfg.intervals.joinToString(separator = "\n", postfix = "\n")
        runCatching { (frameDir / "dwFrameInterval").writeText(intervalsText) }
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

        // Remove UVC symlinks in configs/b.1; keep ffs.adb intact while restoring others
        val existingLinks = getCurrentConfigSymlinks()
        existingLinks.forEach { (linkPath, targetPath) ->
            if (targetPath.name != "ffs.adb") {
                runCatching { linkPath.deleteIfExists() }
            }
        }

        cleanExistingUvcSymlinks(UVC_FUNCTION_PATH)

        // Restore saved original links (or fallback to ffs.adb)
        var restoredAny = getCurrentConfigSymlinks().isNotEmpty()
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
                        if (targetPath.exists() && !linkPath.exists()) {
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
        val currentUdc = runCatching { UDC_PATH.readText().trim() }.getOrDefault("")
        if (currentUdc.isEmpty() || currentUdc == "none") return

        execRoot("echo '' > $UDC_PATH")
    }

    @Throws(IOException::class)
    private fun enableGadget() {
        val udc = getUDC()
        execRoot("echo '$udc' > $UDC_PATH")
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
