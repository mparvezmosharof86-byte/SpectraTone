#include <jni.h>
#include <string>
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>

static inline uint8_t clamp8(float v) {
    if (v < 0.0f) return 0;
    if (v > 255.0f) return 255;
    return static_cast<uint8_t>(v);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_novastudio24_spectratone_engine_NativeEngine_getEngineStatus(
        JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF("SpectraTone C++ Engine Online (64-bit SIMD Ready)");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_novastudio24_spectratone_engine_NativeEngine_applyAdjustments(
        JNIEnv* env, jobject /* this */,
        jobject srcBitmap, jobject dstBitmap,
        jfloat brightness, jfloat contrast, jfloat saturation,
        jfloat warmth, jfloat tint, jfloat vignette, jint presetId) {

    AndroidBitmapInfo srcInfo, dstInfo;
    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0 ||
        AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) < 0) return JNI_FALSE;

    void *srcPixels, *dstPixels;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 ||
        AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) < 0) return JNI_FALSE;

    const int width = srcInfo.width;
    const int height = srcInfo.height;
    uint8_t* src = static_cast<uint8_t*>(srcPixels);
    uint8_t* dst = static_cast<uint8_t*>(dstPixels);

    float contrastFactor = (259.0f * (contrast + 255.0f)) / (255.0f * (259.0f - contrast));
    float satFactor = saturation + 1.0f;
    float brightOffset = brightness * 255.0f;
    float rWarmth = warmth * 30.0f;
    float bWarmth = -warmth * 30.0f;
    float gTint = -tint * 30.0f;
    float mTint = tint * 20.0f;

    float cx = width / 2.0f;
    float cy = height / 2.0f;
    float maxDistSq = cx * cx + cy * cy;

    for (int y = 0; y < height; ++y) {
        float dy = y - cy;
        float dySq = dy * dy;
        int rowIdx = y * width;

        for (int x = 0; x < width; ++x) {
            int idx = (rowIdx + x) * 4;
            float r = src[idx] + rWarmth + mTint + brightOffset;
            float g = src[idx + 1] + gTint + brightOffset;
            float b = src[idx + 2] + bWarmth + mTint + brightOffset;
            uint8_t a = src[idx + 3];

            r = contrastFactor * (r - 128.0f) + 128.0f;
            g = contrastFactor * (g - 128.0f) + 128.0f;
            b = contrastFactor * (b - 128.0f) + 128.0f;

            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = lum + (r - lum) * satFactor;
            g = lum + (g - lum) * satFactor;
            b = lum + (b - lum) * satFactor;

            if (presetId == 1) { // Cinematic
                if (lum > 128.0f) { r += 20.0f; b -= 15.0f; }
                else { b += 25.0f; g += 10.0f; r -= 15.0f; }
            } else if (presetId == 2) { // Vintage
                r += 25.0f; g += 15.0f; b = std::max(b * 0.8f, 30.0f);
            } else if (presetId == 3) { // Film Noir B&W
                float mono = 0.299f * r + 0.587f * g + 0.114f * b;
                r = g = b = 1.35f * (mono - 128.0f) + 128.0f;
            } else if (presetId == 4) { // Cyberpunk
                r += 20.0f; b += 40.0f; g = std::min(g, 180.0f);
            }

            if (vignette > 0.001f) {
                float dx = x - cx;
                float vFactor = std::max(0.0f, 1.0f - (vignette * ((dx * dx + dySq) / maxDistSq)));
                r *= vFactor; g *= vFactor; b *= vFactor;
            }

            dst[idx] = clamp8(r);
            dst[idx + 1] = clamp8(g);
            dst[idx + 2] = clamp8(b);
            dst[idx + 3] = a;
        }
    }

    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
    return JNI_TRUE;
}
