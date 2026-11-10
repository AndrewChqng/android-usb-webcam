#include "uvc_gadget.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/time.h>
#include <unistd.h>

#if defined(__aarch64__) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

#include <atomic>
#include <mutex>
#include <vector>

#define LOG_TAG "UvcGadgetJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Use 6 V4L2 MMAP buffers in a strict circular ring so a dequeued buffer is never
// overwritten while DWC3 scatter-gather DMA still has trailing USB requests in flight.
constexpr int kNumV4l2Buffers = 6;

struct MmapBuffer {
    void* start = nullptr;
    size_t length = 0;
    bool queued = false;
};

struct UvcContext {
    int v4l2_fd = -1;
    int stop_efd = -1;
    uint32_t max_payload_size = 1024;

    uint8_t current_control = 0; // UVC_VS_PROBE_CONTROL or UVC_VS_COMMIT_CONTROL
    struct uvc_streaming_control probe_ctrl {};
    struct uvc_streaming_control commit_ctrl {};

    uint32_t active_fourcc = V4L2_PIX_FMT_MJPEG;
    uint16_t active_width = 1280;
    uint16_t active_height = 720;
    uint32_t active_interval = 333333;

    std::atomic<bool> is_streaming{false};
    bool is_yuyv_only = false;
    std::mutex stream_mutex;
    MmapBuffer buffers[kNumV4l2Buffers]{};
    uint32_t num_buffers = 0;
    uint32_t next_write_idx = 0;
    uint32_t frame_sequence = 0;

    int num_formats = 0;
    UvcFormatDesc formats[2]{};
};

static void initContextFormats(UvcContext* ctx, bool isYuyvOnly, int profileId) {
    if (!ctx) return;

    const UvcFrameDesc frm360p = {1, 640, 360, 333333, {166666, 333333, 666666, 0}};
    const UvcFrameDesc frm480p = {2, 640, 480, 333333, {166666, 333333, 666666, 0}};
    const UvcFrameDesc frm720p = {3, 1280, 720, 333333, {166666, 333333, 666666, 0}};
    const UvcFrameDesc frm1080p = {4, 1920, 1080, 333333, {166666, 333333, 666666, 0}};
    const UvcFrameDesc frm1440p = {5, 2560, 1440, 333333, {166666, 333333, 666666, 0}};
    const UvcFrameDesc frm4k = {6, 3840, 2160, 333333, {333333, 666666, 0, 0}};

    const UvcFrameDesc yuyv360p = {1, 640, 360, 333333, {333333, 666666, 1000000, 0}};
    const UvcFrameDesc yuyv480p = {2, 640, 480, 333333, {333333, 666666, 1000000, 0}};
    const UvcFrameDesc yuyv720p = {3, 1280, 720, 666666, {666666, 1000000, 2000000, 0}};
    const UvcFrameDesc yuyv1080p = {4, 1920, 1080, 2000000, {2000000, 0, 0, 0}};

    UvcFormatDesc mjpegFmt{};
    mjpegFmt.formatIndex = 1;
    mjpegFmt.fourcc = V4L2_PIX_FMT_MJPEG;

    UvcFormatDesc yuyvFmt{};
    yuyvFmt.formatIndex = isYuyvOnly ? 1 : 2;
    yuyvFmt.fourcc = V4L2_PIX_FMT_YUYV;

    switch (profileId) {
        case 1: // Max 1440p (2K)
            mjpegFmt.numFrames = 5;
            mjpegFmt.frames[0] = frm360p;
            mjpegFmt.frames[1] = frm480p;
            mjpegFmt.frames[2] = frm720p;
            mjpegFmt.frames[3] = frm1080p;
            mjpegFmt.frames[4] = frm1440p;

            yuyvFmt.numFrames = 4;
            yuyvFmt.frames[0] = yuyv360p;
            yuyvFmt.frames[1] = yuyv480p;
            yuyvFmt.frames[2] = yuyv720p;
            yuyvFmt.frames[3] = yuyv1080p;
            break;

        case 2: // Max 4K (2160p)
            mjpegFmt.numFrames = 6;
            mjpegFmt.frames[0] = frm360p;
            mjpegFmt.frames[1] = frm480p;
            mjpegFmt.frames[2] = frm720p;
            mjpegFmt.frames[3] = frm1080p;
            mjpegFmt.frames[4] = frm1440p;
            mjpegFmt.frames[5] = frm4k;

            yuyvFmt.numFrames = 4;
            yuyvFmt.frames[0] = yuyv360p;
            yuyvFmt.frames[1] = yuyv480p;
            yuyvFmt.frames[2] = yuyv720p;
            yuyvFmt.frames[3] = yuyv1080p;
            break;

        case 3: // Max 720p (HD)
            mjpegFmt.numFrames = 3;
            mjpegFmt.frames[0] = frm360p;
            mjpegFmt.frames[1] = frm480p;
            mjpegFmt.frames[2] = frm720p;

            yuyvFmt.numFrames = 3;
            yuyvFmt.frames[0] = yuyv360p;
            yuyvFmt.frames[1] = yuyv480p;
            yuyvFmt.frames[2] = yuyv720p;
            break;

        case 4: // Only 1080p (FHD Forced)
            mjpegFmt.numFrames = 1;
            mjpegFmt.frames[0] = {1, 1920, 1080, 333333, {166666, 333333, 666666, 0}};

            yuyvFmt.numFrames = 1;
            yuyvFmt.frames[0] = {1, 1920, 1080, 2000000, {2000000, 0, 0, 0}};
            break;

        case 5: // Only 720p (HD Forced)
            mjpegFmt.numFrames = 1;
            mjpegFmt.frames[0] = {1, 1280, 720, 333333, {166666, 333333, 666666, 0}};

            yuyvFmt.numFrames = 1;
            yuyvFmt.frames[0] = {1, 1280, 720, 666666, {666666, 1000000, 2000000, 0}};
            break;

        case 6: // Only 4K (UHD Forced)
            mjpegFmt.numFrames = 1;
            mjpegFmt.frames[0] = {1, 3840, 2160, 333333, {333333, 666666, 0, 0}};

            yuyvFmt.numFrames = 1;
            yuyvFmt.frames[0] = {1, 1920, 1080, 2000000, {2000000, 0, 0, 0}};
            break;

        case 0: // Max 1080p (Default)
        default:
            mjpegFmt.numFrames = 4;
            mjpegFmt.frames[0] = frm360p;
            mjpegFmt.frames[1] = frm480p;
            mjpegFmt.frames[2] = frm720p;
            mjpegFmt.frames[3] = frm1080p;

            yuyvFmt.numFrames = 4;
            yuyvFmt.frames[0] = yuyv360p;
            yuyvFmt.frames[1] = yuyv480p;
            yuyvFmt.frames[2] = yuyv720p;
            yuyvFmt.frames[3] = yuyv1080p;
            break;
    }

    if (isYuyvOnly) {
        ctx->num_formats = 1;
        ctx->formats[0] = yuyvFmt;
    } else {
        ctx->num_formats = 2;
        ctx->formats[0] = mjpegFmt;
        ctx->formats[1] = yuyvFmt;
    }
}

