# Agent & Maintainer Operational Guide

> **Maintainer & Agent Instructions**: This document specifies operational invariants, architectural boundaries, build procedures, and debugging workflows for autonomous agents and maintainers modifying the Android USB Webcam codebase.

---

## 1. Project Directory Architecture

```
android-usb-webcam/
├── app/
│   ├── src/main/
│   │   ├── cpp/                     # High-performance native C++ layer
│   │   │   ├── uvc_gadget.h         # V4L2/UVC ioctls, format descriptors, frame structs
│   │   │   └── uvc_gadget.cpp       # V4L2 streaming event loop, Bilinear scaler & PTZ mapper
│   │   ├── java/com/andrewchqng/usbwebcam/
│   │   │   ├── hid_utils/           # Linux ConfigFS driver & RootService IPC
│   │   │   │   ├── CharacterDeviceManager.kt # ConfigFS gadget builder, IRQ booster
│   │   │   │   └── UsbGadgetService.kt       # libsu RootService IPC daemon
│   │   │   ├── settings/            # Preferences & Resolution models
│   │   │   │   ├── ResolutionItem.kt         # Sensor resolution discovery & aspect ratios
│   │   │   │   ├── ResolutionManagerDialog.kt# Compose checklist & aspect ratio filter dialog
│   │   │   │   └── UserPreferencesRepository.kt # SharedPreferences & Gadget preferences
│   │   │   ├── webcam/              # Camera2 & UVC orchestration
│   │   │   │   ├── UvcNative.kt              # JNI external bridge
│   │   │   │   ├── WebcamController.kt       # Camera2 capture session & format coordinator
│   │   │   │   └── WebcamForegroundService.kt# Android foreground service host
│   │   │   ├── webserver/           # Embedded Web Remote server
│   │   │   │   └── WebcamWebServer.kt        # HTTP/JSON dashboard & control endpoints
│   │   │   └── MainScreen.kt        # Primary Jetpack Compose UI
```

---

## 2. Invariants & Rules for Agents

1. **Kernel ConfigFS Index Order**:
   - Frame directories in `streaming/mjpeg/m/` and `streaming/uncompressed/u/` must follow `f%02d_%dx%d` naming format.
   - Linux `f_uvc` enumerates subdirectories strictly in alphabetical order. Failure to pad with two digits scrambles `bFrameIndex` descriptors.
   - Max frames supported by `UvcFormatDesc.frames` in `uvc_gadget.h` is **16**.

2. **Bash Variable Interpolation in `Shell.cmd()`**:
   - Never write `${UVC/subpath...}`. Bash parses `${VAR/pattern/replacement}` as substring replacement and truncates the path.
   - Always write `"${UVC}/$subpath"`.

3. **Webcam Web Server JavaScript Strings**:
   - `INDEX_HTML` inside `WebcamWebServer.kt` is a Kotlin multiline string `"""..."""`.
   - Any raw JavaScript template literals using `${...}` will be intercepted by Kotlin's string interpolation compiler.
   - Always use standard JavaScript string concatenation `+` or escape with `${'$'}{...}`.

4. **Git Commits & Authorship**:
   - Always append `Co-authored-by: Antigravity <noreply@google.com>` to all git commit messages.
   - Default Git author when unset: `AndrewChqng <44511577+AndrewChqng@users.noreply.github.com>`.
   - Never push or create pull requests without explicit user authorization.

5. **Privileged Host Commands**:
   - When running host commands requiring root/sudo privileges, use `pkexec` (e.g. `pkexec <cmd>`) to avoid hanging non-interactive sessions. On-device root commands use `adb shell su -c '<cmd>'` or `libsu` in Kotlin.

---

## 3. Build & Deployment Commands

### Build Release APK
```bash
export JAVA_HOME=/home/roboy/.jdks/jdk-21
export ANDROID_HOME=/home/roboy/Android/Sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleRelease
```
*Output APK location*: `app/build/outputs/apk/release/app-release.apk`

### Install over ADB Wi-Fi or USB
```bash
# Wi-Fi (OnePlus 12):
adb -s 192.168.0.20:5555 install -r -d app/build/outputs/apk/release/app-release.apk

# USB:
adb -s 789e7fc install -r -d app/build/outputs/apk/release/app-release.apk
```

### Inspect Kernel UVC Gadget Descriptors via Root
```bash
adb -s 192.168.0.20:5555 shell su -c 'ls -la /config/usb_gadget/g1/functions/uvc.0/streaming/mjpeg/m/'
adb -s 192.168.0.20:5555 shell su -c 'cat /config/usb_gadget/g1/functions/uvc.0/streaming/mjpeg/m/bDefaultFrameIndex'
```

---

## 4. Porting to New Devices

When porting to other Android devices:
1. Verify ConfigFS mount point: default is `/config/usb_gadget/g1`.
2. Inspect UDC controller name: `ls /sys/class/udc`.
3. Check V4L2 device node: USB UVC gadget usually attaches to `/dev/video2` or `/dev/video3`. Auto-discovery logic in `CharacterDeviceManager.discoverUvcVideoDevicePath` scans `/sys/class/video4linux/video*`.
4. Ensure kernel supports `CONFIG_USB_CONFIGFS_F_UVC=y` and `CONFIG_USB_F_UVC=y`.
