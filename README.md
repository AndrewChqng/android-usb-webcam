# Android USB Webcam Client (UVC Gadget)

[![Android CI](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-green.svg)](https://developer.android.com)
[![Package](https://img.shields.io/badge/Package-com.andrewchqng.usbwebcam-purple.svg)](https://github.com/AndrewChqng/android-usb-webcam)
[![Kernel](https://img.shields.io/badge/Kernel-CONFIG__USB__CONFIGFS__F__UVC-blue.svg)](https://kernel.org)
[![License](https://img.shields.io/badge/License-GPLv3-yellow.svg)](LICENSE)

A root-enabled Android application (`com.andrewchqng.usbwebcam`) that exposes your phone's physical cameras (via Camera2 API) as a hardware-level **USB Video Class (UVC 1.1)** webcam (`/dev/videoX`) directly over Linux ConfigFS.

Because it presents itself as a true standard USB webcam, it requires **zero drivers or companion software** on the host computer. It works out-of-the-box with **Linux, Windows, macOS, ChromeOS**, and applications like **OBS Studio, Zoom, Google Meet, Microsoft Teams, Discord, ffplay, and WebRTC browsers**.

Forked and repurposed from the ConfigFS and `libsu` foundation of [`Arian04/android-hid-client`](https://github.com/Arian04/android-hid-client).

---

## 📱 Tested Hardware & Device Compatibility

### ✅ Verified & Tested On
- **OnePlus 12 (`CPH2581`)**
  - **SoC / USB Controller**: Qualcomm Snapdragon 8 Gen 3 (`SM8650`, `pineapple`) / Synopsys DWC3 (`a600000.dwc3`)
  - **OS & Kernel**: OxygenOS 14 (Android 14), GKI Kernel `6.1.68-android14-11`
  - **Root Manager**: KernelSU (`ksud`)
  - **Verified Streaming Modes (into Linux x86_64 host via `uvcvideo` / V4L2 / PipeWire with simultaneous ADB active)**:
    - `1920×1080` MJPEG @ 30 fps (~41 KB/frame)
    - `1280×720` MJPEG @ 30 fps (~16.6 KB/frame)
    - `640×480` Uncompressed YUYV @ ~24 fps (614,400 B/frame)

### 🔮 Likely Compatible Devices
Any rooted Android 8.0+ (API 26+) device whose kernel has **`CONFIG_USB_CONFIGFS=y`** and **`CONFIG_USB_CONFIGFS_F_UVC=y`** enabled:

| Device Category | Examples | Compatibility Notes |
| :--- | :--- | :--- |
| **Android 13 / 14 / 15+ GKI 2.0 Devices (Kernel 5.10, 5.15, 6.1, 6.6+)** | **OnePlus** 11 / 12 / 13 / Open<br>**Google Pixel** 6 / 7 / 8 / 9 series<br>**Samsung Galaxy** S22 / S23 / S24 series<br>**Xiaomi** 12 / 13 / 14 series<br>**Nothing Phone** (1) / (2)<br>**ASUS** ROG Phone / Zenfone, **Motorola** Edge | **High / Out-of-the-box**: Google mandated `CONFIG_USB_CONFIGFS_F_UVC=y` in Android Generic Kernel Image (GKI) trees to support Android 14's UVC gadget subsystem. Even on OEM ROMs (like OxygenOS, One UI, HyperOS) that hide or omit Google's system webcam service, the underlying `f_uvc` kernel driver is compiled in and works with this app. |
| **Older Devices (Android 8.0 – 12, Kernel 4.9 / 4.14 / 4.19 / 5.4)** | OnePlus 6 / 7 / 8 / 9, Pixel 3 / 4 / 5, Xiaomi Mi 9 / 10 / 11, Samsung S9 / S10 / S20 / S21 | **Kernel-dependent**: Works if the stock kernel or a custom kernel (e.g., LineageOS, NetHunter, Sultan, Kirisakura) includes `CONFIG_USB_CONFIGFS_F_UVC=y`. Use the in-app **Troubleshooting** screen to check your kernel config in one tap. |
| **Root Solutions** | **KernelSU** (`ksud`), **Magisk** (`magiskpolicy`), **APatch** (`apd`) | Automatically detects your active root manager to run ConfigFS setup and inject live SELinux policies (`video_device` / `device` `chr_file`). |
| **Host Operating Systems** | **Linux** (`uvcvideo` / V4L2 / PipeWire)<br>**Windows 10 / 11** (`usbvideo.sys`)<br>**macOS** & **ChromeOS** | **100% Plug-and-Play**: Uses standard UVC 1.1 descriptors with IAD (`0xEF / 0x02 / 0x01`). No host drivers or ADB forwarding required. |

---

## ⚡ Quick Start

1. **Prerequisites**:
   - A rooted Android device running **Magisk**, **KernelSU**, or **APatch**.
   - Kernel with USB ConfigFS and UVC support enabled (`CONFIG_USB_CONFIGFS=y` and `CONFIG_USB_CONFIGFS_F_UVC=y`).
2. **Install & Permissions**:
   - Install the APK and grant **Root** privileges when prompted by your root manager.
   - Grant **Camera** permission.
3. **Start Streaming**:
   - Connect your phone to your computer with a USB cable.
   - Tap **"Start USB Webcam"**.
   - Open your favorite camera software on your computer (e.g. `ffplay /dev/video0`, OBS Studio, Windows Camera, or Photo Booth) and select the USB camera!

---

## 🚀 Key Features & Highlights

- **Standard Hardware UVC 1.1 Emulation**:
  - Exposes USB Video Class descriptors via ConfigFS (`functions/uvc.0`), recognized natively by OS-level UVC drivers (`uvcvideo` on Linux, `usbvideo.sys` on Windows, and macOS UVC class drivers).
- **Dual Stream Formats & Resolution Profiles**:
  - **MJPEG (`streaming/mjpeg/m`)**:
    - `1080p` (1920×1080) @ 60, 30, 15 fps
    - `720p` (1280×720) @ 60, 30, 15 fps
    - `360p` (640×360) @ 60, 30, 15 fps
  - **Uncompressed YUYV (`streaming/uncompressed/u`)**:
    - `720p` (1280×720) @ 15, 10 fps
    - `480p` (640×480) @ 30, 15, 10 fps
    - `360p` (640×360) @ 30, 15, 10 fps
- **Safe Composite USB & DWC3 Endpoint Management**:
  - Automatically unlinks endpoint-heavy OEM vendor functions (such as `ffs.mtp`, `ss_acm.0`, `ss_mon.mtp`) while the webcam is active to prevent USB controller TX FIFO allocation failures and endpoint exhaustion on Qualcomm DWC3 / Synopsys USB cores.
  - Maintains `uvc.0` + `ffs.adb` active concurrently (or pure UVC-only mode if selected in Settings) and automatically restores your original USB configuration when you tap **Stop**.
- **Native C++ V4L2 Control & Streaming Engine (`uvc_gadget.cpp`)**:
  - Implements the complete UVC userspace state machine responding to `UVC_EVENT_SETUP` (`VS_PROBE_CONTROL` / `VS_COMMIT_CONTROL` via `UVCIOC_SEND_RESPONSE`), `UVC_EVENT_DATA`, `UVC_EVENT_STREAMON`, and `UVC_EVENT_STREAMOFF`.
  - Zero-IPC, zero-GC memory pipeline: operates on `V4L2_MEMORY_MMAP` output buffers, executing native `-O3` YUV420 planar crop, scale, rotation, mirroring, and ARM NEON-accelerated JPEG compression directly into native buffers.
- **Battery-Saving Headless Streaming**:
  - Operates inside an Android Foreground Service (`foregroundServiceType="camera"`) holding a partial wake lock, allowing continuous high-framerate streaming with the phone display powered completely off.
  - Viewfinder preview is disabled by default to eliminate GPU, composition, and display power draw, but can be enabled on-demand for framing.
- **Rich Camera & Lens Controls**:
  - Switch between any physical sensor (rear main, ultra-wide, telephoto, front-facing camera).
  - On-the-fly orientation adjustment (0°, 90°, 180°, 270°) and horizontal mirroring.
  - Real-time MJPEG quality slider (50% – 95%) and live FPS / format telemetry.

---

## 🛠️ Architecture & How It Works

### Comparison: HID Client vs. UVC Webcam Client

| Component | `android-hid-client` (Upstream) | `android-usb-webcam` (This Fork) |
| :--- | :--- | :--- |
| **Kernel Gadget** | `functions/hid.keyboard` & `functions/hid.touchpad` | `functions/uvc.0` (Control + MJPEG & YUYV Streaming) |
| **Exposed Device** | Character devices `/dev/hidg0`, `/dev/hidg1` | V4L2 Video Output node `/dev/videoX` |
| **Data Flow** | Unidirectional raw HID byte reports (keys / pointer) | Bidirectional UVC control negotiation + continuous high-speed video frames |
| **SELinux Context** | `allow appdomain device chr_file { getattr open rw }` | `allow appdomain { device video_device } chr_file { getattr open rw ioctl map }` |
| **Native Engine** | Userspace `uhid` character writes | Complete V4L2 UVC gadget event loop & buffer streamer (`uvc_gadget.cpp`) |
| **Input Source** | Touchpad gestures & keyboard input views | Android Camera2 API hardware capture pipeline |

```mermaid
flowchart TD
    subgraph Host["Host PC (Linux / Windows / macOS)"]
        HostApp["Webcam App (OBS, Zoom, ffplay)"]
        HostDriver["UVC Class Driver (uvcvideo / usbvideo)"]
        HostApp <--> HostDriver
    end

    subgraph USB["Physical USB Cable (High-Speed / SuperSpeed)"]
        HostDriver <-->|"Isochronous / Bulk Transfers"| GadgetCore["DWC3 USB Controller"]
    end

    subgraph Kernel["Android Linux Kernel (Root / ConfigFS)"]
        GadgetCore <--> ConfigFS["ConfigFS (/config/usb_gadget/g1)"]
        ConfigFS --> FUVC["f_uvc Driver (functions/uvc.0)"]
        FUVC --> V4L2Node["/dev/videoX (V4L2 Output Node)"]
    end

    subgraph App["USB Webcam App (com.andrewchqng.usbwebcam)"]
        DevMgr["CharacterDeviceManager (ConfigFS, SELinux & Perms)"]
        JNI["uvc_gadget.cpp (Native V4L2 Event Loop)"]
        CamCtrl["WebcamController (Camera2 API)"]
        FGS["WebcamForegroundService (WakeLock & Background)"]

        DevMgr -->|"1. Setup ConfigFS & UDC"| ConfigFS
        DevMgr -->|"2. Detect node & patch SELinux"| V4L2Node
        JNI <-->|"3. ioctl UVCIOC_SEND_RESPONSE & mmap VIDIOC_QBUF"| V4L2Node
        CamCtrl -->|"4. YUV420 Frames (NEON convert & JPEG compress)"| JNI
        FGS --> CamCtrl
    end
```

---

## 🧩 Technical Deep Dive & Kernel Quirks Handled

### 1. ConfigFS Descriptor Configuration & `bFrameIndex` Ordering
The app manages the ConfigFS hierarchy under `/config/usb_gadget/g1/functions/uvc.0`:
- **Alphabetical `d_subdirs` Frame Index Requirement**: On Linux 6.1+ `f_uvc`, `uvc_function_bind()` iterates frame subdirectories in `d_subdirs` (alphabetical) order and validates that `bFrameIndex` increments sequentially (`1, 2, 3`). Naming directories `360p`, `720p`, `1080p` causes `1080p` (`bFrameIndex=3`) to sort first alphabetically and fail binding with `-EINVAL`. Frame directories are therefore named `f1_360p`, `f2_720p`, `f3_1080p` (MJPEG) and `f1_360p`, `f2_480p`, `f3_720p` (YUYV) so alphabetical order always matches creation order.
- **Clean Re-binding Across Sessions**: `f_uvc` locks header reference counts (`opts->header->refcnt`) once bound. On every Start/Stop cycle, all class/header symlinks are removed and `uvc.0` subdirectories are removed with `rmdir` before recreating fresh descriptors.
- **Preventing `init` / `adbd` UDC Race Conditions**: When `ffs.adb` is active, writing `""` to `/config/usb_gadget/g1/UDC` triggers Android `init` (`sys.usb.ffs.ready=1`) to immediately re-bind `UDC` and link `os_desc/b.1 -> configs/b.1`. Because `config_usb_cfg_link()` rejects linking `uvc.0` into `configs/b.1` while `UDC` or `os_desc/b.1` is active, `CharacterDeviceManager` prepares all `uvc.0` descriptors first and unbinds `UDC` + `os_desc/b.1` immediately prior to `ln -s functions/uvc.0 configs/b.1/uvc.0`.

### 2. DWC3 FIFO & Endpoint Optimization
Many OEM ROMs link multiple USB functions by default (`ffs.mtp`, `ss_acm.0`, `ffs.adb`, `ss_mon.mtp`), exhausting hardware endpoints on Synopsys DesignWare Cores (DWC3). When active, the app:
1. Saves the existing configuration state into `/data/local/tmp/usb_webcam_saved_links.txt`.
2. Temporarily unlinks MTP, ACM, and vendor monitor endpoints from `configs/b.1/`.
3. Links `functions/uvc.0` and `functions/ffs.adb`.
4. Re-binds the UDC (USB Device Controller) and restores all original vendor symlinks on Stop.

### 3. V4L2 Gadget Node Auto-Discovery & SELinux
When `f_uvc` binds to the UDC, the kernel creates a new V4L2 output node in `/sys/class/video4linux/video*`. `CharacterDeviceManager` scans `/sys/class/video4linux/` for nodes whose sysfs target references the active USB gadget / UDC controller, applies `chown` to the application UID, sets permissions to `0660`, and injects live SELinux policies (`allow appdomain { device video_device } chr_file { getattr open read write ioctl map }`) via `magiskpolicy`, `ksud`, or `apd`.

### 4. Native V4L2 Event Handling & Frame Pipeline (`uvc_gadget.cpp`)
Userspace communication with the `f_uvc` kernel driver uses standard V4L2 event notifications:
1. **Probe & Commit Negotiation**: Listens for `UVC_EVENT_SETUP` via `VIDIOC_DQEVENT`. When the host sends `UVC_GET_CUR`, `UVC_GET_MIN`, `UVC_GET_MAX`, or `UVC_SET_CUR` for `VS_PROBE_CONTROL` / `VS_COMMIT_CONTROL`, the native daemon negotiates format and framerate and responds via `ioctl(fd, UVCIOC_SEND_RESPONSE)`.
2. **Stream Lifecycle**:
   - `UVC_EVENT_STREAMON`: Configures V4L2 format (`VIDIOC_S_FMT`), allocates 4 `V4L2_MEMORY_MMAP` buffers (`VIDIOC_REQBUFS`), primes buffer `0` with a startup frame (leaving buffers `1..3` free for immediate Camera2 frames), and initiates output streaming (`VIDIOC_STREAMON`).
   - `UVC_EVENT_STREAMOFF`: Stops the stream (`VIDIOC_STREAMOFF`), unmaps buffers, and enters standby.
3. **Zero-Copy Frame Processing**:
   - Drains completed V4L2 buffers via non-blocking `VIDIOC_DQBUF` and acquires free buffer slots round-robin.
   - Converts Camera2 `YUV_420_888` planes (handling arbitrary row and pixel strides) into packed `YUYV` or compressed `NV21` with SIMD acceleration.
   - For MJPEG: compresses via Android's hardware-accelerated Skia `libjpeg-turbo` directly into pre-allocated direct byte buffers with zero heap allocation.
   - Queues populated buffers with monotonic timestamps using `VIDIOC_QBUF`.

---

## 🛠️ Building from Source

### Requirements
- Linux or macOS development environment
- JDK 17 or JDK 21
- Android SDK (API 36) & NDK 28.2+
- CMake 3.22.1+

### Build Steps
```bash
# Clone the repository
git clone https://github.com/AndrewChqng/android-usb-webcam.git
cd android-usb-webcam

# Build Debug & Release APKs
./gradlew assembleDebug assembleRelease

# Output APK locations:
# Debug:   app/build/outputs/apk/debug/app-debug.apk
# Release: app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## 🔍 Troubleshooting

- **Check Kernel Config**:
  Open the in-app **Troubleshooting** screen to verify that your kernel has `CONFIG_USB_CONFIGFS=y` and `CONFIG_USB_CONFIGFS_F_UVC=y`.
- **Root Permissions**:
  Ensure root access is granted in Magisk, KernelSU, or APatch. The app requires root to configure ConfigFS and apply SELinux rules.
- **Pure UVC Mode**:
  If the host PC fails to enumerate the camera or if USB endpoint allocation fails, open **Settings** and toggle **"Pure UVC-only mode"** (which unlinks ADB while the webcam is active to maximize available USB bandwidth).
- **Packet Size Compatibility**:
  If using an older USB 2.0 host controller or hub that struggles with high-bandwidth transfers, try switching **"UVC Isochronous Max Packet Size"** in Settings from `3072` to `1024`.

---

## 📜 Credits & Disclaimer

- **Base Architecture & UI Framework**: Based on [`Arian04/android-hid-client`](https://github.com/Arian04/android-hid-client) (GPL-3.0).
- **Repurposing & Implementation**: Repurposed from USB HID (keyboard/mouse) into USB Video Class (UVC) webcam with architecture, ConfigFS engineering, native V4L2 event engine, and Camera2 streaming pipeline developed in collaboration with **Antigravity (Google DeepMind)**.
- **Disclaimer**: *This project contains AI-assisted experimental code that directly configures Linux kernel USB gadgets, manipulates character devices, and modifies runtime SELinux policies as root. It is intended for advanced users and developers. Use at your own risk.*

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. See [LICENSE](LICENSE) for details.
