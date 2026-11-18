package com.andrewchqng.usbwebcam.hid_utils

import java.util.Locale

data class UvcFrameConfig(
    val dirName: String,
    val width: Int,
    val height: Int,
    val minBitRate: Long,
    val maxBitRate: Long,
    val maxBufSize: Long,
    val defaultInterval: Long,
    val intervals: List<Long>
)

data class UvcFormatFrames(
    val mjpegFrames: List<UvcFrameConfig>,
    val yuyvFrames: List<UvcFrameConfig>
)

/**
 * Pure ConfigFS UVC frame descriptor builder and script generator.
 */
object UvcFrameConfigBuilder {
    val ALL_FPS_TIERS = listOf(
        60 to 166666L,
        30 to 333333L,
        24 to 416666L,
        15 to 666666L,
        10 to 1000000L,
        5 to 2000000L
    )

    fun buildFormatFrames(
        widths: IntArray,
        heights: IntArray,
        configuredMaxFps: Int
    ): UvcFormatFrames {
        val mjpegFrames = mutableListOf<UvcFrameConfig>()
        val yuyvFrames = mutableListOf<UvcFrameConfig>()
        val clampedMaxFps = configuredMaxFps.coerceIn(5, 60)
        val count = minOf(widths.size, heights.size)

        for (i in 0 until count) {
            val w = widths[i]
            val h = heights[i]
            val dirName = String.format(Locale.US, "f%02d_%dx%d", i + 1, w, h)
            val pixels = w.toLong() * h
            val maxBufSize = pixels * 2

            val mjpegCapFps = minOf(clampedMaxFps, if (pixels <= 1920L * 1080L) 60 else 30)
            val mjpegIntervals = ALL_FPS_TIERS
                .filter { it.first <= mjpegCapFps }
                .map { it.second }
                .ifEmpty { listOf(333333L) }
            mjpegFrames.add(
                UvcFrameConfig(
                    dirName = dirName,
                    width = w,
                    height = h,
                    minBitRate = (pixels * 16 * 5) / 8,
                    maxBitRate = pixels * 16 * mjpegCapFps,
                    maxBufSize = maxBufSize,
                    defaultInterval = mjpegIntervals.first(),
                    intervals = mjpegIntervals
                )
            )

            val yuyvHwMaxFps = when {
                pixels <= 640L * 480L -> 30
                pixels <= 1280L * 720L -> 15
                else -> 5
            }
            val yuyvCapFps = minOf(clampedMaxFps, yuyvHwMaxFps)
            val yuyvIntervals = ALL_FPS_TIERS
                .filter { it.first <= yuyvCapFps }
                .map { it.second }
                .ifEmpty { listOf(2000000L) }
            yuyvFrames.add(
                UvcFrameConfig(
                    dirName = dirName,
                    width = w,
                    height = h,
                    minBitRate = pixels * 16 * 5,
                    maxBitRate = pixels * 16 * yuyvCapFps,
                    maxBufSize = maxBufSize,
                    defaultInterval = yuyvIntervals.first(),
                    intervals = yuyvIntervals
                )
            )
        }

        return UvcFormatFrames(mjpegFrames = mjpegFrames, yuyvFrames = yuyvFrames)
    }

    fun generateConfigFsCommands(
        formatSubpath: String,
        frames: List<UvcFrameConfig>,
        defaultFrameIndex: Int
    ): String = buildString {
        for (f in frames) {
            val p = "\${UVC}/$formatSubpath/${f.dirName}"
            val intervalsFormatted = f.intervals.joinToString("""\n""") + """\n"""
            appendLine("            mkdir -p \"$p\"")
            appendLine("            echo ${f.width} > \"$p/wWidth\"")
            appendLine("            echo ${f.height} > \"$p/wHeight\"")
            appendLine("            echo ${f.minBitRate} > \"$p/dwMinBitRate\"")
            appendLine("            echo ${f.maxBitRate} > \"$p/dwMaxBitRate\"")
            appendLine("            echo ${f.maxBufSize} > \"$p/dwMaxVideoFrameBufferSize\"")
            appendLine("            echo ${f.defaultInterval} > \"$p/dwDefaultFrameInterval\"")
            appendLine("            printf \"$intervalsFormatted\" > \"$p/dwFrameInterval\"")
        }
        appendLine("            echo $defaultFrameIndex > \"\${UVC}/$formatSubpath/bDefaultFrameIndex\" 2>/dev/null || true")
    }
}