static std::mutex g_ctx_mutex;
static UvcContext* g_ctx = nullptr;

// Minimal valid 16x16 black JPEG used to prime V4L2 buffer 0 on STREAMON before Camera2 delivers its first frame
static const uint8_t kTinyBlackJpeg[] = {
    0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01,
    0x01, 0x01, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00, 0xff, 0xdb, 0x00, 0x43,
    0x00, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xc0, 0x00, 0x0b, 0x08, 0x00, 0x10,
    0x00, 0x10, 0x01, 0x01, 0x11, 0x00, 0xff, 0xc4, 0x00, 0x14, 0x00, 0x01,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0xff, 0xc4, 0x00, 0x14, 0x10, 0x01, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0xff, 0xda, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3f, 0x00,
    0x37, 0xff, 0xd9
};

void fillStreamingControl(
    UvcContext* ctx,
    struct uvc_streaming_control* ctrl,
    int formatIndex,
    int frameIndex,
    uint32_t requestedInterval
) {
    memset(ctrl, 0, sizeof(*ctrl));
    if (!ctx || ctx->num_formats == 0) return;

    int maxFmt = ctx->num_formats;
    int fmtIdx = (formatIndex >= 1 && formatIndex <= maxFmt) ? (formatIndex - 1) : 0;
    const UvcFormatDesc& fmt = ctx->formats[fmtIdx];

    int frmIdx = (frameIndex >= 1 && frameIndex <= fmt.numFrames) ? (frameIndex - 1) : 0;
    const UvcFrameDesc& frm = fmt.frames[frmIdx];

    uint32_t interval = frm.defaultInterval;
    if (requestedInterval != 0) {
        for (int i = 0; i < 4 && frm.intervals[i] != 0; ++i) {
            interval = frm.intervals[i];
            if (requestedInterval <= frm.intervals[i]) {
                break;
            }
        }
    }

    ctrl->bmHint = 1; // dwFrameInterval
    ctrl->bFormatIndex = fmt.formatIndex;
    ctrl->bFrameIndex = frm.frameIndex;
    ctrl->dwFrameInterval = interval;
    ctrl->wKeyFrameRate = 0;
    ctrl->wPFrameRate = 0;
    ctrl->wCompQuality = 0;
    ctrl->wCompWindowSize = 0;
    ctrl->wDelay = 0;
    ctrl->dwMaxVideoFrameSize = static_cast<uint32_t>(frm.width) * static_cast<uint32_t>(frm.height) * 2u;
    ctrl->dwMaxPayloadTransferSize = ctx->max_payload_size;
    ctrl->bmFramingInfo = 3;
    ctrl->bPreferedVersion = 1;
    ctrl->bMaxVersion = 1;
}

void stopV4l2StreamingLocked(UvcContext* ctx, bool callStreamOffIoctl = true) {
    if (!ctx) return;

    const bool wasStreaming = ctx->is_streaming.exchange(false);
    if (wasStreaming && callStreamOffIoctl && ctx->v4l2_fd >= 0) {
        int type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
        if (ioctl(ctx->v4l2_fd, VIDIOC_STREAMOFF, &type) < 0) {
            LOGW("VIDIOC_STREAMOFF failed: %s", strerror(errno));
        }
    }

    for (uint32_t i = 0; i < ctx->num_buffers; ++i) {
        if (ctx->buffers[i].start && ctx->buffers[i].start != MAP_FAILED) {
            munmap(ctx->buffers[i].start, ctx->buffers[i].length);
        }
        ctx->buffers[i].start = nullptr;
        ctx->buffers[i].length = 0;
        ctx->buffers[i].queued = false;
    }
    ctx->num_buffers = 0;
    ctx->next_write_idx = 0;

    if (ctx->v4l2_fd >= 0) {
        struct v4l2_requestbuffers req {};
        req.count = 0;
        req.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
        req.memory = V4L2_MEMORY_MMAP;
        ioctl(ctx->v4l2_fd, VIDIOC_REQBUFS, &req);
    }
}

bool startV4l2StreamingLocked(UvcContext* ctx) {
    stopV4l2StreamingLocked(ctx, false);

    struct v4l2_format fmt {};
    fmt.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    fmt.fmt.pix.width = ctx->active_width;
    fmt.fmt.pix.height = ctx->active_height;
    fmt.fmt.pix.pixelformat = ctx->active_fourcc;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;
    if (ctx->active_fourcc == V4L2_PIX_FMT_MJPEG) {
        fmt.fmt.pix.sizeimage = static_cast<uint32_t>(ctx->active_width) * ctx->active_height * 2u;
    } else {
        fmt.fmt.pix.bytesperline = ctx->active_width * 2u;
        fmt.fmt.pix.sizeimage = fmt.fmt.pix.bytesperline * ctx->active_height;
    }

    if (ioctl(ctx->v4l2_fd, VIDIOC_S_FMT, &fmt) < 0) {
        LOGE("VIDIOC_S_FMT(%ux%u, fourcc=0x%x) failed: %s",
             ctx->active_width, ctx->active_height, ctx->active_fourcc, strerror(errno));
        return false;
    }

    struct v4l2_requestbuffers req {};
    req.count = kNumV4l2Buffers;
    req.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    req.memory = V4L2_MEMORY_MMAP;
    if (ioctl(ctx->v4l2_fd, VIDIOC_REQBUFS, &req) < 0 || req.count < 2) {
        LOGE("VIDIOC_REQBUFS failed: %s (count=%u)", strerror(errno), req.count);
        return false;
    }

    ctx->num_buffers = req.count > kNumV4l2Buffers ? kNumV4l2Buffers : req.count;
    ctx->frame_sequence = 0;
    ctx->next_write_idx = 0;

    for (uint32_t i = 0; i < ctx->num_buffers; ++i) {
        struct v4l2_buffer buf {};
        buf.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        if (ioctl(ctx->v4l2_fd, VIDIOC_QUERYBUF, &buf) < 0) {
            LOGE("VIDIOC_QUERYBUF[%u] failed: %s", i, strerror(errno));
            stopV4l2StreamingLocked(ctx, false);
            return false;
        }

        void* ptr = mmap(nullptr, buf.length, PROT_READ | PROT_WRITE, MAP_SHARED, ctx->v4l2_fd, buf.m.offset);
        if (ptr == MAP_FAILED) {
            LOGE("mmap[%u] failed: %s", i, strerror(errno));
            stopV4l2StreamingLocked(ctx, false);
            return false;
        }

        ctx->buffers[i].start = ptr;
        ctx->buffers[i].length = buf.length;
        ctx->buffers[i].queued = false;
    }

    // Prime only buffer 0 so the host receives an immediate frame on STREAMON while buffers 1..N-1 remain free
    if (ctx->num_buffers > 0) {
        struct v4l2_buffer buf {};
        buf.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = 0;
        if (ctx->active_fourcc == V4L2_PIX_FMT_MJPEG) {
            memcpy(ctx->buffers[0].start, kTinyBlackJpeg, sizeof(kTinyBlackJpeg));
            buf.bytesused = sizeof(kTinyBlackJpeg);
        } else {
            uint8_t* p = static_cast<uint8_t*>(ctx->buffers[0].start);
            size_t frameBytes = static_cast<size_t>(ctx->active_width) * ctx->active_height * 2u;
            if (frameBytes > ctx->buffers[0].length) frameBytes = ctx->buffers[0].length;
            for (size_t j = 0; j + 1 < frameBytes; j += 2) {
                p[j] = 0x00;
                p[j + 1] = 0x80;
            }
            buf.bytesused = static_cast<uint32_t>(frameBytes);
        }
        if (ioctl(ctx->v4l2_fd, VIDIOC_QBUF, &buf) == 0) {
            ctx->buffers[0].queued = true;
            ctx->next_write_idx = 1 % ctx->num_buffers;
        }
    }

    int type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    if (ioctl(ctx->v4l2_fd, VIDIOC_STREAMON, &type) < 0) {
        LOGE("VIDIOC_STREAMON failed: %s", strerror(errno));
        stopV4l2StreamingLocked(ctx, false);
        return false;
    }

    ctx->is_streaming.store(true);
    LOGI("V4L2 UVC stream started: %ux%u fourcc=0x%x interval=%u num_buffers=%u",
         ctx->active_width, ctx->active_height, ctx->active_fourcc, ctx->active_interval, ctx->num_buffers);
    return true;
}

