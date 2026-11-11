# Technical Learnings & Architectural Notes

> **AI Attribution & Disclaimer**: Architectural improvements, bug fixes, and dynamic resolution subsystem developed with assistance from **Antigravity (Google DeepMind)**. Code is experimental; hardware interaction with USB ConfigFS, DWC3 drivers, and V4L2 requires root privileges (`su`) and direct kernel ioctls.

---

## 1. Linux USB Gadget ConfigFS & `f_uvc` Internals

### Lexicographical Frame Directory Ordering
- Linux kernel `f_uvc` gadget driver discovers streaming frame descriptors by iterating subdirectories in `streaming/{mjpeg,uncompressed}/...` using `scandir` or `readdir`, which reads directories **strictly in alphabetical order**.
- If directory names are unordered (e.g. `f1_360p`, `f2_480p`, ..., `f10_1080p`), `f10` sorts before `f2` (`f1, f10, f2...`), scrambling the 1-based `bFrameIndex` descriptors negotiated with the USB host.
- **Solution**: Always pad index names with two digits: `f%02d_%dx%d` (e.g. `f01_1920x1080`, `f02_1280x720`, ..., `f16_640x360`).

### Bash Variable Substitution Pitfall in Root Shell
- In shell scripts executed via `Shell.cmd()`, writing `${UVC/streaming/mjpeg/m}` is interpreted by Bash as a pattern replacement expression `${VAR/pattern/replacement}`. It strips `/streaming` and outputs `/config/usb_gadget/g1/functions/uvc.0/mjpeg/m` instead of `/config/usb_gadget/g1/functions/uvc.0/streaming/mjpeg/m`.
- This broke symlinking to `header/h/m` and caused the webcam device to disappear from the host.
- **Rule**: Never use slash-based in-place paths with braces like `${VAR/subpath}`. Always use explicit concatenation: `"${UVC}/streaming/mjpeg/m"` or pass format relative subpaths (`"${UVC}/$subpath"`).

### Persistent Shell Execution with Libsu
- `libsu` executes commands inside a persistent daemon root shell.
- Never execute `exit 0` inside root scripts run via `Shell.cmd()`, as it terminates the shared daemon shell process. Always use `break` or normal command termination.

---

## 2. Real-Time USB Scheduling & Qualcomm DWC3 Thread Quirks

### Threaded DWC3 IRQ Spawning on Little Cores
- On Qualcomm Snapdragon platforms (e.g. Snapdragon 8 Gen 3 / SM8650), the `dwc3-msm` USB controller driver spawns a dedicated threaded IRQ handler `[irq/<N>-dwc3]` asynchronously upon USB bus reset or UDC binding.
- By default, Android kernel assigns this thread to `SCHED_OTHER` with nice 0, often scheduling it onto LITTLE CPU cores (CPU 0-1).
- Under high ISOC USB 3.0 / USB 2.0 bandwidth (3072 bytes/packet × 3 transactions/microframe), scheduling jitter on little cores causes missed isochronous microframes (`-EXDEV`), resulting in `UVC_QUEUE_DROP_INCOMPLETE` truncated buffers on the host.

### Solution: Dynamic IRQ Elevation & CPU Affinity Pinning
- Elevate USB IRQ threads to real-time `SCHED_FIFO 90` using `chrt -f -p 90 <pid>`.
- Pin IRQ smp affinity to big performance cores:
  ```bash
  for irq in $(awk '/dwc3|udc/ {gsub(":", "", $1); print $1}' /proc/interrupts); do
      echo 2-3 > "/proc/irq/$irq/smp_affinity_list" || true
  done
  ```
- Because Qualcomm re-spawns or resets this thread upon bus enumeration, this optimization is applied both during gadget creation and re-checked once the USB host binds and initiates streaming.

---

## 3. Dynamic Resolution Discovery & Aspect Ratio Handling

### Camera2 Sensor Discovery
- Device cameras advertise native capture sizes via `CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP` for `ImageFormat.YUV_420_888`.
- Phone sensors typically output landscape resolutions (16:9, 4:3, 1:1).

### Vertical (9:16) & Custom Resolution Synthesis
- For vertical 9:16 streaming (e.g. 1080×1920 for OBS, mobile streaming, TikTok, Instagram Live), native camera sensors do not output 9:16 natively while the phone is horizontal.
- The C++ scaler (`buildCoordinateLuts` in `uvc_gadget.cpp`) performs arbitrary destination mapping:
  - Rotates (0°, 90°, 180°, 270°).
  - Performs aspect-correct center-cropping or zooming.
  - Scales bilinear YUV420 to destination dimensions `dstW × dstH` without memory reallocation.
- In `WebcamController.chooseOptimalSize()`, requested resolutions use `maxOf(w, h)` and `minOf(w, h)` to select the highest quality matching sensor stream from Camera2 before cropping/scaling in native code.

---

## 4. Resolution Boundaries & Efficiency Policies

### Technical vs Arbitrary Resolution Limits
- Resolutions must **never** be hidden or dropped simply for being small. Standard low resolutions (e.g. 176×144 QCIF, 320×240 QVGA, 352×288 CIF) are fully valid and supported.
- **Valid Technical Limits**:
  1. **Minimum Dimension ($\ge 16 \times 16$)**: Required by JPEG DCT macroblocks ($8\times8$ or $16\times16$) and hardware video encoders.
  2. **Even Dimension Parity ($w \% 2 == 0, h \% 2 == 0$)**: Required by YUV 4:2:0 planar chroma subsampling and YUYV 4:2:2 packed pixel pairs.
  3. **Maximum Dimension ($\le 7680 \times 7680$)**: 8K boundary within 16-bit uint descriptors (`wWidth`, `wHeight`).

### Sensor Capture Matching for Power & Throughput
- In `WebcamController.chooseOptimalSize()`, low-resolution requests (e.g. 320×240 or 640×360) do not artificially force a 1080p sensor stream.
- Instead, the controller matches the smallest native sensor resolution that covers the requested dimensions. This eliminates unnecessary CPU downscaling, reduces ISP memory bandwidth, and optimizes device battery life.

## 5. Double-Buffering & Zero-Tear Synchronization

- Queuing multiple V4L2 buffers simultaneously can cause tearing if camera frame production outpaces host consumption.
- Setting `maxInFlightBuffers = 1` and checking `UvcNative.nativeCanAcceptFrame(1)` before camera YUV encoding ensures that V4L2 kernel buffers are drained and only a single buffer is in-flight in the USB FIFO, completely preventing tearing and buffer bloat.
