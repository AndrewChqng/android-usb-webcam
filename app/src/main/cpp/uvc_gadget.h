#pragma once

#include <android/log.h>
#include <linux/usb/ch9.h>
#include <linux/usb/video.h>
#include <linux/videodev2.h>
#include <stddef.h>
#include <stdint.h>

#include <atomic>
#include <mutex>

#define LOG_TAG "UvcGadgetJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// UAPI definitions from <linux/usb/g_uvc.h>
#ifndef UVC_EVENT_FIRST
#define UVC_EVENT_FIRST         (V4L2_EVENT_PRIVATE_START + 0)
#define UVC_EVENT_CONNECT       (V4L2_EVENT_PRIVATE_START + 0)
#define UVC_EVENT_DISCONNECT    (V4L2_EVENT_PRIVATE_START + 1)
#define UVC_EVENT_STREAMON      (V4L2_EVENT_PRIVATE_START + 2)
#define UVC_EVENT_STREAMOFF     (V4L2_EVENT_PRIVATE_START + 3)
#define UVC_EVENT_SETUP         (V4L2_EVENT_PRIVATE_START + 4)
#define UVC_EVENT_DATA          (V4L2_EVENT_PRIVATE_START + 5)
#define UVC_EVENT_LAST          (V4L2_EVENT_PRIVATE_START + 5)

struct uvc_request_data {
    __s32 length;
    __u8 data[60];
};

struct uvc_event {
    union {
        enum usb_device_speed speed;
        struct usb_ctrlrequest req;
        struct uvc_request_data data;
    };
};

#define UVCIOC_SEND_RESPONSE    _IOW('U', 1, struct uvc_request_data)
#endif

// Use 6 V4L2 MMAP buffers in a strict circular ring so a dequeued buffer is never
// overwritten while DWC3 scatter-gather DMA still has trailing USB requests in flight.
constexpr int kNumV4l2Buffers = 6;

struct UvcFrameDesc {
    uint8_t frameIndex;
    uint16_t width;
    uint16_t height;
    uint32_t defaultInterval; // in 100ns units (e.g. 333333 = 30fps)
    uint32_t intervals[8];
};

struct UvcFormatDesc {
    uint8_t formatIndex;
    uint32_t fourcc; // V4L2_PIX_FMT_MJPEG or V4L2_PIX_FMT_YUYV
    uint8_t numFrames;
    UvcFrameDesc frames[16];
};

struct MmapBuffer {
    void* start = nullptr;
    size_t length = 0;
    bool queued = false;
};

struct UvcContext {
    int v4l2_fd = -1;
    int stop_efd = -1;
    int dma_latency_fd = -1;
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
    uint8_t default_frame_index = 1;
    UvcFormatDesc formats[2]{};
};

// uvc_protocol.cpp
void initContextFormats(
    UvcContext* ctx,
    bool isYuyvOnly,
    int profileId,
    const uint16_t* widths = nullptr,
    const uint16_t* heights = nullptr,
    int numResolutions = 0,
    int defaultFps = 30
);
void fillStreamingControl(
    UvcContext* ctx,
    struct uvc_streaming_control* ctrl,
    int formatIndex,
    int frameIndex,
    uint32_t requestedInterval
);
void handleUvcSetupEvent(UvcContext* ctx, const struct usb_ctrlrequest& ctrl, struct uvc_request_data& resp);
void handleUvcDataEvent(UvcContext* ctx, const struct uvc_request_data& data);

// yuv_convert.cpp
void fillBlackYuyvFrame(uint8_t* dst, size_t frameBytes);
void convertYuv420ToYuyv(
    const uint8_t* yPlane,
    const uint8_t* uPlane,
    const uint8_t* vPlane,
    int srcW,
    int srcH,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    uint8_t* dst,
    int dstW,
    int dstH,
    int rotation,
    bool mirror,
    float zoom,
    float panX,
    float panY
);
void convertYuv420ToNv21(
    const uint8_t* yPlane,
    const uint8_t* uPlane,
    const uint8_t* vPlane,
    int srcW,
    int srcH,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    uint8_t* nv21,
    int dstW,
    int dstH,
    int rotation,
    bool mirror,
    float zoom,
    float panX,
    float panY
);
