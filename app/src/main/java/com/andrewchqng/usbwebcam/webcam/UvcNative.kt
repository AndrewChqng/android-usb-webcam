package com.andrewchqng.usbwebcam.webcam

import java.nio.ByteBuffer

interface UvcEventCallback {
    fun onStreamStateChanged(isStreaming: Boolean, isMjpeg: Boolean, width: Int, height: Int, fps: Int)
    fun onHostConnectionChanged(isConnected: Boolean)
}

object UvcNative {
    init {
        System.loadLibrary("usb_webcam")
    }

    external fun nativeOpen(
        devicePath: String,
        maxPayloadSize: Int = 1024,
        isYuyvOnly: Boolean = false,
        resolutionProfile: Int = 0
    ): Int
    external fun nativeRunEventLoop(callback: UvcEventCallback)
    external fun nativeClose()

    /**
     * Drains completed V4L2 buffers via non-blocking VIDIOC_DQBUF and checks if a write slot
     * is available without exceeding [maxInFlight] queued kernel buffers.
     * Calling this before YUV/JPEG encoding avoids wasted CPU work and prevents buffer tearing.
     */
    external fun nativeCanAcceptFrame(maxInFlight: Int): Boolean

    external fun nativePushMjpegFrame(directJpegBuffer: ByteBuffer, length: Int, maxInFlight: Int): Boolean

    external fun nativePushYuvToYuyvFrame(
        yBuf: ByteBuffer,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        rotation: Int,
        mirror: Boolean,
        zoom: Float,
        panX: Float,
        panY: Float,
        maxInFlight: Int
    ): Boolean

    external fun nativeYuv420ToNv21(
        yBuf: ByteBuffer,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        dstWidth: Int,
        dstHeight: Int,
        rotation: Int,
        mirror: Boolean,
        zoom: Float,
        panX: Float,
        panY: Float,
        outNv21: ByteArray
    )
}
