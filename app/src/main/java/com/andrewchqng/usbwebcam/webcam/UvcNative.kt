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

    external fun nativeOpen(devicePath: String, maxPayloadSize: Int = 3072): Int
    external fun nativeRunEventLoop(callback: UvcEventCallback)
    external fun nativeClose()

    external fun nativePushMjpegFrame(directJpegBuffer: ByteBuffer, length: Int): Boolean

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
        mirror: Boolean
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
        outNv21: ByteArray
    )
}
