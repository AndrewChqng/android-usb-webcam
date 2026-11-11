# Changelog

All notable changes, development milestones, and releases for **Android USB Webcam Client** (`com.andrewchqng.usbwebcam`) are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [v3.2.0] - 2026-11-11

### Added
- **Dynamic Camera2 Sensor Resolution Discovery**: Dynamically scans sensor capabilities via `CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP` to populate all supported hardware resolutions and generate corresponding vertical portrait companions.
- **Aspect Ratio Filtering**: Filter available resolutions by `16:9`, `9:16` (Vertical), `4:3`, `1:1`, or `Other` in both the Compose dialog and Web Remote dashboard.
- **Unconstrained Resolution Retention**: Removed arbitrary low-resolution drop thresholds; all valid resolutions down to technical macroblock limits ($\ge 16\times16$, even pixel parity for YUV/YUYV) such as 176×144 QCIF, 320×240 QVGA, and 352×288 CIF are fully accessible.
- **Custom Resolution Entry**: Add custom $W \times H$ dimensions dynamically with real-time validation and deletion support.
- **Default Resolution Picker**: Select default startup resolution (`bDefaultFrameIndex`) presented to the USB host upon enumeration.
- **Alphabetical ConfigFS Directory Formatting**: Generates two-digit zero-padded directory names (`f%02d_%dx%d`) to ensure Linux kernel `f_uvc` enumerates frame descriptors strictly in numerical index order.
- **Dynamic C++ Frame Tables**: `uvc_gadget.cpp` and `UvcNative` accept dynamic width/height arrays up to 16 frames with zero-copy memory buffers.
- **Embedded Web Remote Resolution Management**: Added collapsible resolution set manager to the Web Remote interface with aspect ratio filtering, checkbox selection, default resolution radio indicator, and custom dimension entry.
- **Project Documentation**: Added [`learnings.md`](learnings.md) detailing Linux ConfigFS nuances, Qualcomm DWC3 IRQ quirks, and zero-tear buffer synchronization; added [`agent.md`](agent.md) maintainer operations guide.

### Changed
- **Adaptive Sensor Resolution Matching**: Optimized `WebcamController.chooseOptimalSize` to select the smallest sufficient sensor capture size for low-resolution streams, eliminating unnecessary CPU downscaling, reducing ISP memory bandwidth, and saving battery.
- Bumped app `versionCode` to `320` and `versionName` to `v3.2.0`.

---

## [v3.1.4-test] - 2026-11-11

### Added
- **Resolution Configuration Profiles**: Introduced preset resolution profiles (`Max 1080p`, `Max 1440p 2K`, `Max 4K UHD`, `Max 720p HD`, and forced single-resolution modes `Only 1080p`, `Only 720p`, `Only 4K`).
- Native C++ format descriptor tables expanded to support 1440p QHD (`2560×1440`) and 4K UHD (`3840×2160`).

### Fixed
- **ConfigFS Bash Parameter Expansion Bug**: Fixed a critical shell script bug where writing `${UVC/streaming/mjpeg/m}` was interpreted by Bash as substring substitution, stripping `/streaming` and preventing the UVC function from attaching to `configs/b.1`. Updated to explicit path concatenation `${UVC}/$subpath`.

---

## [v3.1.3-test] - 2026-11-10

### Fixed
- **Release Build Hardening**: Added explicit Proguard `-keep` rules for native JNI callbacks in `proguard-rules.pro` to prevent R8 from stripping `UvcEventCallback` methods in minified release builds.
- **Release Signing Fallback**: Configured release build signing in `app/build.gradle.kts` to fall back to the Android debug keystore (`~/.android/debug.keystore`), allowing reproducible standalone release builds without missing keystore errors.
- **JNI Exception Safety**: Hardened JNI callback dispatches with `ExceptionClear()` checks in `uvc_gadget.cpp` to prevent native thread crashes if a Kotlin UI callback throws.

---

## [v3.1.2-test] - 2026-11-10

### Added
- **Embedded Web Remote Control**: Integrated local HTTP/JSON web server (`WebcamWebServer.kt`) running on port `8080`.
- **Browser Dashboard**: Modern dark-mode web interface for controlling zoom, pan/tilt, exposure compensation, white balance, torch, JPEG quality, resolution profiles, and camera lens switching from a PC browser via ADB port forwarding (`adb forward tcp:8080 tcp:8080`).
- **Digital Pan & Tilt (PTZ)**: Dynamic pan and tilt crop controls for digital zoom.
- **C++ LUT Scaler Pan/Tilt Mapping**: Implemented bilinear coordinate mapping in native C++ (`buildCoordinateLuts`) to bypass Camera2 HAL `CENTER_ONLY` zoom restriction.
- **Live Stream Telemetry**: Real-time HUD displaying measured FPS, frame latency (ms), frame size (KB), USB bus utilization, and bound V4L2 node.

---

## [v3.1.1-test] - 2026-11-10

### Fixed
- **V4L2 1-Buffer Black Frame Deadlock**: Resolved a race condition where priming a single V4L2 buffer caused streaming deadlock if the host paused reads.
- **Zero-Tear Buffer Synchronization**: Added `UvcNative.nativeCanAcceptFrame(1)` checks before Camera2 frame encoding to ensure V4L2 kernel buffers are fully drained, preventing frame tearing and USB FIFO buffer bloat.
- **Qualcomm DWC3 Threaded IRQ Priority Elevation**: Elevated `[irq/<N>-dwc3]` threads to real-time `SCHED_FIFO 90` and pinned IRQ affinity to big performance cores (CPU 2-3), eliminating `-EXDEV` isochronous microframe drops and `UVC_QUEUE_DROP_INCOMPLETE` truncated frames on Snapdragon 8 Gen 3.
- **Linux Host Compatibility**: Defaulted exposed formats to `Both` (MJPEG + YUYV) for compatibility with Linux V4L2 clients (e.g. Kamoso, PipeWire, OBS).
- **Quality & Latency Controls**: Added live JPEG quality slider (50–100%) and in-flight buffer queue depth selector in settings.

---

## [v3.1.0] - 2026-11-10

### Added
- **Project Repurposing**: Forked from [`Arian04/android-hid-client`](https://github.com/Arian04/android-hid-client) and repurposed the ConfigFS foundation from USB HID (keyboard/mouse) into a full **USB Video Class (UVC 1.1)** webcam.
- **Kernel ConfigFS UVC Engine**: Direct root shell creation of `/config/usb_gadget/g1/functions/uvc.0` with standard isochronous high-bandwidth streaming endpoints (`3072` bytes/packet, `mult = 2`).
- **Automated SELinux Policy Injection**: Real-time policy injection for Magisk (`magiskpolicy`), KernelSU (`ksud`), and APatch (`apd`) granting untrusted app access to `/dev/videoX` character devices.
- **Native C++ V4L2 Engine**: High-performance multi-threaded C++ event loop (`uvc_gadget.cpp`) handling UVC probe/commit negotiation, streaming events (`UVC_EVENT_SETUP`, `UVC_EVENT_DATA`, `UVC_EVENT_STREAMON`, `UVC_EVENT_STREAMOFF`), and non-blocking `VIDIOC_QBUF`/`VIDIOC_DQBUF` buffer queues.
- **Camera2 Streaming Pipeline**: Foreground service capturing `ImageFormat.YUV_420_888` frames with NEON-accelerated YUYV packing and hardware-accelerated Skia `libjpeg-turbo` compression.
- **Jetpack Compose UI**: Clean modern Material 3 interface with live camera viewfinder, lens switcher (front, back, ultrawide, telephoto), ISP controls, and hardware diagnostics.
