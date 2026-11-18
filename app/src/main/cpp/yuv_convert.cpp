#include "uvc_gadget.h"

#include <string.h>

#include <cmath>
#include <vector>

#if defined(__aarch64__) || defined(__ARM_NEON__)
#include <arm_neon.h>

namespace {

inline void transpose8x8_u8(
    uint8x8_t& r0, uint8x8_t& r1, uint8x8_t& r2, uint8x8_t& r3,
    uint8x8_t& r4, uint8x8_t& r5, uint8x8_t& r6, uint8x8_t& r7
) {
    uint8x8x2_t t0 = vtrn_u8(r0, r1);
    uint8x8x2_t t1 = vtrn_u8(r2, r3);
    uint8x8x2_t t2 = vtrn_u8(r4, r5);
    uint8x8x2_t t3 = vtrn_u8(r6, r7);

    uint16x4x2_t q0 = vtrn_u16(vreinterpret_u16_u8(t0.val[0]), vreinterpret_u16_u8(t1.val[0]));
    uint16x4x2_t q1 = vtrn_u16(vreinterpret_u16_u8(t0.val[1]), vreinterpret_u16_u8(t1.val[1]));
    uint16x4x2_t q2 = vtrn_u16(vreinterpret_u16_u8(t2.val[0]), vreinterpret_u16_u8(t3.val[0]));
    uint16x4x2_t q3 = vtrn_u16(vreinterpret_u16_u8(t2.val[1]), vreinterpret_u16_u8(t3.val[1]));

    uint32x2x2_t d0 = vtrn_u32(vreinterpret_u32_u16(q0.val[0]), vreinterpret_u32_u16(q2.val[0]));
    uint32x2x2_t d1 = vtrn_u32(vreinterpret_u32_u16(q1.val[0]), vreinterpret_u32_u16(q3.val[0]));
    uint32x2x2_t d2 = vtrn_u32(vreinterpret_u32_u16(q0.val[1]), vreinterpret_u32_u16(q2.val[1]));
    uint32x2x2_t d3 = vtrn_u32(vreinterpret_u32_u16(q1.val[1]), vreinterpret_u32_u16(q3.val[1]));

    r0 = vreinterpret_u8_u32(d0.val[0]);
    r1 = vreinterpret_u8_u32(d1.val[0]);
    r2 = vreinterpret_u8_u32(d2.val[0]);
    r3 = vreinterpret_u8_u32(d3.val[0]);
    r4 = vreinterpret_u8_u32(d0.val[1]);
    r5 = vreinterpret_u8_u32(d1.val[1]);
    r6 = vreinterpret_u8_u32(d2.val[1]);
    r7 = vreinterpret_u8_u32(d3.val[1]);
}

} // namespace
#endif

namespace {

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

thread_local CoordinateLutCache tl_lutCache;

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

} // namespace

void fillBlackYuyvFrame(uint8_t* dst, size_t frameBytes) {
    size_t j = 0;
#if defined(__aarch64__) || defined(__ARM_NEON__)
    uint8x16_t blackYuyv = vreinterpretq_u8_u16(vdupq_n_u16(0x8000));
    for (; j + 16 <= frameBytes; j += 16) {
        vst1q_u8(dst + j, blackYuyv);
    }
#endif
    for (; j + 1 < frameBytes; j += 2) {
        dst[j] = 0x00;
        dst[j + 1] = 0x80;
    }
}

