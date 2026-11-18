# Changelog

All notable changes, development milestones, and releases for **Android USB Webcam Client** (`com.andrewchqng.usbwebcam`) are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [v3.2.5] - 2026-11-18

> **AI Attribution & Disclaimer**: Implemented with assistance from **Antigravity (Google DeepMind)** via modular architecture decomposition, API modernization, and automated unit test authoring. Code is experimental; hardware interaction with Linux USB ConfigFS, DWC3 drivers, Camera2 HAL, and V4L2 requires root (`su`) and direct kernel ioctls.

### Changed & Refactored
- **Dead Code & Dependency Cleanup**: Removed legacy bound root service (`UsbGadgetService`, `UsbGadgetServiceConnection`), dropped unused `libsu-service` dependency and ProGuard rule, and extracted UVC ConfigFS frame descriptor generation into `UvcFrameConfigBuilder`.
- **Native C++ Modularization**: Split monolithic `uvc_gadget.cpp` into focused units (`uvc_gadget.h`, `uvc_protocol.cpp`, `yuv_convert.cpp`, `uvc_gadget.cpp`) wired via `CMakeLists.txt`.
- **Web Remote Asset Separation**: Extracted embedded HTML/CSS/JS out of `WebcamWebServer.kt` into `app/src/main/assets/web/` (`index.html`, `styles.css`, `app.js`), lazily loaded and served from Android assets.
- **Camera2 & Webcam Domain Decomposition**: Extracted models (`WebcamModels.kt`) and pure Camera2/PTZ/pacing/USB bus math (`Camera2ConfigHelper.kt`) out of `WebcamController.kt`, migrated API 28+ capture sessions to `SessionConfiguration`, and fixed constant-FPS range tie-breaking when sensor max FPS is below requested target FPS.
- **UI Modularization & Android 15 Hygiene**: Split `MainScreen.kt` into 6 dedicated card composables under `ui/main/`, replaced deprecated clipboard and status bar APIs, enabled `enableEdgeToEdge()`, bumped `targetSdk = 35`, and added optional `keystore.properties`/env release signing with `debug` fallback.

### Added
- **JVM Unit Test Suite**: Added JUnit unit tests covering Camera2 selection/PTZ/pacing/USB math (`Camera2ConfigHelperTest`), resolution parsing (`ResolutionItemTest`), and UVC ConfigFS generation (`UvcFrameConfigBuilderTest`).

---

## [v3.2.4] - 2026-11-18

> **AI Attribution & Disclaimer**: Implemented with assistance from **Antigravity (Google DeepMind)**. Code is experimental; hardware interaction with Linux USB ConfigFS, DWC3 drivers, Camera2 HAL, and V4L2 requires root (`su`) and direct kernel ioctls.

### Added
- **Native Camera2 API PTZ Mode (`SCALER_CROP_REGION`)**: Added hardware ISP-level pan/tilt/zoom via `CaptureRequest.SCALER_CROP_REGION` (`computeCamera2CropRegion`) with `CONTROL_ZOOM_RATIO` held at `1.0f` on API 30+, keeping native C++ conversion on the 1:1 ARM64 NEON fast path while active.
- **PTZ Mode Toggle (App UI & Web Remote)**: Added a persisted **Native Camera2 API PTZ** toggle (`use_native_camera2_ptz_v1`, default `false`) across `MainScreen` and the embedded Web Remote (`/api/settings`), letting users switch seamlessly between Camera2 hardware cropping and the zero-IPC native C++ software ROI crop.
- **HAL Crop Diagnostics**: Queries `SCALER_CROPPING_TYPE` to warn on `CENTER_ONLY` sensors and surfaces live `TotalCaptureResult.SCALER_CROP_REGION` (`HAL Crop: [left,top WxH]`) telemetry in `MainScreen` while in Camera2 PTZ mode.

---

## [v3.2.3] - 2026-11-18

> **AI Attribution & Disclaimer**: Implemented with assistance from **Antigravity (Google DeepMind)**. Code is experimental; hardware interaction with Linux USB ConfigFS, DWC3 drivers, Camera2 HAL, and V4L2 requires root (`su`) and direct kernel ioctls.

### Added
- **Full UVC & Live Frame Rate (FPS) Configuration**: Added `Max / Default Frame Rate (FPS)` setting (`60`, `30`, `24`, `15`, `10`, `5` FPS; default `30` FPS) propagated into Linux ConfigFS (`dwFrameInterval` / `dwDefaultFrameInterval`) and the native UVC control responder (`UVC_GET_MIN` / `UVC_GET_MAX` / `UVC_GET_DEF`).
- **24 FPS Live Cap & Persistence**: Added `24 FPS` target cap chip/pill on Android & Web Remote UI and persisted `targetFpsCap` and `lockConstantFps` preferences across sessions.

