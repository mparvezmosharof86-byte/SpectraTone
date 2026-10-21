#include <jni.h>
#include <string>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>

#define LOG_TAG "SpectraTone_Engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static inline uint8_t clampTo8Bit(int value) {
    if (value < 0) return 0;
    if (value > 255) return 255;
    return static_cast<uint8_t>(value);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_novastudio24_spectratone_engine_NativeEngine_getEngineStatus(
        JNIEnv* env,
        jobject /* this */) {
    return env->NewStringUTF("SpectraTone C++ Engine Online (64-bit SIMD Ready)");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_novastudio24_spectratone_engine_NativeEngine_applyAdjustments(
        JNIEnv* env,
        jobject /* this */,
        jobject srcBitmap,
        jobject dstBitmap,
        jfloat brightness,
        jfloat contrast,
        jfloat saturation) {

    AndroidBitmapInfo srcInfo, dstInfo;
    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0 ||
        AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) < 0) {
        return JNI_FALSE;
    }

    if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }

    void* srcPixels;
    void* dstPixels;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 ||
        AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) < 0) {
        return JNI_FALSE;
    }

    const int width = srcInfo.width;
    const int height = srcInfo.height;
    const int totalPixels = width * height;

    uint8_t* src = static_cast<uint8_t*>(srcPixels);
    uint8_t* dst = static_cast<uint8_t*>(dstPixels);

    // Pre-calculate contrast factor
    // Contrast formula: factor = (259 * (contrast + 255)) / (255 * (259 - contrast))
    float cVal = contrast;
    float contrastFactor = (259.0f * (cVal + 255.0f)) / (255.0f * (259.0f - cVal));
    float satFactor = saturation + 1.0f;
    float brightOffset = brightness * 255.0f;

    #pragma omp parallel for
    for (int i = 0; i < totalPixels; ++i) {
        int idx = i * 4;
        int r = src[idx];
        int g = src[idx + 1];
        int b = src[idx + 2];
        uint8_t a = src[idx + 3];

        // 1. Brightness
        float rf = r + brightOffset;
        float gf = g + brightOffset;
        float bf = b + brightOffset;

        // 2. Contrast
        rf = contrastFactor * (rf - 128.0f) + 128.0f;
        gf = contrastFactor * (gf - 128.0f) + 128.0f;
        bf = contrastFactor * (bf - 128.0f) + 128.0f;

        // 3. Saturation (Rec.709 High Definition Luminance)
        float lum = 0.2126f * rf + 0.7152f * gf + 0.0722f * bf;
        rf = lum + (rf - lum) * satFactor;
        gf = lum + (gf - lum) * satFactor;
        bf = lum + (bf - lum) * satFactor;

        dst[idx]     = clampTo8Bit(static_cast<int>(rf));
        dst[idx + 1] = clampTo8Bit(static_cast<int>(gf));
        dst[idx + 2] = clampTo8Bit(static_cast<int>(bf));
        dst[idx + 3] = a; // Keep original Alpha
    }

    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);

    return JNI_TRUE;
}
