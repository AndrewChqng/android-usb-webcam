#include "uvc_gadget.h"

#include <errno.h>
#include <string.h>

#include <algorithm>

namespace {

void populateFrameIntervals(UvcFrameDesc& frm, int capFps, uint32_t fallbackInterval) {
    static constexpr struct {
        int fps;
        uint32_t interval;
    } kFpsTiers[] = {
        {60, 166666u},
        {30, 333333u},
        {24, 416666u},
        {15, 666666u},
        {10, 1000000u},
        {5,  2000000u},
    };
    memset(frm.intervals, 0, sizeof(frm.intervals));
    int outIdx = 0;
    for (const auto& tier : kFpsTiers) {
        if (tier.fps <= capFps && outIdx < 8) {
            frm.intervals[outIdx++] = tier.interval;
        }
    }
    if (outIdx == 0) {
        frm.intervals[0] = fallbackInterval;
    }
    frm.defaultInterval = frm.intervals[0];
}

} // namespace

void initContextFormats(
    UvcContext* ctx,
    bool isYuyvOnly,
    int profileId,
    const uint16_t* widths,
    const uint16_t* heights,
    int numResolutions,
    int defaultFps
) {
    if (!ctx) return;

    const int configuredMaxFps = std::clamp(defaultFps, 5, 60);

    UvcFormatDesc mjpegFmt{};
    mjpegFmt.formatIndex = 1;
    mjpegFmt.fourcc = V4L2_PIX_FMT_MJPEG;

    UvcFormatDesc yuyvFmt{};
    yuyvFmt.formatIndex = isYuyvOnly ? 1 : 2;
    yuyvFmt.fourcc = V4L2_PIX_FMT_YUYV;

    if (numResolutions > 0 && widths != nullptr && heights != nullptr) {
        int count = std::min(numResolutions, 16);
        mjpegFmt.numFrames = static_cast<uint8_t>(count);
        yuyvFmt.numFrames = static_cast<uint8_t>(count);

        for (int i = 0; i < count; ++i) {
            uint16_t w = widths[i];
            uint16_t h = heights[i];
            uint64_t pixels = static_cast<uint64_t>(w) * h;

            // MJPEG frame intervals capped by configured max/default FPS
            int mjpegHwMaxFps = (pixels <= 1920ULL * 1080ULL) ? 60 : 30;
            int mjpegCapFps = std::min(configuredMaxFps, mjpegHwMaxFps);
            mjpegFmt.frames[i] = {};
            mjpegFmt.frames[i].frameIndex = static_cast<uint8_t>(i + 1);
            mjpegFmt.frames[i].width = w;
            mjpegFmt.frames[i].height = h;
            populateFrameIntervals(mjpegFmt.frames[i], mjpegCapFps, 333333u);

            // YUYV frame intervals capped by configured max/default FPS and USB bandwidth ceiling
            int yuyvHwMaxFps = (pixels <= 640ULL * 480ULL) ? 30 : ((pixels <= 1280ULL * 720ULL) ? 15 : 5);
            int yuyvCapFps = std::min(configuredMaxFps, yuyvHwMaxFps);
            yuyvFmt.frames[i] = {};
            yuyvFmt.frames[i].frameIndex = static_cast<uint8_t>(i + 1);
            yuyvFmt.frames[i].width = w;
            yuyvFmt.frames[i].height = h;
            populateFrameIntervals(yuyvFmt.frames[i], yuyvCapFps, 2000000u);
        }
    } else {
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

            case 7: // Portrait 9:16
                mjpegFmt.numFrames = 3;
                mjpegFmt.frames[0] = {1, 1080, 1920, 333333, {166666, 333333, 666666, 0}};
                mjpegFmt.frames[1] = {2, 720, 1280, 333333, {166666, 333333, 666666, 0}};
                mjpegFmt.frames[2] = {3, 1440, 2560, 333333, {166666, 333333, 666666, 0}};

                yuyvFmt.numFrames = 3;
                yuyvFmt.frames[0] = {1, 1080, 1920, 2000000, {2000000, 0, 0, 0}};
                yuyvFmt.frames[1] = {2, 720, 1280, 666666, {666666, 1000000, 2000000, 0}};
                yuyvFmt.frames[2] = {3, 1440, 2560, 2000000, {2000000, 0, 0, 0}};
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
        for (int i = 0; i < mjpegFmt.numFrames; ++i) {
            uint64_t pixels = static_cast<uint64_t>(mjpegFmt.frames[i].width) * mjpegFmt.frames[i].height;
            int capFps = std::min(configuredMaxFps, (pixels <= 1920ULL * 1080ULL) ? 60 : 30);
            populateFrameIntervals(mjpegFmt.frames[i], capFps, 333333u);
        }
        for (int i = 0; i < yuyvFmt.numFrames; ++i) {
            uint64_t pixels = static_cast<uint64_t>(yuyvFmt.frames[i].width) * yuyvFmt.frames[i].height;
            int hwMax = (pixels <= 640ULL * 480ULL) ? 30 : ((pixels <= 1280ULL * 720ULL) ? 15 : 5);
            populateFrameIntervals(yuyvFmt.frames[i], std::min(configuredMaxFps, hwMax), 2000000u);
        }
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
        for (int i = 0; i < 8 && frm.intervals[i] != 0; ++i) {
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
            int maxFmt = ctx->num_formats;
            uint8_t fmtNum = target->bFormatIndex ? target->bFormatIndex : 1;
            int fmtIdx = (fmtNum >= 1 && fmtNum <= maxFmt) ? (fmtNum - 1) : 0;
            const UvcFormatDesc& fmt = ctx->formats[fmtIdx];

            uint8_t frmNum = target->bFrameIndex ? target->bFrameIndex : ctx->default_frame_index;
            int frmIdx = (frmNum >= 1 && frmNum <= fmt.numFrames) ? (frmNum - 1) : 0;
            const UvcFrameDesc& frm = fmt.frames[frmIdx];

            uint32_t reqInterval = 0u;
            if (ctrl.bRequest == UVC_GET_MIN) {
                reqInterval = frm.intervals[0] ? frm.intervals[0] : frm.defaultInterval;
            } else if (ctrl.bRequest == UVC_GET_MAX) {
                reqInterval = 0xFFFFFFFFu;
            }

            struct uvc_streaming_control temp {};
            fillStreamingControl(
                ctx,
                &temp,
                fmtNum,
                frmNum,
                reqInterval
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
