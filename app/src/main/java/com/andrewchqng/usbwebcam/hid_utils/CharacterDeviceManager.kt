package com.andrewchqng.usbwebcam.hid_utils

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
import com.andrewchqng.usbwebcam.settings.GadgetUserPreferences
import com.andrewchqng.usbwebcam.shell_utils.RootStateHolder
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
        return withContext(Dispatchers.IO) {
            createUvcGadgetViaRootShell(gadgetUserPreferences)
            fixSelinuxPermissions()

            // Poll until UVC function is bound to a UDC (so open() will not fail with ENXIO)
            var detectedNode: String? = null
            try {
                withTimeout(4000) {
                    while (detectedNode == null) {
                        val candidate = discoverUvcVideoDevicePath(gadgetUserPreferences.uvcVideoDevicePath.path)
                        val udcBound = runCatching {
                            val res = Shell.cmd("cat /config/usb_gadget/g1/UDC 2>/dev/null").exec()
                            val udc = res.out.firstOrNull()?.trim().orEmpty()
                            udc.isNotEmpty() && udc != "none"
                        }.getOrDefault(true)
                        if (candidate != null && udcBound) {
                            detectedNode = candidate
                        } else {
                            delay(200)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Timber.e("Timed out waiting for UVC /dev/videoX character device to appear.")
            }

            val resolvedDetected = detectedNode
            val finalNode: String = if (!resolvedDetected.isNullOrBlank()) {
                resolvedDetected
            } else {
                val prefPath = gadgetUserPreferences.uvcVideoDevicePath.path
                if (prefPath.isNotBlank() && prefPath != AUTO_DETECT_VIDEO_PATH) prefPath else "/dev/video2"
            }

            DevicePaths.setUvcDevicePath(UvcVideoDevicePath(finalNode))
            val exists = File(finalNode).exists() ||
                runCatching { Shell.cmd("test -e $finalNode").exec().isSuccess }.getOrDefault(false)

            if (exists) {
                fixCharacterDevicePermissions(finalNode)
                if (gadgetUserPreferences.boostUsbIrqPriority) {
                    applyUsbIrqOptimizations()
                }
                finalNode
            } else {
                Timber.e("UVC V4L2 node $finalNode does not exist after gadget creation")
                null
            }
        }
    }

    private fun createUvcGadgetViaRootShell(prefs: GadgetUserPreferences) {
        val gPath = prefs.usbGadgetPath.path.ifBlank { "/config/usb_gadget/g1" }
        val maxPacket = prefs.uvcMaxPacketSize.coerceIn(1024, 3072)
        val maxBurst = prefs.uvcMaxBurst.coerceIn(0, 15)
        val keepAdb = if (prefs.disableGadgetFunctionsDuringConfiguration) "0" else "1"
        val boostIrq = if (prefs.boostUsbIrqPriority) "1" else "0"
        val exposedFormats = prefs.exposedFormats
        val customProduct = prefs.customProductName.trim()
        val customMfg = prefs.customManufacturerName.trim()

        val (mjpegFrames, mjpegDefaultIdx) = when (prefs.resolutionProfile) {
            "max_1440p" -> listOf(
                MJPEG_FRAME_360P, MJPEG_FRAME_480P, MJPEG_FRAME_720P, MJPEG_FRAME_1080P, MJPEG_FRAME_1440P
            ) to 5
            "max_4k" -> listOf(
                MJPEG_FRAME_360P, MJPEG_FRAME_480P, MJPEG_FRAME_720P, MJPEG_FRAME_1080P, MJPEG_FRAME_1440P, MJPEG_FRAME_4K
            ) to 6
            "max_720p" -> listOf(
                MJPEG_FRAME_360P, MJPEG_FRAME_480P, MJPEG_FRAME_720P
            ) to 3
            "only_1080p" -> listOf(
                MJPEG_FRAME_1080P.copy(dirName = "f1_1080p")
            ) to 1
            "only_720p" -> listOf(
                MJPEG_FRAME_720P.copy(dirName = "f1_720p")
            ) to 1
            "only_4k" -> listOf(
                MJPEG_FRAME_4K.copy(dirName = "f1_4k")
            ) to 1
            else -> listOf(
                MJPEG_FRAME_360P, MJPEG_FRAME_480P, MJPEG_FRAME_720P, MJPEG_FRAME_1080P
            ) to 4
        }

        val (yuyvFrames, yuyvDefaultIdx) = when (prefs.resolutionProfile) {
            "max_720p" -> listOf(
                YUYV_FRAME_360P, YUYV_FRAME_480P, YUYV_FRAME_720P
            ) to 3
            "only_1080p" -> listOf(
                YUYV_FRAME_1080P.copy(dirName = "f1_1080p")
            ) to 1
            "only_720p" -> listOf(
                YUYV_FRAME_720P.copy(dirName = "f1_720p")
            ) to 1
            "only_4k" -> listOf(
                YUYV_FRAME_1080P.copy(dirName = "f1_1080p")
            ) to 1
            else -> listOf(
                YUYV_FRAME_360P, YUYV_FRAME_480P, YUYV_FRAME_720P, YUYV_FRAME_1080P
            ) to 4
        }

        val mjpegFramesScript = generateFrameCommands("streaming/mjpeg/m", mjpegFrames, mjpegDefaultIdx)
        val yuyvFramesScript = generateFrameCommands("streaming/uncompressed/u", yuyvFrames, yuyvDefaultIdx)

        val script = """
            G="$gPath"
            [ -d "${'$'}G" ] || G="/config/usb_gadget/g1"
            CFG="${'$'}G/configs/b.1"
            UVC="${'$'}G/functions/uvc.0"
            BACKUP="/data/local/tmp/usb_webcam_saved_links.txt"

            # Save original non-UVC function symlinks and strings once
            if [ ! -f "${'$'}BACKUP" ]; then
                has_uvc=0
                for f in "${'$'}CFG"/*; do
                    [ -L "${'$'}f" ] || continue
                    t=${'$'}(readlink -f "${'$'}f" 2>/dev/null)
                    case "${'$'}t" in *uvc*) has_uvc=1 ;; esac
                done
                if [ "${'$'}has_uvc" = "0" ]; then
                    rm -f "${'$'}BACKUP"
                    echo "CLASS|${'$'}(cat "${'$'}G/bDeviceClass" 2>/dev/null)|${'$'}(cat "${'$'}G/bDeviceSubClass" 2>/dev/null)|${'$'}(cat "${'$'}G/bDeviceProtocol" 2>/dev/null)" >> "${'$'}BACKUP"
                    echo "STRINGS|${'$'}(cat "${'$'}G/strings/0x409/product" 2>/dev/null)|${'$'}(cat "${'$'}G/strings/0x409/manufacturer" 2>/dev/null)" >> "${'$'}BACKUP"
                    for f in "${'$'}CFG"/*; do
                        [ -L "${'$'}f" ] || continue
                        t=${'$'}(readlink -f "${'$'}f" 2>/dev/null)
                        echo "LINK|${'$'}f|${'$'}t" >> "${'$'}BACKUP"
                    done
                fi
            fi

            if [ -n "$customProduct" ]; then
                echo "$customProduct" > "${'$'}G/strings/0x409/product" 2>/dev/null || true
            fi
            if [ -n "$customMfg" ]; then
                echo "$customMfg" > "${'$'}G/strings/0x409/manufacturer" 2>/dev/null || true
            fi

            echo "" > "${'$'}G/UDC" 2>/dev/null || true
            rm -f "${'$'}G/os_desc/b.1" 2>/dev/null || true

            has_adb=0
            for f in "${'$'}CFG"/*; do
                [ -L "${'$'}f" ] || continue
                t=${'$'}(readlink -f "${'$'}f" 2>/dev/null)
                case "${'$'}t" in
                    *ffs.adb)
                        if [ "$keepAdb" = "1" ]; then
                            has_adb=1
                        else
                            rm -f "${'$'}f"
                        fi
                        ;;
                    *)
                        rm -f "${'$'}f"
                        ;;
                esac
            done

            if [ -d "${'$'}UVC" ]; then
                find "${'$'}UVC/streaming/class" -type l -exec rm -f {} + 2>/dev/null || true
                find "${'$'}UVC/control/class" -type l -exec rm -f {} + 2>/dev/null || true
                find "${'$'}UVC/streaming/header" -type l -exec rm -f {} + 2>/dev/null || true
                find "${'$'}UVC/streaming/mjpeg" -mindepth 2 -maxdepth 2 -type d -exec rmdir {} + 2>/dev/null || true
                find "${'$'}UVC/streaming/mjpeg" -mindepth 1 -maxdepth 1 -type d -exec rmdir {} + 2>/dev/null || true
                find "${'$'}UVC/streaming/uncompressed" -mindepth 2 -maxdepth 2 -type d -exec rmdir {} + 2>/dev/null || true
                find "${'$'}UVC/streaming/uncompressed" -mindepth 1 -maxdepth 1 -type d -exec rmdir {} + 2>/dev/null || true
                find "${'$'}UVC/streaming/h264" -mindepth 2 -maxdepth 2 -type d -exec rmdir {} + 2>/dev/null || true
                find "${'$'}UVC/streaming/h264" -mindepth 1 -maxdepth 1 -type d -exec rmdir {} + 2>/dev/null || true
                find "${'$'}UVC/streaming/header" -mindepth 1 -maxdepth 1 -type d -exec rmdir {} + 2>/dev/null || true
                find "${'$'}UVC/control/header" -mindepth 1 -maxdepth 1 -type d -exec rmdir {} + 2>/dev/null || true
                rmdir "${'$'}UVC" 2>/dev/null || true
            fi
            mkdir -p "${'$'}UVC"

            echo "$maxPacket" > "${'$'}UVC/streaming_maxpacket" 2>/dev/null || true
            echo "$maxBurst" > "${'$'}UVC/streaming_maxburst" 2>/dev/null || true
            echo "1" > "${'$'}UVC/streaming_interval" 2>/dev/null || true

            mkdir -p "${'$'}UVC/control/header/h"
            ln -s "${'$'}UVC/control/header/h" "${'$'}UVC/control/class/fs/h" 2>/dev/null || true
            ln -s "${'$'}UVC/control/header/h" "${'$'}UVC/control/class/ss/h" 2>/dev/null || true

$mjpegFramesScript
            echo 0x04 > "${'$'}UVC/streaming/mjpeg/m/bmaControls" 2>/dev/null || true

$yuyvFramesScript

            mkdir -p "${'$'}UVC/streaming/header/h"
            if [ "$exposedFormats" != "yuyv" ]; then
                ln -s "${'$'}UVC/streaming/mjpeg/m" "${'$'}UVC/streaming/header/h/m" 2>/dev/null || true
            fi
            if [ "$exposedFormats" != "mjpeg" ]; then
                ln -s "${'$'}UVC/streaming/uncompressed/u" "${'$'}UVC/streaming/header/h/u" 2>/dev/null || true
            fi
            ln -s "${'$'}UVC/streaming/header/h" "${'$'}UVC/streaming/class/fs/h" 2>/dev/null || true
            ln -s "${'$'}UVC/streaming/header/h" "${'$'}UVC/streaming/class/hs/h" 2>/dev/null || true
            ln -s "${'$'}UVC/streaming/header/h" "${'$'}UVC/streaming/class/ss/h" 2>/dev/null || true

            echo 0xef > "${'$'}G/bDeviceClass" 2>/dev/null || true
            echo 0x02 > "${'$'}G/bDeviceSubClass" 2>/dev/null || true
            echo 0x01 > "${'$'}G/bDeviceProtocol" 2>/dev/null || true

            # Unbind UDC and os_desc right before linking uvc.0 into configs/b.1 so adbd/init cannot rebind UDC in the middle
            echo "" > "${'$'}G/UDC" 2>/dev/null || true
            rm -f "${'$'}G/os_desc/b.1" "${'$'}CFG/uvc.0" 2>/dev/null || true
            ln -s "${'$'}UVC" "${'$'}CFG/uvc.0" 2>/dev/null || true
            if [ "$keepAdb" = "1" ] && [ "${'$'}has_adb" = "0" ] && [ -d "${'$'}G/functions/ffs.adb" ]; then
                rm -f "${'$'}CFG/ffs.adb" 2>/dev/null || true
                ln -s "${'$'}G/functions/ffs.adb" "${'$'}CFG/ffs.adb" 2>/dev/null || true
            fi
            ln -s "${'$'}CFG" "${'$'}G/os_desc/b.1" 2>/dev/null || true

            UDC_NAME=${'$'}(ls /sys/class/udc | head -n 1)
            for attempt in 1 2 3 4 5 6 7 8; do
                echo "${'$'}UDC_NAME" > "${'$'}G/UDC" 2>/dev/null || true
                BOUND=${'$'}(cat "${'$'}G/UDC" 2>/dev/null)
                if [ -n "${'$'}BOUND" ] && [ "${'$'}BOUND" != "none" ]; then
                    break
                fi
                sleep 0.05
            done
            if [ -z "${'$'}BOUND" ] || [ "${'$'}BOUND" = "none" ]; then
                echo "" > "${'$'}G/UDC" 2>/dev/null || true
                rm -f "${'$'}G/os_desc/b.1" "${'$'}CFG/uvc.0" 2>/dev/null || true
                echo 1024 > "${'$'}UVC/streaming_maxpacket" 2>/dev/null || true
                echo 0 > "${'$'}UVC/streaming_maxburst" 2>/dev/null || true
                ln -s "${'$'}UVC" "${'$'}CFG/uvc.0" 2>/dev/null || true
                ln -s "${'$'}CFG" "${'$'}G/os_desc/b.1" 2>/dev/null || true
                echo "${'$'}UDC_NAME" > "${'$'}G/UDC" 2>/dev/null || true
            fi

            if [ "$boostIrq" = "1" ]; then
                for p in ${'$'}(ps -A -o pid,comm | grep -E "irq/.*(dwc3|udc|usb)" | awk '{print ${'$'}1}'); do
                    chrt -f -p "${'$'}p" 90 2>/dev/null || chrt -f -p 90 "${'$'}p" 2>/dev/null || true
                    renice -n -20 -p "${'$'}p" 2>/dev/null || true
                done
                for irq in ${'$'}(awk '/dwc3|udc/ {gsub(":", "", ${'$'}1); print ${'$'}1}' /proc/interrupts 2>/dev/null); do
                    echo 2-3 > "/proc/irq/${'$'}irq/smp_affinity_list" 2>/dev/null || echo 2-5 > "/proc/irq/${'$'}irq/smp_affinity_list" 2>/dev/null || true
                done
            fi
        """.trimIndent()

        val res = Shell.cmd(script).exec()
        if (!res.isSuccess) {
            Timber.w("createUvcGadgetViaRootShell warning: out=${res.out} err=${res.err}")
        }
    }

    /**
     * Elevates DWC3/UDC threaded IRQ priority to SCHED_FIFO 90 and pins IRQ affinity to
     * performance cores (CPU 2-3). Qualcomm's dwc3-msm driver spawns a new [irq/<N>-dwc3]
     * thread asynchronously after UDC bind or USB bus reset with SCHED_OTHER on LITTLE cores
     * (CPU 0-1), which causes missed ISOC microframes (-EXDEV) and UVC_QUEUE_DROP_INCOMPLETE
     * truncated frames unless re-boosted once USB link enumeration completes.
     */
    fun applyUsbIrqOptimizations() {
        val cmd = """
            for p in ${'$'}(ps -A -o pid,comm | grep -E "irq/.*(dwc3|udc|usb)" | awk '{print ${'$'}1}'); do
                chrt -f -p "${'$'}p" 90 2>/dev/null || chrt -f -p 90 "${'$'}p" 2>/dev/null || true
                renice -n -20 -p "${'$'}p" 2>/dev/null || true
            done
            for irq in ${'$'}(awk '/dwc3|udc/ {gsub(":", "", ${'$'}1); print ${'$'}1}' /proc/interrupts 2>/dev/null); do
                echo 2-3 > "/proc/irq/${'$'}irq/smp_affinity_list" 2>/dev/null || echo 2-5 > "/proc/irq/${'$'}irq/smp_affinity_list" 2>/dev/null || true
            done
        """.trimIndent()
        runCatching {
            Shell.cmd(cmd).exec()
        }.onFailure {
            Timber.w(it, "Failed to apply USB IRQ scheduling optimizations")
        }
    }

    fun getBoundStreamingMaxPacket(prefs: GadgetUserPreferences): Int {
        val gPath = prefs.usbGadgetPath.path.ifBlank { "/config/usb_gadget/g1" }
        return runCatching {
            val out = Shell.cmd("cat \"$gPath/functions/uvc.0/streaming_maxpacket\" 2>/dev/null").exec().out
            out.firstOrNull()?.trim()?.toIntOrNull() ?: prefs.uvcMaxPacketSize
        }.getOrDefault(prefs.uvcMaxPacketSize)
    }

    /**
     * Scans /sys/class/video4linux/video* to find the V4L2 video output node created by the USB UVC gadget.
     * Note: Must use `break` (NOT `exit 0`) because libsu executes commands inside a persistent root shell!
     */
    fun discoverUvcVideoDevicePath(preferredOverride: String = ""): String? {
        if (preferredOverride.isNotBlank() && preferredOverride != AUTO_DETECT_VIDEO_PATH) {
            val ok = runCatching { Shell.cmd("test -e $preferredOverride").exec().isSuccess }.getOrDefault(false)
            if (ok) {
                return preferredOverride
            }
        }

        val findCmd = """
            for v in /sys/class/video4linux/video*; do
                [ -e "${'$'}v" ] || continue
                link_target=${'$'}(readlink -f "${'$'}v" 2>/dev/null)
                vname=${'$'}(cat "${'$'}v/name" 2>/dev/null)
                case "${'$'}link_target ${'$'}vname" in
                    *gadget*|*dwc3*|*udc*|*uvc*)
                        echo "/dev/${'$'}(basename "${'$'}v")"
                        break
                        ;;
                esac
            done
        """.trimIndent()

        val found = runCatching {
            val result = Shell.cmd(findCmd).exec()
            result.out.firstOrNull { it.startsWith("/dev/video") }?.trim()
        }.getOrNull()

        if (!found.isNullOrEmpty()) {
            Timber.i("Discovered UVC V4L2 node: $found")
            DevicePaths.setUvcDevicePath(UvcVideoDevicePath(found))
            return found
        }
        return null
    }

    private fun fixSelinuxPermissions() {
        val sepolicyCmd = rootStateHolder.sepolicyCommand ?: return
        runCatching {
            val commands = SELINUX_POLICIES.map { policy -> "$sepolicyCmd '$policy'" }.toTypedArray()
            Shell.cmd(*commands).exec()
        }.onFailure {
            Timber.w(it, "Failed to apply SELinux policies")
        }
    }

    fun fixCharacterDevicePermissions(device: DevicePath) = fixCharacterDevicePermissions(device.path)

    fun fixCharacterDevicePermissions(device: String) {
        fixSelinuxPermissions()

        val appUID: Int = application.applicationInfo.uid
        val categories = getSelinuxCategories()
        val chconTarget = if (categories.isNotEmpty()) {
            "u:object_r:device:s0:$categories"
        } else {
            "u:object_r:device:s0"
        }

        runCatching {
            Shell.cmd(
                "chown '${appUID}:${appUID}' $device",
                "chmod 660 $device",
                "chcon '$chconTarget' $device"
            ).exec()
        }.onFailure {
            Timber.e(it, "Failed to fix character device permissions on $device")
        }
    }

    private fun getSelinuxCategories(): String {
        val appDataDirPath: String = application.applicationInfo.dataDir
        return runCatching {
            val commandResult = Shell.cmd("stat -c %C $appDataDirPath").exec()
            val selinuxContextString = commandResult.out.joinToString(separator = "\n").trim()
            val categories = selinuxContextString.substringAfterLast(':', "")
            Timber.d("context (before,after): (%s,%s)", selinuxContextString, categories)
            categories
        }.getOrDefault("")
    }

    suspend fun deleteCharacterDevices(gadgetUserPreferences: GadgetUserPreferences) {
        withContext(Dispatchers.IO) {
            val gPath = gadgetUserPreferences.usbGadgetPath.path.ifBlank { "/config/usb_gadget/g1" }
            val script = """
                G="$gPath"
                [ -d "${'$'}G" ] || G="/config/usb_gadget/g1"
                CFG="${'$'}G/configs/b.1"
                UVC="${'$'}G/functions/uvc.0"
                BACKUP="/data/local/tmp/usb_webcam_saved_links.txt"

                echo "" > "${'$'}G/UDC" 2>&1
            rm -f "${'$'}G/os_desc/b.1" 2>/dev/null || true
            echo "AFTER_UNBIND_UDC=${'$'}(cat "${'$'}G/UDC" 2>/dev/null)"

                for f in "${'$'}CFG"/*; do
                    [ -L "${'$'}f" ] || continue
                    t=${'$'}(readlink -f "${'$'}f" 2>/dev/null)
                    case "${'$'}t" in
                        *ffs.adb) ;;
                        *) rm -f "${'$'}f" ;;
                    esac
                done

                if [ -d "${'$'}UVC" ]; then
                    find "${'$'}UVC/streaming/class" -type l -exec rm -f {} + 2>/dev/null || true
                    find "${'$'}UVC/control/class" -type l -exec rm -f {} + 2>/dev/null || true
                    find "${'$'}UVC/streaming/header" -type l -exec rm -f {} + 2>/dev/null || true
                    find "${'$'}UVC/streaming/mjpeg" -mindepth 2 -maxdepth 2 -type d -exec rmdir {} + 2>/dev/null || true
                    find "${'$'}UVC/streaming/mjpeg" -mindepth 1 -maxdepth 1 -type d -exec rmdir {} + 2>/dev/null || true
                    find "${'$'}UVC/streaming/uncompressed" -mindepth 2 -maxdepth 2 -type d -exec rmdir {} + 2>/dev/null || true
                    find "${'$'}UVC/streaming/uncompressed" -mindepth 1 -maxdepth 1 -type d -exec rmdir {} + 2>/dev/null || true
                    find "${'$'}UVC/streaming/h264" -mindepth 2 -maxdepth 2 -type d -exec rmdir {} + 2>/dev/null || true
                    find "${'$'}UVC/streaming/h264" -mindepth 1 -maxdepth 1 -type d -exec rmdir {} + 2>/dev/null || true
                    find "${'$'}UVC/streaming/header" -mindepth 1 -maxdepth 1 -type d -exec rmdir {} + 2>/dev/null || true
                    find "${'$'}UVC/control/header" -mindepth 1 -maxdepth 1 -type d -exec rmdir {} + 2>/dev/null || true
                    rmdir "${'$'}UVC" 2>/dev/null || true
                fi
                mkdir -p "${'$'}UVC" 2>/dev/null || true

                if [ -f "${'$'}BACKUP" ]; then
                    while IFS='|' read -r kind a b c; do
                        if [ "${'$'}kind" = "CLASS" ]; then
                            [ -n "${'$'}a" ] && echo "${'$'}a" > "${'$'}G/bDeviceClass" 2>/dev/null || true
                            [ -n "${'$'}b" ] && echo "${'$'}b" > "${'$'}G/bDeviceSubClass" 2>/dev/null || true
                            [ -n "${'$'}c" ] && echo "${'$'}c" > "${'$'}G/bDeviceProtocol" 2>/dev/null || true
                        elif [ "${'$'}kind" = "STRINGS" ]; then
                            [ -n "${'$'}a" ] && echo "${'$'}a" > "${'$'}G/strings/0x409/product" 2>/dev/null || true
                            [ -n "${'$'}b" ] && echo "${'$'}b" > "${'$'}G/strings/0x409/manufacturer" 2>/dev/null || true
                        elif [ "${'$'}kind" = "LINK" ]; then
                            if [ -d "${'$'}b" ] && [ ! -e "${'$'}a" ]; then
                                ln -s "${'$'}b" "${'$'}a" 2>/dev/null || true
                            fi
                        fi
                    done < "${'$'}BACKUP"
                    rm -f "${'$'}BACKUP"
                fi

                rm -f "${'$'}G/os_desc/b.1" 2>/dev/null || true
                ln -s "${'$'}CFG" "${'$'}G/os_desc/b.1" 2>/dev/null || true
                UDC_NAME=${'$'}(ls /sys/class/udc | head -n 1)
                echo "${'$'}UDC_NAME" > "${'$'}G/UDC" 2>/dev/null || true
            """.trimIndent()
            Shell.cmd(script).exec()
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

        private data class UvcFrameConfig(
            val dirName: String,
            val width: Int,
            val height: Int,
            val minBitRate: Long,
            val maxBitRate: Long,
            val maxBufSize: Long,
            val defaultInterval: Long,
            val intervals: List<Long>
        )

        private val MJPEG_FRAME_360P = UvcFrameConfig(
            dirName = "f1_360p", width = 640, height = 360,
            minBitRate = 18432000L, maxBitRate = 110592000L, maxBufSize = 460800L,
            defaultInterval = 333333L, intervals = listOf(166666L, 333333L, 666666L)
        )
        private val MJPEG_FRAME_480P = UvcFrameConfig(
            dirName = "f2_480p", width = 640, height = 480,
            minBitRate = 24576000L, maxBitRate = 147456000L, maxBufSize = 614400L,
            defaultInterval = 333333L, intervals = listOf(166666L, 333333L, 666666L)
        )
        private val MJPEG_FRAME_720P = UvcFrameConfig(
            dirName = "f3_720p", width = 1280, height = 720,
            minBitRate = 29491200L, maxBitRate = 221184000L, maxBufSize = 1843200L,
            defaultInterval = 333333L, intervals = listOf(166666L, 333333L, 666666L)
        )
        private val MJPEG_FRAME_1080P = UvcFrameConfig(
            dirName = "f4_1080p", width = 1920, height = 1080,
            minBitRate = 66355200L, maxBitRate = 497664000L, maxBufSize = 4147200L,
            defaultInterval = 333333L, intervals = listOf(166666L, 333333L, 666666L)
        )
        private val MJPEG_FRAME_1440P = UvcFrameConfig(
            dirName = "f5_1440p", width = 2560, height = 1440,
            minBitRate = 117964800L, maxBitRate = 884736000L, maxBufSize = 7372800L,
            defaultInterval = 333333L, intervals = listOf(166666L, 333333L, 666666L)
        )
        private val MJPEG_FRAME_4K = UvcFrameConfig(
            dirName = "f6_4k", width = 3840, height = 2160,
            minBitRate = 265420800L, maxBitRate = 530841600L, maxBufSize = 16588800L,
            defaultInterval = 333333L, intervals = listOf(333333L, 666666L)
        )

        private val YUYV_FRAME_360P = UvcFrameConfig(
            dirName = "f1_360p", width = 640, height = 360,
            minBitRate = 36864000L, maxBitRate = 110592000L, maxBufSize = 460800L,
            defaultInterval = 333333L, intervals = listOf(333333L, 666666L, 1000000L)
        )
        private val YUYV_FRAME_480P = UvcFrameConfig(
            dirName = "f2_480p", width = 640, height = 480,
            minBitRate = 49152000L, maxBitRate = 147456000L, maxBufSize = 614400L,
            defaultInterval = 333333L, intervals = listOf(333333L, 666666L, 1000000L)
        )
        private val YUYV_FRAME_720P = UvcFrameConfig(
            dirName = "f3_720p", width = 1280, height = 720,
            minBitRate = 73728000L, maxBitRate = 221184000L, maxBufSize = 1843200L,
            defaultInterval = 666666L, intervals = listOf(666666L, 1000000L, 2000000L)
        )
        private val YUYV_FRAME_1080P = UvcFrameConfig(
            dirName = "f4_1080p", width = 1920, height = 1080,
            minBitRate = 165888000L, maxBitRate = 497664000L, maxBufSize = 4147200L,
            defaultInterval = 2000000L, intervals = listOf(2000000L)
        )

        private fun generateFrameCommands(formatSubpath: String, frames: List<UvcFrameConfig>, defaultFrameIndex: Int): String {
            return buildString {
                for (f in frames) {
                    val p = "\${UVC}/$formatSubpath/${f.dirName}"
                    val intervalsFormatted = f.intervals.joinToString("""\n""") + """\n"""
                    appendLine("            mkdir -p \"$p\"")
                    appendLine("            echo ${f.width} > \"$p/wWidth\"")
                    appendLine("            echo ${f.height} > \"$p/wHeight\"")
                    appendLine("            echo ${f.minBitRate} > \"$p/dwMinBitRate\"")
                    appendLine("            echo ${f.maxBitRate} > \"$p/dwMaxBitRate\"")
                    appendLine("            echo ${f.maxBufSize} > \"$p/dwMaxVideoFrameBufferSize\"")
                    appendLine("            echo ${f.defaultInterval} > \"$p/dwDefaultFrameInterval\"")
                    appendLine("            printf \"$intervalsFormatted\" > \"$p/dwFrameInterval\"")
                }
                appendLine("            echo $defaultFrameIndex > \"\${UVC}/$formatSubpath/bDefaultFrameIndex\" 2>/dev/null || true")
            }
        }

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

        // Apply base rw permissions and ioctl/map permissions both as sets and individually for maximum magiskpolicy/ksud compatibility
        private val SELINUX_POLICIES = listOf(
            "allow appdomain device chr_file { getattr open read write }",
            "allow appdomain device chr_file ioctl",
            "allow appdomain device chr_file map",
            "allow appdomain video_device chr_file { getattr open read write }",
            "allow appdomain video_device chr_file ioctl",
            "allow appdomain video_device chr_file map",
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
