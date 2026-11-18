#include "uvc_gadget.h"

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

#include <algorithm>
#include <mutex>

namespace {

std::mutex g_ctx_mutex;
UvcContext* g_ctx = nullptr;

// Minimal valid 16x16 black JPEG used to prime V4L2 buffer 0 on STREAMON before Camera2 delivers its first frame
const uint8_t kTinyBlackJpeg[] = {
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

    if (ctx->dma_latency_fd >= 0) {
        close(ctx->dma_latency_fd);
        ctx->dma_latency_fd = -1;
    }
}

bool startV4l2StreamingLocked(UvcContext* ctx) {
    stopV4l2StreamingLocked(ctx, false);

    // Acquire PM QoS CPU DMA latency lock (0 us) while streaming to prevent deep C-state USB jitter
    int qosFd = open("/dev/cpu_dma_latency", O_WRONLY | O_CLOEXEC);
    if (qosFd >= 0) {
        int32_t targetLatency = 0;
        if (write(qosFd, &targetLatency, sizeof(targetLatency)) == sizeof(targetLatency)) {
            ctx->dma_latency_fd = qosFd;
            LOGI("Acquired PM QoS /dev/cpu_dma_latency=0 lock");
        } else {
            close(qosFd);
        }
    }

    struct v4l2_format fmt {};
    fmt.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    fmt.fmt.pix.width = ctx->active_width;
    fmt.fmt.pix.height = ctx->active_height;
    fmt.fmt.pix.pixelformat = ctx->active_fourcc;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;
    if (ctx->active_fourcc == V4L2_PIX_FMT_MJPEG) {
        uint32_t rawBytes = static_cast<uint32_t>(ctx->active_width) * ctx->active_height * 2u;
        uint32_t clamped = std::max<uint32_t>(rawBytes / 2u, 2u * 1024u * 1024u);
        fmt.fmt.pix.sizeimage = std::min<uint32_t>(rawBytes, clamped);
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
            size_t frameBytes = static_cast<size_t>(ctx->active_width) * ctx->active_height * 2u;
            if (frameBytes > ctx->buffers[0].length) frameBytes = ctx->buffers[0].length;
            fillBlackYuyvFrame(static_cast<uint8_t*>(ctx->buffers[0].start), frameBytes);
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

inline uint32_t computeMaxQueuedBuffersLocked(const UvcContext* ctx, int maxInFlight) {
    if (ctx->num_buffers <= 1) return 1;
    uint32_t cap = (maxInFlight >= 1) ? static_cast<uint32_t>(maxInFlight) : 1u;
    uint32_t maxAllowed = ctx->num_buffers - 1;
    return (cap < maxAllowed) ? cap : maxAllowed;
}

int acquireWritableBufferLocked(UvcContext* ctx, struct v4l2_buffer& buf, int maxInFlight) {
    if (ctx->num_buffers == 0) return -1;

    // 1. Drain any completed buffers returned by USB stack
    drainCompletedBuffersLocked(ctx);

    // 2. Enforce strict zero-tear queue cap
    const uint32_t maxQueued = computeMaxQueuedBuffersLocked(ctx, maxInFlight);
    if (countQueuedBuffersLocked(ctx) >= maxQueued) {
        return -1;
    }

    // 3. Pick the next unqueued buffer strictly in circular ring order
    for (uint32_t offset = 0; offset < ctx->num_buffers; ++offset) {
        uint32_t idx = (ctx->next_write_idx + offset) % ctx->num_buffers;
        if (!ctx->buffers[idx].queued) {
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
    jint resolutionProfile,
    jintArray jWidths,
    jintArray jHeights,
    jint defaultFrameIndex,
    jint defaultFps
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
    LOGI("Opened UVC V4L2 device: %s (fd=%d, maxPayload=%d, isYuyvOnly=%d, profile=%d, defaultFrameIndex=%d, defaultFps=%d)",
         path, fd, maxPayloadSize, isYuyvOnly, resolutionProfile, defaultFrameIndex, defaultFps);
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

    uint16_t localWidths[16];
    uint16_t localHeights[16];
    int numRes = 0;
    if (jWidths != nullptr && jHeights != nullptr) {
        jsize lenW = env->GetArrayLength(jWidths);
        jsize lenH = env->GetArrayLength(jHeights);
        jint* wPtr = env->GetIntArrayElements(jWidths, nullptr);
        jint* hPtr = env->GetIntArrayElements(jHeights, nullptr);
        numRes = std::min({static_cast<int>(lenW), static_cast<int>(lenH), 16});
        for (int i = 0; i < numRes; ++i) {
            localWidths[i] = static_cast<uint16_t>(wPtr[i]);
            localHeights[i] = static_cast<uint16_t>(hPtr[i]);
        }
        env->ReleaseIntArrayElements(jWidths, wPtr, JNI_ABORT);
        env->ReleaseIntArrayElements(jHeights, hPtr, JNI_ABORT);
    }

    initContextFormats(ctx, ctx->is_yuyv_only, resolutionProfile, localWidths, localHeights, numRes, defaultFps);

    int defaultFormatIndex = 1;
    int resolvedDefFrame = (defaultFrameIndex >= 1 && defaultFrameIndex <= ctx->formats[0].numFrames)
        ? defaultFrameIndex
        : ((ctx->formats[0].numFrames > 0) ? ctx->formats[0].numFrames : 1);
    ctx->default_frame_index = static_cast<uint8_t>(resolvedDefFrame);
    uint32_t defaultInterval = ctx->formats[0].frames[resolvedDefFrame - 1].defaultInterval;

    fillStreamingControl(ctx, &ctx->probe_ctrl, defaultFormatIndex, resolvedDefFrame, defaultInterval);
    fillStreamingControl(ctx, &ctx->commit_ctrl, defaultFormatIndex, resolvedDefFrame, defaultInterval);

    ctx->active_fourcc = ctx->formats[0].fourcc;
    ctx->active_width = ctx->formats[0].frames[resolvedDefFrame - 1].width;
    ctx->active_height = ctx->formats[0].frames[resolvedDefFrame - 1].height;
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
                            int fps = (ctx->active_interval > 0)
                                ? static_cast<int>((10000000u + (ctx->active_interval / 2u)) / ctx->active_interval)
                                : 30;
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

    convertYuv420ToYuyv(
        yPlane, uPlane, vPlane,
        srcW, srcH,
        yRowStride, uvRowStride, uvPixelStride,
        static_cast<uint8_t*>(ctx->buffers[idx].start), dstW, dstH,
        rotation, mirror == JNI_TRUE, zoom, panX, panY
    );

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

    convertYuv420ToNv21(
        yPlane, uPlane, vPlane,
        srcW, srcH,
        yRowStride, uvRowStride, uvPixelStride,
        nv21, dstW, dstH,
        rotation, mirror == JNI_TRUE, zoom, panX, panY
    );

    env->ReleasePrimitiveArrayCritical(outNv21, nv21, 0);
}