void handleUvcSetupEvent(UvcContext* ctx, const struct usb_ctrlrequest& ctrl, struct uvc_request_data& resp) {
    resp.length = -EL2HLT; // Stall by default if unhandled

    if ((ctrl.bRequestType & USB_TYPE_MASK) != USB_TYPE_CLASS) {
        return;
    }

    const uint8_t recip = ctrl.bRequestType & USB_RECIP_MASK;
    if (recip != USB_RECIP_INTERFACE) {
        return;
    }

    const uint8_t entityId = (ctrl.wIndex >> 8) & 0xff;
    const uint8_t cs = (ctrl.wValue >> 8) & 0xff;

    const bool isStreamingProbeOrCommit =
        (entityId == 0) &&
        (cs == UVC_VS_PROBE_CONTROL || cs == UVC_VS_COMMIT_CONTROL) &&
        !(ctrl.bRequest == UVC_GET_CUR && ctrl.wLength == 1);

    if (!isStreamingProbeOrCommit) {
        if (cs == UVC_VC_REQUEST_ERROR_CODE_CONTROL) {
            resp.data[0] = 0x06; // Invalid control
            resp.length = 1;
        } else {
            switch (ctrl.bRequest) {
                case UVC_SET_CUR:
                    resp.data[0] = 0x00;
                    resp.length = ctrl.wLength;
                    break;
                case UVC_GET_INFO:
                    resp.data[0] = 0x03;
                    resp.length = 1;
                    break;
                default:
                    memset(resp.data, 0, sizeof(resp.data));
                    resp.length = ctrl.wLength > sizeof(resp.data) ? sizeof(resp.data) : ctrl.wLength;
                    break;
            }
        }
        return;
    }

    ctx->current_control = cs;
    struct uvc_streaming_control* target = (cs == UVC_VS_PROBE_CONTROL) ? &ctx->probe_ctrl : &ctx->commit_ctrl;

    switch (ctrl.bRequest) {
        case UVC_SET_CUR:
            resp.data[0] = 0x00;
            resp.length = ctrl.wLength;
            break;

        case UVC_GET_CUR:
            memcpy(resp.data, target, sizeof(*target));
            resp.length = ctrl.wLength < sizeof(*target) ? ctrl.wLength : sizeof(*target);
            break;

        case UVC_GET_MIN:
        case UVC_GET_MAX:
        case UVC_GET_DEF: {
            struct uvc_streaming_control temp {};
            fillStreamingControl(
                ctx,
                &temp,
                target->bFormatIndex ? target->bFormatIndex : 1,
                target->bFrameIndex ? target->bFrameIndex : 2,
                (ctrl.bRequest == UVC_GET_MIN) ? 166666 : ((ctrl.bRequest == UVC_GET_MAX) ? 1000000 : 333333)
            );
            memcpy(resp.data, &temp, sizeof(temp));
            resp.length = ctrl.wLength < sizeof(temp) ? ctrl.wLength : sizeof(temp);
            break;
        }

        case UVC_GET_RES:
            memset(resp.data, 0, sizeof(resp.data));
            resp.length = ctrl.wLength < sizeof(*target) ? ctrl.wLength : sizeof(*target);
            break;

        case UVC_GET_LEN:
            resp.data[0] = sizeof(struct uvc_streaming_control);
            resp.data[1] = 0x00;
            resp.length = 2;
            break;

        case UVC_GET_INFO:
            resp.data[0] = 0x03; // Supports GET and SET
            resp.length = 1;
            break;
    }
}

void handleUvcDataEvent(UvcContext* ctx, const struct uvc_request_data& data) {
    if (ctx->current_control != UVC_VS_PROBE_CONTROL && ctx->current_control != UVC_VS_COMMIT_CONTROL) {
        return;
    }
    if (data.length < 26) {
        return;
    }

    const auto* incoming = reinterpret_cast<const struct uvc_streaming_control*>(data.data);
    struct uvc_streaming_control resolved {};
    fillStreamingControl(ctx, &resolved, incoming->bFormatIndex, incoming->bFrameIndex, incoming->dwFrameInterval);

    ctx->probe_ctrl = resolved;
    if (ctx->current_control == UVC_VS_COMMIT_CONTROL) {
        ctx->commit_ctrl = resolved;

        int maxFmt = ctx->num_formats;
        int fmtIdx = (resolved.bFormatIndex >= 1 && resolved.bFormatIndex <= maxFmt) ? (resolved.bFormatIndex - 1) : 0;
        const UvcFormatDesc& fmt = ctx->formats[fmtIdx];
        int frmIdx = (resolved.bFrameIndex >= 1 && resolved.bFrameIndex <= fmt.numFrames) ? (resolved.bFrameIndex - 1) : 0;
        const UvcFrameDesc& frm = fmt.frames[frmIdx];

        ctx->active_fourcc = fmt.fourcc;
        ctx->active_width = frm.width;
        ctx->active_height = frm.height;
        ctx->active_interval = resolved.dwFrameInterval ? resolved.dwFrameInterval : frm.defaultInterval;

        LOGI("Host committed UVC format: fmt=%u (%s) %ux%u interval=%u",
             fmt.formatIndex,
             (fmt.fourcc == V4L2_PIX_FMT_MJPEG) ? "MJPEG" : "YUYV",
             ctx->active_width,
             ctx->active_height,
             ctx->active_interval);
    }
}