void convertYuv420ToYuyv(
    const uint8_t* yPlane,
    const uint8_t* uPlane,
    const uint8_t* vPlane,
    int srcW,
    int srcH,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    uint8_t* __restrict dst,
    int dstW,
    int dstH,
    int rotation,
    bool mirror,
    float zoom,
    float panX,
    float panY
) {
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
            } else if (uvPixelStride == 2) {
                // Separate or non-adjacent semi-planar UV with stride 2 -> YUYV via NEON
                for (; x + 16 <= dstW; x += 16) {
                    uint8x16_t yVec = vld1q_u8(yRow + x);
                    uint8x8x2_t uDeint = vld2_u8(uRow + x);
                    uint8x8x2_t vDeint = vld2_u8(vRow + x);
                    uint8x8x2_t uvZip = vzip_u8(uDeint.val[0], vDeint.val[0]);
                    uint8x16_t uvVec = vcombine_u8(uvZip.val[0], uvZip.val[1]);
                    uint8x16x2_t yuyv;
                    yuyv.val[0] = yVec;
                    yuyv.val[1] = uvVec;
                    vst2q_u8(dstRow + x * 2, yuyv);
                }
            } else if (uvPixelStride == 1) {
                // Planar I420 / YV12 source -> YUYV via NEON
                for (; x + 16 <= dstW; x += 16) {
                    uint8x16_t yVec = vld1q_u8(yRow + x);
                    uint8x8_t uVec = vld1_u8(uRow + (x >> 1));
                    uint8x8_t vVec = vld1_u8(vRow + (x >> 1));
                    uint8x8x2_t uvZip = vzip_u8(uVec, vVec);
                    uint8x16_t uvVec = vcombine_u8(uvZip.val[0], uvZip.val[1]);
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
        const auto& cache = getCoordinateLuts(dstW, dstH, srcW, srcH, rotation, mirror, zoom, panX, panY);
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
}

void convertYuv420ToNv21(
    const uint8_t* yPlane,
    const uint8_t* uPlane,
    const uint8_t* vPlane,
    int srcW,
    int srcH,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    uint8_t* __restrict nv21,
    int dstW,
    int dstH,
    int rotation,
    bool mirror,
    float zoom,
    float panX,
    float panY
) {
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
        } else if (uvPixelStride == 2) {
            // Separate or non-adjacent semi-planar UV with stride 2 -> NV21 via ARM NEON
            for (int cy = 0; cy < chromaH; ++cy) {
                const uint8_t* __restrict uRow = uPlane + cy * uvRowStride;
                const uint8_t* __restrict vRow = vPlane + cy * uvRowStride;
                uint8_t* __restrict vuRow = dstVU + cy * dstW;
                int cx = 0;
#if defined(__aarch64__) || defined(__ARM_NEON__)
                for (; cx + 16 <= chromaW; cx += 16) {
                    uint8x16x2_t uDeint = vld2q_u8(uRow + cx * 2);
                    uint8x16x2_t vDeint = vld2q_u8(vRow + cx * 2);
                    uint8x16x2_t vu;
                    vu.val[0] = vDeint.val[0];
                    vu.val[1] = uDeint.val[0];
                    vst2q_u8(vuRow + cx * 2, vu);
                }
#endif
                for (; cx < chromaW; ++cx) {
                    vuRow[cx * 2 + 0] = vRow[cx * 2];
                    vuRow[cx * 2 + 1] = uRow[cx * 2];
                }
            }
        } else if (uvPixelStride == 1) {
            // Planar I420 -> NV21 via ARM NEON
            for (int cy = 0; cy < chromaH; ++cy) {
                const uint8_t* __restrict uRow = uPlane + cy * uvRowStride;
                const uint8_t* __restrict vRow = vPlane + cy * uvRowStride;
                uint8_t* __restrict vuRow = dstVU + cy * dstW;
                int cx = 0;
#if defined(__aarch64__) || defined(__ARM_NEON__)
                for (; cx + 16 <= chromaW; cx += 16) {
                    uint8x16x2_t vu;
                    vu.val[0] = vld1q_u8(vRow + cx);
                    vu.val[1] = vld1q_u8(uRow + cx);
                    vst2q_u8(vuRow + cx * 2, vu);
                }
#endif
                for (; cx < chromaW; ++cx) {
                    vuRow[cx * 2 + 0] = vRow[cx];
                    vuRow[cx * 2 + 1] = uRow[cx];
                }
            }
        } else {
            // Arbitrary stride fallback
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
        const auto& cache = getCoordinateLuts(dstW, dstH, srcW, srcH, rotation, mirror, zoom, panX, panY);
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
            bool lumaHandledByNeon = false;
#if defined(__aarch64__) || defined(__ARM_NEON__)
            if (!mirror && zoom <= 1.001f && srcW == dstH && srcH == dstW && (rotation == 90 || rotation == 270)) {
                lumaHandledByNeon = true;
                const int alignedW = dstW & ~7;
                const int alignedH = dstH & ~7;

                for (int y = 0; y < alignedH; y += 8) {
                    const int sxBase = (rotation == 270) ? (srcW - 8 - y) : y;
                    for (int x = 0; x < alignedW; x += 8) {
                        const int syBase = (rotation == 90) ? (srcH - 8 - x) : x;
                        uint8x8_t r0 = vld1_u8(yPlane + (syBase + 0) * yRowStride + sxBase);
                        uint8x8_t r1 = vld1_u8(yPlane + (syBase + 1) * yRowStride + sxBase);
                        uint8x8_t r2 = vld1_u8(yPlane + (syBase + 2) * yRowStride + sxBase);
                        uint8x8_t r3 = vld1_u8(yPlane + (syBase + 3) * yRowStride + sxBase);
                        uint8x8_t r4 = vld1_u8(yPlane + (syBase + 4) * yRowStride + sxBase);
                        uint8x8_t r5 = vld1_u8(yPlane + (syBase + 5) * yRowStride + sxBase);
                        uint8x8_t r6 = vld1_u8(yPlane + (syBase + 6) * yRowStride + sxBase);
                        uint8x8_t r7 = vld1_u8(yPlane + (syBase + 7) * yRowStride + sxBase);

                        transpose8x8_u8(r0, r1, r2, r3, r4, r5, r6, r7);

                        if (rotation == 90) {
                            vst1_u8(dstY + (y + 0) * dstW + x, vrev64_u8(r0));
                            vst1_u8(dstY + (y + 1) * dstW + x, vrev64_u8(r1));
                            vst1_u8(dstY + (y + 2) * dstW + x, vrev64_u8(r2));
                            vst1_u8(dstY + (y + 3) * dstW + x, vrev64_u8(r3));
                            vst1_u8(dstY + (y + 4) * dstW + x, vrev64_u8(r4));
                            vst1_u8(dstY + (y + 5) * dstW + x, vrev64_u8(r5));
                            vst1_u8(dstY + (y + 6) * dstW + x, vrev64_u8(r6));
                            vst1_u8(dstY + (y + 7) * dstW + x, vrev64_u8(r7));
                        } else {
                            vst1_u8(dstY + (y + 0) * dstW + x, r7);
                            vst1_u8(dstY + (y + 1) * dstW + x, r6);
                            vst1_u8(dstY + (y + 2) * dstW + x, r5);
                            vst1_u8(dstY + (y + 3) * dstW + x, r4);
                            vst1_u8(dstY + (y + 4) * dstW + x, r3);
                            vst1_u8(dstY + (y + 5) * dstW + x, r2);
                            vst1_u8(dstY + (y + 6) * dstW + x, r1);
                            vst1_u8(dstY + (y + 7) * dstW + x, r0);
                        }
                    }
                    // Remaining x columns if dstW not multiple of 8
                    for (int row = 0; row < 8; ++row) {
                        const int dy = y + row;
                        const int sx = lutY[dy];
                        uint8_t* __restrict yOutRow = dstY + dy * dstW;
                        for (int x = alignedW; x < dstW; ++x) {
                            yOutRow[x] = yPlane[lutX[x] * yRowStride + sx];
                        }
                    }
                }
                // Remaining y rows if dstH not multiple of 8
                for (int y = alignedH; y < dstH; ++y) {
                    const int sx = lutY[y];
                    uint8_t* __restrict yOutRow = dstY + y * dstW;
                    for (int x = 0; x < dstW; ++x) {
                        yOutRow[x] = yPlane[lutX[x] * yRowStride + sx];
                    }
                }
            }
#endif
            for (int y = 0; y < dstH; ++y) {
                const int sx = lutY[y];
                const int uvColOffset = (sx >> 1) * uvPixelStride;
                uint8_t* __restrict yOutRow = dstY + y * dstW;
                uint8_t* __restrict vuOutRow = ((y & 1) == 0) ? (dstVU + (y >> 1) * dstW) : nullptr;

                if (!lumaHandledByNeon) {
                    for (int x = 0; x < dstW; ++x) {
                        const int sy = lutX[x];
                        yOutRow[x] = yPlane[sy * yRowStride + sx];
                        if (vuOutRow != nullptr && (x & 1) == 0) {
                            const int uvIdx = (sy >> 1) * uvRowStride + uvColOffset;
                            vuOutRow[x + 0] = vPlane[uvIdx];
                            vuOutRow[x + 1] = uPlane[uvIdx];
                        }
                    }
                } else if (vuOutRow != nullptr) {
                    for (int x = 0; x < dstW; x += 2) {
                        const int sy = lutX[x];
                        const int uvIdx = (sy >> 1) * uvRowStride + uvColOffset;
                        vuOutRow[x + 0] = vPlane[uvIdx];
                        vuOutRow[x + 1] = uPlane[uvIdx];
                    }
                }
            }
        }
    }
}
