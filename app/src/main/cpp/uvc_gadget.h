#pragma once

#include <linux/usb/ch9.h>
#include <linux/usb/video.h>
#include <linux/videodev2.h>
#include <stdint.h>

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

struct UvcFrameDesc {
    uint8_t frameIndex;
    uint16_t width;
    uint16_t height;
    uint32_t defaultInterval; // in 100ns units (e.g. 333333 = 30fps)
    uint32_t intervals[4];
};

struct UvcFormatDesc {
    uint8_t formatIndex;
    uint32_t fourcc; // V4L2_PIX_FMT_MJPEG or V4L2_PIX_FMT_YUYV
    uint8_t numFrames;
    UvcFrameDesc frames[4];
};
