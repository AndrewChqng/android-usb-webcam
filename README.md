# Android USB Webcam Client (UVC Gadget)

A root-enabled Android application that exposes your phone's physical cameras (Camera2 API) as a hardware-level **USB Video Class (UVC)** webcam (`/dev/videoX`) over ConfigFS (`CONFIG_USB_CONFIGFS_F_UVC=y`), requiring zero host drivers on Linux, Windows, or macOS.

Built on the ConfigFS and `libsu` `RootService` foundation of [`Arian04/android-hid-client`](https://github.com/Arian04/android-hid-client).

## Key Features

- **Hardware UVC 1.1 Gadget (`functions/uvc.0`)**:
  - **MJPEG (`streaming/mjpeg/m`)**: `1920x1080`, `1280x720`, and `640x360` at `60 / 30 / 15 fps`.
  - **Uncompressed YUYV (`streaming/uncompressed/u`)**: `640x360`, `640x480`, and `1280x720` fallback.
- **Composite USB & DWC3 Endpoint Safe**:
  - Automatically unlinks bandwidth/endpoint-heavy vendor functions (`ffs.mtp`, `ss_acm.0`, `ss_mon.mtp`) while active so Qualcomm DWC3 TX FIFOs do not overflow, keeping `uvc.0` + `ffs.adb` active (or pure UVC-only mode via Settings), and restores original USB functions on Stop.
- **Native C++ V4L2 Event & Streaming Engine (`uvc_gadget.cpp`)**:
  - Handles `UVC_EVENT_CONNECT`, `UVC_EVENT_DISCONNECT`, `UVC_EVENT_SETUP`, `UVC_EVENT_DATA` (`VS_PROBE_CONTROL` / `VS_COMMIT_CONTROL` via `UVCIOC_SEND_RESPONSE`), `UVC_EVENT_STREAMON`, and `UVC_EVENT_STREAMOFF`.
  - Zero-GC frame pipeline using `V4L2_MEMORY_MMAP` buffers, native `-O3` YUV420 planar-to-NV21/YUYV conversion with crop/scale/rotation/mirroring, and NEON-accelerated JPEG compression into direct `ByteBuffer`s.
- **Power-Efficient Foreground Service**:
  - Continues streaming with the display turned off via a Camera Foreground Service and partial wake lock.
  - Local viewfinder preview is disabled by default (zero GPU/SurfaceFlinger overhead) and can be toggled on at any time for framing.

## Prerequisites

- Rooted Android device (Magisk, KernelSU, or APatch).
- Kernel compiled with `CONFIG_USB_CONFIGFS=y` and `CONFIG_USB_CONFIGFS_F_UVC=y`.

## Credits & Disclaimer

- Base architecture & UI framework: [Arian04/android-hid-client](https://github.com/Arian04/android-hid-client) (GPL-3.0).
- Repurposed from USB HID to USB Video Class (UVC) webcam with assistance from **Antigravity (Google DeepMind)** by replacing the UHID/hidg report layer with a ConfigFS `f_uvc` descriptor manager, automatic `/sys/class/video4linux` gadget node discovery, SELinux `ioctl`/`map` policy injection, a native C++/JNI V4L2 UVC control/streaming state machine, and a Camera2 foreground streaming pipeline.
- *Disclaimer: This project includes experimental AI-assisted code that directly manipulates Linux kernel ConfigFS USB gadgets and SELinux policies as root. Use at your own risk.*