/**
 * Precomputes 1D source coordinate lookup tables in O(dstW + dstH) time instead of O(dstW * dstH),
 * completely eliminating per-pixel branches and integer divisions during scaling/rotation/mirroring.
 *
 * When swappedAxes == false (rotation 0 or 180):
 *   sx = lutForDestX[dx], sy = lutForDestY[dy]
 * When swappedAxes == true (rotation 90 or 270):
 *   sx = lutForDestY[dy], sy = lutForDestX[dx]
 */
void buildCoordinateLuts(
    int dstW, int dstH,
    int srcW, int srcH,
    int rotation, bool mirror,
    float zoom, float panX, float panY,
    bool& swappedAxes,
    std::vector<int>& lutForDestX,
    std::vector<int>& lutForDestY
) {
    swappedAxes = (rotation == 90 || rotation == 270);
    const int rw = swappedAxes ? dstH : dstW;
    const int rh = swappedAxes ? dstW : dstH;

    int baseW = srcW;
    int baseH = srcH;
    if (static_cast<int64_t>(srcW) * rh > static_cast<int64_t>(srcH) * rw) {
        baseW = static_cast<int>((static_cast<int64_t>(srcH) * rw) / rh);
    } else if (static_cast<int64_t>(srcW) * rh < static_cast<int64_t>(srcH) * rw) {
        baseH = static_cast<int>((static_cast<int64_t>(srcW) * rh) / rw);
    }

    const float z = (zoom >= 1.0f) ? zoom : 1.0f;
    int croppedSrcW = static_cast<int>(baseW / z);
    int croppedSrcH = static_cast<int>(baseH / z);
    if (croppedSrcW < 16) croppedSrcW = 16;
    if (croppedSrcH < 16) croppedSrcH = 16;
    if (croppedSrcW > srcW) croppedSrcW = srcW;
    if (croppedSrcH > srcH) croppedSrcH = srcH;

    const int maxPanX = srcW - croppedSrcW;
    const int maxPanY = srcH - croppedSrcH;

    float userX = mirror ? -panX : panX;
    float userY = panY;

    float sensorPanX = userX;
    float sensorPanY = userY;
    switch (rotation) {
        case 90:
            sensorPanX = userY;
            sensorPanY = -userX;
            break;
        case 180:
            sensorPanX = -userX;
            sensorPanY = -userY;
            break;
        case 270:
            sensorPanX = -userY;
            sensorPanY = userX;
            break;
        default:
            break;
    }

    const float normX = ((sensorPanX < -1.0f ? -1.0f : (sensorPanX > 1.0f ? 1.0f : sensorPanX)) + 1.0f) * 0.5f;
    const float normY = ((sensorPanY < -1.0f ? -1.0f : (sensorPanY > 1.0f ? 1.0f : sensorPanY)) + 1.0f) * 0.5f;

    const int offsetX = static_cast<int>(normX * maxPanX);
    const int offsetY = static_cast<int>(normY * maxPanY);

    lutForDestX.resize(dstW);
    lutForDestY.resize(dstH);

    if (!swappedAxes) {
        // rotation 0 or 180: rx depends on dx, ry depends on dy
        for (int dx = 0; dx < dstW; ++dx) {
            int mx = mirror ? (dstW - 1 - dx) : dx;
            int rx = (rotation == 180) ? (dstW - 1 - mx) : mx;
            int sx = offsetX + static_cast<int>((static_cast<int64_t>(rx) * croppedSrcW) / rw);
            if (sx < 0) sx = 0;
            else if (sx >= srcW) sx = srcW - 1;
            lutForDestX[dx] = sx;
        }
        for (int dy = 0; dy < dstH; ++dy) {
            int ry = (rotation == 180) ? (dstH - 1 - dy) : dy;
            int sy = offsetY + static_cast<int>((static_cast<int64_t>(ry) * croppedSrcH) / rh);
            if (sy < 0) sy = 0;
            else if (sy >= srcH) sy = srcH - 1;
            lutForDestY[dy] = sy;
        }
    } else {
        // rotation 90 or 270: rx depends on dy, ry depends on dx
        for (int dx = 0; dx < dstW; ++dx) {
            int mx = mirror ? (dstW - 1 - dx) : dx;
            int ry = (rotation == 90) ? (dstW - 1 - mx) : mx;
            int sy = offsetY + static_cast<int>((static_cast<int64_t>(ry) * croppedSrcH) / rh);
            if (sy < 0) sy = 0;
            else if (sy >= srcH) sy = srcH - 1;
            lutForDestX[dx] = sy;
        }
        for (int dy = 0; dy < dstH; ++dy) {
            int rx = (rotation == 270) ? (dstH - 1 - dy) : dy;
            int sx = offsetX + static_cast<int>((static_cast<int64_t>(rx) * croppedSrcW) / rw);
            if (sx < 0) sx = 0;
            else if (sx >= srcW) sx = srcW - 1;
            lutForDestY[dy] = sx;
        }
    }
}

struct CoordinateLutCache {
    int dstW = 0;
    int dstH = 0;
    int srcW = 0;
    int srcH = 0;
    int rotation = -1;
    bool mirror = false;
    float zoom = 1.0f;
    float panX = 0.0f;
    float panY = 0.0f;
    bool swappedAxes = false;
    std::vector<int> lutX;
    std::vector<int> lutY;
};

static thread_local CoordinateLutCache tl_lutCache;

inline const CoordinateLutCache& getCoordinateLuts(
    int dstW, int dstH,
    int srcW, int srcH,
    int rotation, bool mirror,
    float zoom, float panX, float panY
) {
    if (tl_lutCache.dstW != dstW || tl_lutCache.dstH != dstH ||
        tl_lutCache.srcW != srcW || tl_lutCache.srcH != srcH ||
        tl_lutCache.rotation != rotation || tl_lutCache.mirror != mirror ||
        std::abs(tl_lutCache.zoom - zoom) > 0.001f ||
        std::abs(tl_lutCache.panX - panX) > 0.001f ||
        std::abs(tl_lutCache.panY - panY) > 0.001f) {
        tl_lutCache.dstW = dstW;
        tl_lutCache.dstH = dstH;
        tl_lutCache.srcW = srcW;
        tl_lutCache.srcH = srcH;
        tl_lutCache.rotation = rotation;
        tl_lutCache.mirror = mirror;
        tl_lutCache.zoom = zoom;
        tl_lutCache.panX = panX;
        tl_lutCache.panY = panY;
        buildCoordinateLuts(dstW, dstH, srcW, srcH, rotation, mirror, zoom, panX, panY,
                            tl_lutCache.swappedAxes, tl_lutCache.lutX, tl_lutCache.lutY);
    }
    return tl_lutCache;
}

