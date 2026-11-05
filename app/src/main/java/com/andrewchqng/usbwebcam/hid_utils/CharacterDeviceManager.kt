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

        val script = """
            G="$gPath"
            [ -d "${'$'}G" ] || G="/config/usb_gadget/g1"
            CFG="${'$'}G/configs/b.1"
            UVC="${'$'}G/functions/uvc.0"
            BACKUP="/data/local/tmp/usb_webcam_saved_links.txt"

            # Save original non-UVC function symlinks once
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
                    for f in "${'$'}CFG"/*; do
                        [ -L "${'$'}f" ] || continue
                        t=${'$'}(readlink -f "${'$'}f" 2>/dev/null)
                        echo "LINK|${'$'}f|${'$'}t" >> "${'$'}BACKUP"
                    done
                fi
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

            mkdir -p "${'$'}UVC/streaming/mjpeg/m/f1_360p" "${'$'}UVC/streaming/mjpeg/m/f2_720p" "${'$'}UVC/streaming/mjpeg/m/f3_1080p"
            echo 640 > "${'$'}UVC/streaming/mjpeg/m/f1_360p/wWidth"
            echo 360 > "${'$'}UVC/streaming/mjpeg/m/f1_360p/wHeight"
            echo 18432000 > "${'$'}UVC/streaming/mjpeg/m/f1_360p/dwMinBitRate"
            echo 110592000 > "${'$'}UVC/streaming/mjpeg/m/f1_360p/dwMaxBitRate"
            echo 460800 > "${'$'}UVC/streaming/mjpeg/m/f1_360p/dwMaxVideoFrameBufferSize"
            echo 333333 > "${'$'}UVC/streaming/mjpeg/m/f1_360p/dwDefaultFrameInterval"
            printf "166666\n333333\n666666\n" > "${'$'}UVC/streaming/mjpeg/m/f1_360p/dwFrameInterval"

            echo 1280 > "${'$'}UVC/streaming/mjpeg/m/f2_720p/wWidth"
            echo 720 > "${'$'}UVC/streaming/mjpeg/m/f2_720p/wHeight"
            echo 29491200 > "${'$'}UVC/streaming/mjpeg/m/f2_720p/dwMinBitRate"
            echo 221184000 > "${'$'}UVC/streaming/mjpeg/m/f2_720p/dwMaxBitRate"
            echo 1843200 > "${'$'}UVC/streaming/mjpeg/m/f2_720p/dwMaxVideoFrameBufferSize"
            echo 333333 > "${'$'}UVC/streaming/mjpeg/m/f2_720p/dwDefaultFrameInterval"
            printf "166666\n333333\n666666\n" > "${'$'}UVC/streaming/mjpeg/m/f2_720p/dwFrameInterval"

            echo 1920 > "${'$'}UVC/streaming/mjpeg/m/f3_1080p/wWidth"
            echo 1080 > "${'$'}UVC/streaming/mjpeg/m/f3_1080p/wHeight"
            echo 66355200 > "${'$'}UVC/streaming/mjpeg/m/f3_1080p/dwMinBitRate"
            echo 497664000 > "${'$'}UVC/streaming/mjpeg/m/f3_1080p/dwMaxBitRate"
            echo 4147200 > "${'$'}UVC/streaming/mjpeg/m/f3_1080p/dwMaxVideoFrameBufferSize"
            echo 333333 > "${'$'}UVC/streaming/mjpeg/m/f3_1080p/dwDefaultFrameInterval"
            printf "166666\n333333\n666666\n" > "${'$'}UVC/streaming/mjpeg/m/f3_1080p/dwFrameInterval"
            echo 0x04 > "${'$'}UVC/streaming/mjpeg/m/bmaControls" 2>/dev/null || true

            mkdir -p "${'$'}UVC/streaming/uncompressed/u/f1_360p" "${'$'}UVC/streaming/uncompressed/u/f2_480p" "${'$'}UVC/streaming/uncompressed/u/f3_720p"
            echo 640 > "${'$'}UVC/streaming/uncompressed/u/f1_360p/wWidth"
            echo 360 > "${'$'}UVC/streaming/uncompressed/u/f1_360p/wHeight"
            echo 36864000 > "${'$'}UVC/streaming/uncompressed/u/f1_360p/dwMinBitRate"
            echo 110592000 > "${'$'}UVC/streaming/uncompressed/u/f1_360p/dwMaxBitRate"
            echo 460800 > "${'$'}UVC/streaming/uncompressed/u/f1_360p/dwMaxVideoFrameBufferSize"
            echo 333333 > "${'$'}UVC/streaming/uncompressed/u/f1_360p/dwDefaultFrameInterval"
            printf "333333\n666666\n1000000\n" > "${'$'}UVC/streaming/uncompressed/u/f1_360p/dwFrameInterval"

            echo 640 > "${'$'}UVC/streaming/uncompressed/u/f2_480p/wWidth"
            echo 480 > "${'$'}UVC/streaming/uncompressed/u/f2_480p/wHeight"
            echo 49152000 > "${'$'}UVC/streaming/uncompressed/u/f2_480p/dwMinBitRate"
            echo 147456000 > "${'$'}UVC/streaming/uncompressed/u/f2_480p/dwMaxBitRate"
            echo 614400 > "${'$'}UVC/streaming/uncompressed/u/f2_480p/dwMaxVideoFrameBufferSize"
            echo 333333 > "${'$'}UVC/streaming/uncompressed/u/f2_480p/dwDefaultFrameInterval"
            printf "333333\n666666\n1000000\n" > "${'$'}UVC/streaming/uncompressed/u/f2_480p/dwFrameInterval"

            echo 1280 > "${'$'}UVC/streaming/uncompressed/u/f3_720p/wWidth"
            echo 720 > "${'$'}UVC/streaming/uncompressed/u/f3_720p/wHeight"
            echo 73728000 > "${'$'}UVC/streaming/uncompressed/u/f3_720p/dwMinBitRate"
            echo 221184000 > "${'$'}UVC/streaming/uncompressed/u/f3_720p/dwMaxBitRate"
            echo 1843200 > "${'$'}UVC/streaming/uncompressed/u/f3_720p/dwMaxVideoFrameBufferSize"
            echo 666666 > "${'$'}UVC/streaming/uncompressed/u/f3_720p/dwDefaultFrameInterval"
            printf "666666\n1000000\n2000000\n" > "${'$'}UVC/streaming/uncompressed/u/f3_720p/dwFrameInterval"

            mkdir -p "${'$'}UVC/streaming/header/h"
            ln -s "${'$'}UVC/streaming/mjpeg/m" "${'$'}UVC/streaming/header/h/m" 2>/dev/null || true
            ln -s "${'$'}UVC/streaming/uncompressed/u" "${'$'}UVC/streaming/header/h/u" 2>/dev/null || true
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
                for p in ${'$'}(ps -A -o pid,comm | grep "irq/.*dwc3" | awk '{print ${'$'}1}'); do
                    chrt -f -p "${'$'}p" 50 2>/dev/null || true
                    renice -n -20 -p "${'$'}p" 2>/dev/null || true
                done
            fi
        """.trimIndent()

        val res = Shell.cmd(script).exec()
        if (!res.isSuccess) {
            Timber.w("createUvcGadgetViaRootShell warning: out=${res.out} err=${res.err}")
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
