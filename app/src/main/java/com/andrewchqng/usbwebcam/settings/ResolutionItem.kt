package com.andrewchqng.usbwebcam.settings

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.ImageFormat
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ResolutionItem(
    val width: Int,
    val height: Int,
    val isCustom: Boolean = false
) : Parcelable, Comparable<ResolutionItem> {
    val key: String get() = "${width}x${height}"

    val aspectRatio: String get() {
        val gcdVal = gcd(width, height)
        val wR = width / gcdVal
        val hR = height / gcdVal
        return when {
            width * 9 == height * 16 -> "16:9"
            height * 9 == width * 16 -> "9:16"
            width * 3 == height * 4 -> "4:3"
            height * 3 == width * 4 -> "3:4"
            width == height -> "1:1"
            width * 10 == height * 16 -> "16:10"
            width * 9 == height * 21 -> "21:9"
            width * 9 == height * 18 -> "18:9"
            else -> "$wR:$hR"
        }
    }

    val isPortrait: Boolean get() = height > width

    val displayName: String get() {
        val name = when {
            width == 3840 && height == 2160 -> "4K UHD"
            width == 2560 && height == 1440 -> "2K QHD"
            width == 1920 && height == 1080 -> "1080p FHD"
            width == 1280 && height == 720 -> "720p HD"
            width == 640 && height == 480 -> "480p SD (VGA)"
            width == 640 && height == 360 -> "360p nHD"
            width == 352 && height == 288 -> "288p CIF"
            width == 320 && height == 240 -> "240p QVGA"
            width == 176 && height == 144 -> "144p QCIF"
            width == 2160 && height == 3840 -> "4K Portrait"
            width == 1440 && height == 2560 -> "2K Portrait"
            width == 1080 && height == 1920 -> "1080p Portrait"
            width == 720 && height == 1280 -> "720p Portrait"
            width == 360 && height == 640 -> "360p Portrait"
            width == 240 && height == 320 -> "240p Portrait"
            width == 144 && height == 176 -> "144p Portrait"
            width == 1600 && height == 1200 -> "1200p (4:3)"
            width == 1280 && height == 960 -> "960p (4:3)"
            width == 1080 && height == 1080 -> "1080p Square"
            else -> "${width}x${height}"
        }
        return "$name (${width}x${height}, $aspectRatio)"
    }

    override fun compareTo(other: ResolutionItem): Int {
        val pixels = this.width.toLong() * this.height
        val otherPixels = other.width.toLong() * other.height
        return when {
            pixels != otherPixels -> otherPixels.compareTo(pixels) // descending by pixel count
            this.width != other.width -> other.width.compareTo(this.width)
            else -> other.height.compareTo(this.height)
        }
    }

    companion object {
        fun fromKey(key: String, isCustom: Boolean = false): ResolutionItem? {
            val parts = key.trim().lowercase().split("x")
            if (parts.size != 2) return null
            val w = parts[0].toIntOrNull() ?: return null
            val h = parts[1].toIntOrNull() ?: return null
            // Technical limits: minimum 16x16 (JPEG DCT macroblock boundary), max 7680x7680 (8K)
            if (w < 16 || h < 16 || w > 7680 || h > 7680) return null
            return ResolutionItem(w, h, isCustom)
        }

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        val BASELINE_RESOLUTIONS: List<ResolutionItem> = listOf(
            ResolutionItem(3840, 2160),
            ResolutionItem(2560, 1440),
            ResolutionItem(1920, 1080),
            ResolutionItem(1280, 720),
            ResolutionItem(640, 480),
            ResolutionItem(640, 360),
            ResolutionItem(352, 288),
            ResolutionItem(320, 240),
            ResolutionItem(176, 144),
            // Standard vertical companion resolutions (for mobile/OBS streaming)
            ResolutionItem(1080, 1920),
            ResolutionItem(720, 1280),
            ResolutionItem(1440, 2560),
            ResolutionItem(2160, 3840),
            ResolutionItem(360, 640),
            ResolutionItem(240, 320),
            ResolutionItem(144, 176)
        )

        fun discoverSensorResolutions(cameraManager: CameraManager): List<ResolutionItem> {
            val found = mutableSetOf<ResolutionItem>()
            found.addAll(BASELINE_RESOLUTIONS)

            runCatching {
                for (id in cameraManager.cameraIdList) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: continue
                    for (s in yuvSizes) {
                        // Do not hide resolutions considered too small unless for technical reasons:
                        // 1. width >= 16 and height >= 16 (minimum DCT macroblock / block size for JPEG and YUV encoders)
                        // 2. width and height are even (required for YUV 4:2:0 and YUYV 4:2:2 chroma subsampling)
                        if (s.width >= 16 && s.height >= 16 && s.width % 2 == 0 && s.height % 2 == 0) {
                            found.add(ResolutionItem(s.width, s.height))
                            // If landscape, also offer matching portrait companion
                            if (s.width > s.height && s.width <= 3840) {
                                found.add(ResolutionItem(s.height, s.width))
                            }
                        }
                    }
                }
            }

            return found.sorted()
        }

        fun getPresetResolutions(presetKey: String): Pair<List<ResolutionItem>, ResolutionItem> {
            return when (presetKey) {
                "max_4k" -> listOf(
                    ResolutionItem(3840, 2160),
                    ResolutionItem(2560, 1440),
                    ResolutionItem(1920, 1080),
                    ResolutionItem(1280, 720),
                    ResolutionItem(640, 480),
                    ResolutionItem(640, 360)
                ) to ResolutionItem(3840, 2160)

                "max_1440p" -> listOf(
                    ResolutionItem(2560, 1440),
                    ResolutionItem(1920, 1080),
                    ResolutionItem(1280, 720),
                    ResolutionItem(640, 480),
                    ResolutionItem(640, 360)
                ) to ResolutionItem(2560, 1440)

                "max_720p" -> listOf(
                    ResolutionItem(1280, 720),
                    ResolutionItem(640, 480),
                    ResolutionItem(640, 360)
                ) to ResolutionItem(1280, 720)

                "portrait_9_16" -> listOf(
                    ResolutionItem(1080, 1920),
                    ResolutionItem(720, 1280),
                    ResolutionItem(1440, 2560)
                ) to ResolutionItem(1080, 1920)

                "only_1080p" -> listOf(
                    ResolutionItem(1920, 1080)
                ) to ResolutionItem(1920, 1080)

                "only_720p" -> listOf(
                    ResolutionItem(1280, 720)
                ) to ResolutionItem(1280, 720)

                "only_4k" -> listOf(
                    ResolutionItem(3840, 2160)
                ) to ResolutionItem(3840, 2160)

                "max_1080p" -> listOf(
                    ResolutionItem(1920, 1080),
                    ResolutionItem(1280, 720),
                    ResolutionItem(640, 480),
                    ResolutionItem(640, 360)
                ) to ResolutionItem(1920, 1080)

                else -> listOf(
                    ResolutionItem(1920, 1080),
                    ResolutionItem(1280, 720),
                    ResolutionItem(640, 480),
                    ResolutionItem(640, 360)
                ) to ResolutionItem(1920, 1080)
            }
        }
    }
}