void drainCompletedBuffersLocked(UvcContext* ctx) {
    while (true) {
        struct v4l2_buffer dq {};
        dq.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
        dq.memory = V4L2_MEMORY_MMAP;
        if (ioctl(ctx->v4l2_fd, VIDIOC_DQBUF, &dq) == 0) {
            if (dq.index < ctx->num_buffers) {
                ctx->buffers[dq.index].queued = false;
            }
        } else {
            break;
        }
    }
}

uint32_t countQueuedBuffersLocked(const UvcContext* ctx) {
    uint32_t count = 0;
    for (uint32_t i = 0; i < ctx->num_buffers; ++i) {
        if (ctx->buffers[i].queued) {
            ++count;
        }
    }
    return count;
}

/**
 * Calculates the maximum queued kernel buffers allowed for a given queue depth preference.
 *
 * In USB UVC DMA, 1 buffer is always actively being streamed by DWC3 hardware DMA.
 * Enforcing maxQueued = 1 prevents queuing a second buffer while the initial primed
 * black frame is active, causing an unrecoverable deadlock.
 *
 * Adding 1 treats `maxInFlight` as the depth of buffers queued *ahead* of the active DMA buffer:
 * - maxInFlight = 1 -> 2 buffers in kernel (1 DMA + 1 queued ahead), minimal single-frame latency.
 * - maxInFlight = 2 -> 3 buffers in kernel (tear-free balanced).
 * - maxInFlight = 3 -> 4 buffers in kernel (max smoothness).
 */
static inline uint32_t computeMaxQueuedBuffersLocked(const UvcContext* ctx, int maxInFlight) {
    const uint32_t allowedInFlight = static_cast<uint32_t>(maxInFlight > 0 ? maxInFlight : 1) + 1;
    return (allowedInFlight < ctx->num_buffers)
        ? allowedInFlight
        : (ctx->num_buffers - 1);
}

/**
 * Drains completed V4L2 buffers and selects the next write buffer using a strict round-robin index
 * (`next_write_idx`) while respecting `maxInFlight`.
 *
 * This guarantees that a buffer recently marked DONE by `f_uvc` (which may still have scatter-gather
 * TRBs draining in the DWC3 hardware FIFO) is not overwritten until `num_buffers - 1` frames later,
 * completely preventing mid-frame tearing.
 */
int acquireWritableBufferLocked(UvcContext* ctx, struct v4l2_buffer& buf, int maxInFlight) {
    drainCompletedBuffersLocked(ctx);

    if (ctx->num_buffers == 0) {
        return -1;
    }

    const uint32_t maxQueued = computeMaxQueuedBuffersLocked(ctx, maxInFlight);

    if (countQueuedBuffersLocked(ctx) >= maxQueued) {
        return -1;
    }

    for (uint32_t step = 0; step < ctx->num_buffers; ++step) {
        uint32_t idx = (ctx->next_write_idx + step) % ctx->num_buffers;
        if (!ctx->buffers[idx].queued && ctx->buffers[idx].start != nullptr) {
            memset(&buf, 0, sizeof(buf));
            buf.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
            buf.memory = V4L2_MEMORY_MMAP;
            buf.index = idx;
            ctx->next_write_idx = (idx + 1) % ctx->num_buffers;
            return static_cast<int>(idx);
        }
    }
    return -1;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_andrewchqng_usbwebcam_webcam_UvcNative_nativeOpen(
    JNIEnv* env,
    jobject /* thiz */,
    jstring jDevicePath,
    jint maxPayloadSize,
    jboolean isYuyvOnly,
    jint resolutionProfile
) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);

    if (g_ctx != nullptr) {
        std::lock_guard<std::mutex> slock(g_ctx->stream_mutex);
        stopV4l2StreamingLocked(g_ctx, false);
        if (g_ctx->v4l2_fd >= 0) close(g_ctx->v4l2_fd);
        if (g_ctx->stop_efd >= 0) close(g_ctx->stop_efd);
        delete g_ctx;
        g_ctx = nullptr;
    }

    const char* path = env->GetStringUTFChars(jDevicePath, nullptr);
    int fd = open(path, O_RDWR | O_NONBLOCK);
    if (fd < 0) {
        int err = errno;
        LOGE("Failed to open %s: %s (errno=%d)", path, strerror(err), err);
        env->ReleaseStringUTFChars(jDevicePath, path);
        return -err;
    }
    LOGI("Opened UVC V4L2 device: %s (fd=%d, maxPayload=%d, isYuyvOnly=%d, profile=%d)",
         path, fd, maxPayloadSize, isYuyvOnly, resolutionProfile);
    env->ReleaseStringUTFChars(jDevicePath, path);

    struct v4l2_capability cap {};
    if (ioctl(fd, VIDIOC_QUERYCAP, &cap) < 0) {
        int err = errno;
        LOGE("VIDIOC_QUERYCAP failed: %s", strerror(err));
        close(fd);
        return -err;
    }

    auto* ctx = new UvcContext();
    ctx->v4l2_fd = fd;
    ctx->stop_efd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    ctx->max_payload_size = (maxPayloadSize > 0) ? static_cast<uint32_t>(maxPayloadSize) : 1024u;
    ctx->is_yuyv_only = (isYuyvOnly == JNI_TRUE);

    initContextFormats(ctx, ctx->is_yuyv_only, resolutionProfile);

    int defaultFormatIndex = 1;
    int defaultFrameIndex = (ctx->formats[0].numFrames > 0) ? ctx->formats[0].numFrames : 1;
    uint32_t defaultInterval = ctx->formats[0].frames[defaultFrameIndex - 1].defaultInterval;

    fillStreamingControl(ctx, &ctx->probe_ctrl, defaultFormatIndex, defaultFrameIndex, defaultInterval);
    fillStreamingControl(ctx, &ctx->commit_ctrl, defaultFormatIndex, defaultFrameIndex, defaultInterval);

    ctx->active_fourcc = ctx->formats[0].fourcc;
    ctx->active_width = ctx->formats[0].frames[defaultFrameIndex - 1].width;
    ctx->active_height = ctx->formats[0].frames[defaultFrameIndex - 1].height;
    ctx->active_interval = defaultInterval;

    for (uint32_t evType = UVC_EVENT_FIRST; evType <= UVC_EVENT_LAST; ++evType) {
        struct v4l2_event_subscription sub {};
        sub.type = evType;
        if (ioctl(fd, VIDIOC_SUBSCRIBE_EVENT, &sub) < 0) {
            LOGW("VIDIOC_SUBSCRIBE_EVENT(%u) failed: %s", evType, strerror(errno));
        }
    }

    g_ctx = ctx;
    return fd;
}