### Fixed & Improved
- **Solid 30 FPS Frame Pacing & V4L2 Double-Buffering**: Switched software pacing to hardware sensor exposure timestamps (`image.timestamp`) instead of post-encode `endNs`, bypassed redundant software frame drops when Camera2 hardware `CONTROL_AE_TARGET_FPS_RANGE.upper` matches `effectiveFps`, upgraded default `maxInFlightBuffers` to `2` (`max_in_flight_buffers_v2`), and overlapped MJPEG CPU encoding with USB ISOC draining.
- **UI & USB Saturation Telemetry**: Status card turns red (`errorContainer`) when USB bus utilization reaches `100%`, computes exact ISOC bus bandwidth limits across `1024`/`2048`/`3072`B packet sizes, and moves the PC Web Remote Control card to the bottom of `MainScreen`.

---

## [v3.2.2] - 2026-11-18

### Added
- **Discrete Max Resolution Slider**: Replaced preset resolution chips with a discrete 7-step **Max Resolution** slider (`240p` · `360p` · `480p` · `720p` · `1080p` · `1440p` · `2160p/4K`) across `MainScreen`, `ResolutionManagerDialog`, and the Web Remote dashboard.
- **Batch Custom Resolution Controls**: Added **Select All** and **Deselect All** buttons (respecting the 16-frame Linux UVC cap and preserving at least 1 valid frame) in both `ResolutionManagerDialog` and the Web Remote.

### Improved
- **Zero-Teardown `STREAMOFF` Debounce**: Added a 350ms grace window on `UVC_EVENT_STREAMOFF` in `WebcamController` so rapid host `STREAMOFF -> STREAMON` transitions (FPS/format switches) keep Camera2 alive for 0ms on-the-fly updates.
- **Effective FPS Software Pacing**: `processCameraFrame` paces against `effectiveFps` (`negotiatedFps` capped by `targetFpsCap`) so lower host-negotiated framerates take effect smoothly even on fixed-FPS sensors.
- **Direct 1:1 Sensor Capture & Sub-240p Removal**: Enforced `minOf(w, h) >= 240` across sensor discovery, baselines, presets, and custom validation (disallowing sub-240p QCIF streams that exhibit HAL/stride artifacts without relying on scaler downscaling workarounds).

---

## [v3.2.1] - 2026-11-18

### Performance & Latency Tuning
- **Zero-Teardown On-The-Fly Camera2 Updates**: `WebcamController` tracks `activeCameraId` and `activeCaptureSize` and updates `CaptureRequest` on-the-fly (`updateRepeatingRequestOnFly()`) instead of tearing down and reopening the Camera2 session when the same sensor stream is already active, eliminating ~1.5s video blackouts on FPS/stream renegotiation.
- **Disabled Zero-Shutter-Lag (`CONTROL_ENABLE_ZSL = false`)**: Explicitly disables ZSL reprocessing ring buffers on API 26+ to cut 60–100ms HAL pipeline latency.
- **Physical OIS over EIS**: Prefers zero-latency physical OIS (`LENS_OPTICAL_STABILIZATION_MODE_ON`) with EIS disabled when stabilization is enabled and supported, avoiding 1–3 frame gyro-warp latency and FOV crop.
- **Reduced `ImageReader` Queue**: Lowered `ImageReader` buffer count from `4` to `3` alongside `acquireLatestImage()`.
- **PM QoS DMA Latency Lock**: Acquires `/dev/cpu_dma_latency = 0` on `VIDIOC_STREAMON` (when accessible) and releases it on `VIDIOC_STREAMOFF` to prevent deep CPU C-state wakeup jitter.
- **MJPEG `sizeimage` Clamp**: Clamps MJPEG V4L2 buffer sizing to `min(rawBytes, max(rawBytes / 2, 2MB))` to reduce locked kernel buffer memory at 1440p/4K.

### ARM64 NEON Vectorization
- **8×8 NEON Register Tile Transposition (`transpose8x8_u8`)**: Vectorizes 90° and 270° luma rotation in `nativeYuv420ToNv21` using `vtrn_u8`/`vtrn_u16`/`vtrn_u32` (and `vrev64_u8` for 90°) to eliminate column-strided cache misses.
- **Planar I420 & Stride-2 Semi-Planar NEON Fast Paths**: Added NEON SIMD vectorization for `uvPixelStride == 1` and generic `uvPixelStride == 2` in both `nativePushYuvToYuyvFrame` and `nativeYuv420ToNv21`.
- **NEON YUYV Startup Fill**: Vectorized initial black YUYV frame priming in `startV4l2StreamingLocked` with 16-byte `vst1q_u8` stores.

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