extern "C" JNIEXPORT void JNICALL
Java_com_andrewchqng_usbwebcam_webcam_UvcNative_nativeRunEventLoop(
    JNIEnv* env,
    jobject /* thiz */,
    jobject callback
) {
    // Elevate UVC event responder thread priority for minimum control/setup latency
    setpriority(PRIO_PROCESS, 0, -16);

    UvcContext* ctx = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_ctx_mutex);
        ctx = g_ctx;
    }
    if (!ctx || ctx->v4l2_fd < 0) {
        LOGE("nativeRunEventLoop called without valid context");
        return;
    }

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onStreamStateChanged = env->GetMethodID(cbClass, "onStreamStateChanged", "(ZZIII)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Failed to find onStreamStateChanged method ID");
    }
    jmethodID onHostConnectionChanged = env->GetMethodID(cbClass, "onHostConnectionChanged", "(Z)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Failed to find onHostConnectionChanged method ID");
    }

    struct pollfd pfds[2]{};
    pfds[0].fd = ctx->v4l2_fd;
    pfds[0].events = POLLPRI | POLLERR | POLLHUP;
    pfds[1].fd = ctx->stop_efd;
    pfds[1].events = POLLIN;

    LOGI("Entering UVC V4L2 event loop (fd=%d)", ctx->v4l2_fd);

    while (true) {
        int ret = poll(pfds, 2, 1000);
        if (ret < 0) {
            if (errno == EINTR) continue;
            LOGE("poll() error in UVC loop: %s", strerror(errno));
            break;
        }
        if (ret == 0) {
            continue;
        }

        if (pfds[1].revents & POLLIN) {
            LOGI("Stop signal received on eventfd, exiting UVC loop");
            break;
        }

        if (pfds[0].revents & POLLPRI) {
            struct v4l2_event v4l2_ev {};
            while (ioctl(ctx->v4l2_fd, VIDIOC_DQEVENT, &v4l2_ev) == 0) {
                const auto* uvc_ev = reinterpret_cast<const struct uvc_event*>(&v4l2_ev.u.data);

                switch (v4l2_ev.type) {
                    case UVC_EVENT_CONNECT:
                        LOGI("UVC_EVENT_CONNECT (speed=%d)", uvc_ev->speed);
                        if (onHostConnectionChanged) {
                            env->CallVoidMethod(callback, onHostConnectionChanged, JNI_TRUE);
                        }
                        break;

                    case UVC_EVENT_DISCONNECT: {
                        LOGI("UVC_EVENT_DISCONNECT");
                        {
                            std::lock_guard<std::mutex> slock(ctx->stream_mutex);
                            // Do not call VIDIOC_STREAMOFF on DISCONNECT if not in delayed_status
                            stopV4l2StreamingLocked(ctx, false);
                        }
                        if (onStreamStateChanged) {
                            env->CallVoidMethod(callback, onStreamStateChanged, JNI_FALSE, JNI_TRUE, 0, 0, 0);
                        }
                        if (onHostConnectionChanged) {
                            env->CallVoidMethod(callback, onHostConnectionChanged, JNI_FALSE);
                        }
                        break;
                    }

                    case UVC_EVENT_SETUP: {
                        struct uvc_request_data resp {};
                        handleUvcSetupEvent(ctx, uvc_ev->req, resp);
                        if (ioctl(ctx->v4l2_fd, UVCIOC_SEND_RESPONSE, &resp) < 0) {
                            LOGW("UVCIOC_SEND_RESPONSE failed: %s", strerror(errno));
                        }
                        break;
                    }

                    case UVC_EVENT_DATA:
                        handleUvcDataEvent(ctx, uvc_ev->data);
                        break;

                    case UVC_EVENT_STREAMON: {
                        LOGI("UVC_EVENT_STREAMON");
                        bool started = false;
                        {
                            std::lock_guard<std::mutex> slock(ctx->stream_mutex);
                            started = startV4l2StreamingLocked(ctx);
                        }
                        if (started && onStreamStateChanged) {
                            bool isMjpeg = (ctx->active_fourcc == V4L2_PIX_FMT_MJPEG);
                            int fps = (ctx->active_interval > 0) ? static_cast<int>(10000000u / ctx->active_interval) : 30;
                            env->CallVoidMethod(
                                callback,
                                onStreamStateChanged,
                                JNI_TRUE,
                                isMjpeg ? JNI_TRUE : JNI_FALSE,
                                static_cast<jint>(ctx->active_width),
                                static_cast<jint>(ctx->active_height),
                                static_cast<jint>(fps)
                            );
                        }
                        break;
                    }

                    case UVC_EVENT_STREAMOFF: {
                        LOGI("UVC_EVENT_STREAMOFF");
                        {
                            std::lock_guard<std::mutex> slock(ctx->stream_mutex);
                            stopV4l2StreamingLocked(ctx, true);
                        }
                        if (onStreamStateChanged) {
                            env->CallVoidMethod(callback, onStreamStateChanged, JNI_FALSE, JNI_TRUE, 0, 0, 0);
                        }
                        break;
                    }

                    default:
                        break;
                }
            }
        }
    }

    LOGI("Exited UVC V4L2 event loop");
}

extern "C" JNIEXPORT void JNICALL
Java_com_andrewchqng_usbwebcam_webcam_UvcNative_nativeClose(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    if (!g_ctx) return;

    if (g_ctx->stop_efd >= 0) {
        uint64_t val = 1;
        (void)write(g_ctx->stop_efd, &val, sizeof(val));
    }

    {
        std::lock_guard<std::mutex> slock(g_ctx->stream_mutex);
        stopV4l2StreamingLocked(g_ctx, false);
    }

    if (g_ctx->v4l2_fd >= 0) {
        close(g_ctx->v4l2_fd);
        g_ctx->v4l2_fd = -1;
    }
    if (g_ctx->stop_efd >= 0) {
        close(g_ctx->stop_efd);
        g_ctx->stop_efd = -1;
    }

    delete g_ctx;
    g_ctx = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_andrewchqng_usbwebcam_webcam_UvcNative_nativeCanAcceptFrame(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint maxInFlight
) {
    UvcContext* ctx = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_ctx_mutex);
        ctx = g_ctx;
    }
    if (!ctx || !ctx->is_streaming.load()) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> slock(ctx->stream_mutex);
    if (!ctx->is_streaming.load() || ctx->v4l2_fd < 0 || ctx->num_buffers == 0) {
        return JNI_FALSE;
    }

    drainCompletedBuffersLocked(ctx);

    const uint32_t maxQueued = computeMaxQueuedBuffersLocked(ctx, maxInFlight);

    return (countQueuedBuffersLocked(ctx) < maxQueued) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_andrewchqng_usbwebcam_webcam_UvcNative_nativePushMjpegFrame(
    JNIEnv* env,
    jobject /* thiz */,
    jobject jDirectJpegBuf,
    jint length,
    jint maxInFlight
) {
    UvcContext* ctx = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_ctx_mutex);
        ctx = g_ctx;
    }
    if (!ctx || !ctx->is_streaming.load() || length <= 0) {
        return JNI_FALSE;
    }

    auto* srcPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(jDirectJpegBuf));
    if (!srcPtr) return JNI_FALSE;

    std::lock_guard<std::mutex> slock(ctx->stream_mutex);
    if (!ctx->is_streaming.load() || ctx->v4l2_fd < 0) return JNI_FALSE;

    struct v4l2_buffer buf {};
    int idx = acquireWritableBufferLocked(ctx, buf, maxInFlight);
    if (idx < 0) {
        return JNI_FALSE;
    }

    size_t copyLen = static_cast<size_t>(length);
    if (copyLen > ctx->buffers[idx].length) {
        copyLen = ctx->buffers[idx].length;
    }
    memcpy(ctx->buffers[idx].start, srcPtr, copyLen);
    buf.bytesused = static_cast<uint32_t>(copyLen);
    buf.sequence = ctx->frame_sequence++;

    struct timeval tv {};
    gettimeofday(&tv, nullptr);
    buf.timestamp = tv;

    if (ioctl(ctx->v4l2_fd, VIDIOC_QBUF, &buf) == 0) {
        ctx->buffers[idx].queued = true;
        return JNI_TRUE;
    } else {
        LOGE("VIDIOC_QBUF failed: %s (errno=%d)", strerror(errno), errno);
    }

    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_andrewchqng_usbwebcam_webcam_UvcNative_nativePushYuvToYuyvFrame(
    JNIEnv* env,
    jobject /* thiz */,
    jobject yBuf,
    jobject uBuf,
    jobject vBuf,
    jint srcW,
    jint srcH,
    jint yRowStride,
    jint uvRowStride,
    jint uvPixelStride,
    jint rotation,
    jboolean mirror,
    jfloat zoom,
    jfloat panX,
    jfloat panY,
    jint maxInFlight
) {
    UvcContext* ctx = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_ctx_mutex);
        ctx = g_ctx;
    }
    if (!ctx || !ctx->is_streaming.load()) {
        return JNI_FALSE;
    }

    const auto* yPlane = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    const auto* uPlane = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuf));
    const auto* vPlane = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!yPlane || !uPlane || !vPlane) return JNI_FALSE;

    std::lock_guard<std::mutex> slock(ctx->stream_mutex);
    if (!ctx->is_streaming.load() || ctx->v4l2_fd < 0) return JNI_FALSE;

    struct v4l2_buffer buf {};
    int idx = acquireWritableBufferLocked(ctx, buf, maxInFlight);
    if (idx < 0) {
        return JNI_FALSE;
    }

    const int dstW = ctx->active_width;
    const int dstH = ctx->active_height;
    const size_t neededBytes = static_cast<size_t>(dstW) * dstH * 2u;
    if (neededBytes > ctx->buffers[idx].length) {
        return JNI_FALSE;
    }

    auto* __restrict dst = static_cast<uint8_t*>(ctx->buffers[idx].start);
    const bool isIdentity = (rotation == 0 && !mirror && srcW == dstW && srcH == dstH && zoom <= 1.001f && panX == 0.0f && panY == 0.0f);

    if (isIdentity) {
        for (int y = 0; y < dstH; ++y) {
            const uint8_t* __restrict yRow = yPlane + y * yRowStride;
            const uint8_t* __restrict uRow = uPlane + (y >> 1) * uvRowStride;
            const uint8_t* __restrict vRow = vPlane + (y >> 1) * uvRowStride;
            uint8_t* __restrict dstRow = dst + y * (dstW * 2);

            int x = 0;
#if defined(__aarch64__) || defined(__ARM_NEON__)
            if (uvPixelStride == 2 && (vPlane + 1 == uPlane)) {
                // NV21 semi-planar source -> YUYV via NEON
                for (; x + 16 <= dstW; x += 16) {
                    uint8x16_t yVec = vld1q_u8(yRow + x);
                    uint8x8x2_t vuVec = vld2_u8(vRow + x); // val[0] = V, val[1] = U
                    uint8x8x2_t uvZip = vzip_u8(vuVec.val[1], vuVec.val[0]); // interleave U0,V0,U1,V1...
                    uint8x16_t uvVec = vcombine_u8(uvZip.val[0], uvZip.val[1]);
                    uint8x16x2_t yuyv;
                    yuyv.val[0] = yVec;
                    yuyv.val[1] = uvVec;
                    vst2q_u8(dstRow + x * 2, yuyv);
                }
            } else if (uvPixelStride == 2 && (uPlane + 1 == vPlane)) {
                // NV12 semi-planar source -> YUYV via NEON
                for (; x + 16 <= dstW; x += 16) {
                    uint8x16_t yVec = vld1q_u8(yRow + x);
                    uint8x16_t uvVec = vld1q_u8(uRow + x); // already U0,V0,U1,V1...
                    uint8x16x2_t yuyv;
                    yuyv.val[0] = yVec;
                    yuyv.val[1] = uvVec;
                    vst2q_u8(dstRow + x * 2, yuyv);
                }
            }
#endif
            for (; x < dstW; x += 2) {
                int uvOffset = (x >> 1) * uvPixelStride;
                dstRow[x * 2 + 0] = yRow[x];
                dstRow[x * 2 + 1] = uRow[uvOffset];
                dstRow[x * 2 + 2] = yRow[x + 1];
                dstRow[x * 2 + 3] = vRow[uvOffset];
            }
        }
    } else {
        const auto& cache = getCoordinateLuts(dstW, dstH, srcW, srcH, rotation, mirror == JNI_TRUE, zoom, panX, panY);
        const bool swappedAxes = cache.swappedAxes;
        const int* __restrict lutX = cache.lutX.data();
        const int* __restrict lutY = cache.lutY.data();

        if (!swappedAxes) {
            for (int y = 0; y < dstH; ++y) {
                const int sy = lutY[y];
                const uint8_t* __restrict yRow = yPlane + sy * yRowStride;
                const uint8_t* __restrict uRow = uPlane + (sy >> 1) * uvRowStride;
                const uint8_t* __restrict vRow = vPlane + (sy >> 1) * uvRowStride;
                uint8_t* __restrict dstRow = dst + y * (dstW * 2);

                for (int x = 0; x < dstW; x += 2) {
                    const int sx0 = lutX[x];
                    const int sx1 = lutX[x + 1];
                    const int uvIdx = (sx0 >> 1) * uvPixelStride;
                    dstRow[x * 2 + 0] = yRow[sx0];
                    dstRow[x * 2 + 1] = uRow[uvIdx];
                    dstRow[x * 2 + 2] = yRow[sx1];
                    dstRow[x * 2 + 3] = vRow[uvIdx];
                }
            }
        } else {
            for (int y = 0; y < dstH; ++y) {
                const int sx = lutY[y];
                const int uvColOffset = (sx >> 1) * uvPixelStride;
                uint8_t* __restrict dstRow = dst + y * (dstW * 2);

                for (int x = 0; x < dstW; x += 2) {
                    const int sy0 = lutX[x];
                    const int sy1 = lutX[x + 1];
                    const int uvIdx = (sy0 >> 1) * uvRowStride + uvColOffset;
                    dstRow[x * 2 + 0] = yPlane[sy0 * yRowStride + sx];
                    dstRow[x * 2 + 1] = uPlane[uvIdx];
                    dstRow[x * 2 + 2] = yPlane[sy1 * yRowStride + sx];
                    dstRow[x * 2 + 3] = vPlane[uvIdx];
                }
            }
        }
    }

    buf.bytesused = static_cast<uint32_t>(neededBytes);
    buf.sequence = ctx->frame_sequence++;
    struct timeval tv {};
    gettimeofday(&tv, nullptr);
    buf.timestamp = tv;

    if (ioctl(ctx->v4l2_fd, VIDIOC_QBUF, &buf) == 0) {
        ctx->buffers[idx].queued = true;
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_andrewchqng_usbwebcam_webcam_UvcNative_nativeYuv420ToNv21(
    JNIEnv* env,
    jobject /* thiz */,
    jobject yBuf,
    jobject uBuf,
    jobject vBuf,
    jint srcW,
    jint srcH,
    jint yRowStride,
    jint uvRowStride,
    jint uvPixelStride,
    jint dstW,
    jint dstH,
    jint rotation,
    jboolean mirror,
    jfloat zoom,
    jfloat panX,
    jfloat panY,
    jbyteArray outNv21
) {
    const auto* yPlane = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    const auto* uPlane = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuf));
    const auto* vPlane = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!yPlane || !uPlane || !vPlane) return;

    auto* __restrict nv21 = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(outNv21, nullptr));
    if (!nv21) return;

    uint8_t* __restrict dstY = nv21;
    uint8_t* __restrict dstVU = nv21 + (dstW * dstH);

    const bool isIdentity = (rotation == 0 && !mirror && srcW == dstW && srcH == dstH && zoom <= 1.001f && panX == 0.0f && panY == 0.0f);

    if (isIdentity) {
        if (yRowStride == dstW) {
            memcpy(dstY, yPlane, static_cast<size_t>(dstW) * dstH);
        } else {
            for (int y = 0; y < dstH; ++y) {
                memcpy(dstY + y * dstW, yPlane + y * yRowStride, dstW);
            }
        }

        const int chromaH = dstH >> 1;
        const int chromaW = dstW >> 1;
        if (uvPixelStride == 2 && (vPlane + 1 == uPlane)) {
            // Hardware buffer is already NV21 (V followed by U)
            if (uvRowStride == dstW) {
                memcpy(dstVU, vPlane, static_cast<size_t>(dstW) * chromaH);
            } else {
                for (int cy = 0; cy < chromaH; ++cy) {
                    memcpy(dstVU + cy * dstW, vPlane + cy * uvRowStride, dstW);
                }
            }
        } else if (uvPixelStride == 2 && (uPlane + 1 == vPlane)) {
            // Hardware buffer is NV12 (U followed by V) — swap pairs using ARM NEON
            for (int cy = 0; cy < chromaH; ++cy) {
                const uint8_t* __restrict uvSrc = uPlane + cy * uvRowStride;
                uint8_t* __restrict vuDst = dstVU + cy * dstW;
                int cx = 0;
#if defined(__aarch64__) || defined(__ARM_NEON__)
                for (; cx + 16 <= chromaW; cx += 16) {
                    uint8x16x2_t uv = vld2q_u8(uvSrc + cx * 2);
                    uint8x16x2_t vu;
                    vu.val[0] = uv.val[1]; // V
                    vu.val[1] = uv.val[0]; // U
                    vst2q_u8(vuDst + cx * 2, vu);
                }
#endif
                for (; cx < chromaW; ++cx) {
                    vuDst[cx * 2 + 0] = uvSrc[cx * 2 + 1];
                    vuDst[cx * 2 + 1] = uvSrc[cx * 2 + 0];
                }
            }
        } else {
            // Planar or arbitrary stride
            for (int cy = 0; cy < chromaH; ++cy) {
                const uint8_t* __restrict uRow = uPlane + cy * uvRowStride;
                const uint8_t* __restrict vRow = vPlane + cy * uvRowStride;
                uint8_t* __restrict vuRow = dstVU + cy * dstW;
                for (int cx = 0; cx < chromaW; ++cx) {
                    int idx = cx * uvPixelStride;
                    vuRow[cx * 2 + 0] = vRow[idx];
                    vuRow[cx * 2 + 1] = uRow[idx];
                }
            }
        }
    } else {
        // Fast 1D LUT-driven path for rotation (0/90/180/270), mirror, and aspect-preserving crop/scale
        const auto& cache = getCoordinateLuts(dstW, dstH, srcW, srcH, rotation, mirror == JNI_TRUE, zoom, panX, panY);
        const bool swappedAxes = cache.swappedAxes;
        const int* __restrict lutX = cache.lutX.data();
        const int* __restrict lutY = cache.lutY.data();

        if (!swappedAxes) {
            for (int y = 0; y < dstH; ++y) {
                const int sy = lutY[y];
                const uint8_t* __restrict ySrcRow = yPlane + sy * yRowStride;
                uint8_t* __restrict yOutRow = dstY + y * dstW;

                for (int x = 0; x < dstW; ++x) {
                    yOutRow[x] = ySrcRow[lutX[x]];
                }

                if ((y & 1) == 0) {
                    const uint8_t* __restrict uSrcRow = uPlane + (sy >> 1) * uvRowStride;
                    const uint8_t* __restrict vSrcRow = vPlane + (sy >> 1) * uvRowStride;
                    uint8_t* __restrict vuOutRow = dstVU + (y >> 1) * dstW;
                    for (int x = 0; x < dstW; x += 2) {
                        const int uvIdx = (lutX[x] >> 1) * uvPixelStride;
                        vuOutRow[x + 0] = vSrcRow[uvIdx];
                        vuOutRow[x + 1] = uSrcRow[uvIdx];
                    }
                }
            }
        } else {
            for (int y = 0; y < dstH; ++y) {
                const int sx = lutY[y];
                const int uvColOffset = (sx >> 1) * uvPixelStride;
                uint8_t* __restrict yOutRow = dstY + y * dstW;
                uint8_t* __restrict vuOutRow = ((y & 1) == 0) ? (dstVU + (y >> 1) * dstW) : nullptr;

                for (int x = 0; x < dstW; ++x) {
                    const int sy = lutX[x];
                    yOutRow[x] = yPlane[sy * yRowStride + sx];
                    if (vuOutRow != nullptr && (x & 1) == 0) {
                        const int uvIdx = (sy >> 1) * uvRowStride + uvColOffset;
                        vuOutRow[x + 0] = vPlane[uvIdx];
                        vuOutRow[x + 1] = uPlane[uvIdx];
                    }
                }
            }
        }
    }

    env->ReleasePrimitiveArrayCritical(outNv21, nv21, 0);
}
