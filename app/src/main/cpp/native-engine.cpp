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

// Smooth Hermite Spline (Eliminates skin posterization & face blotches)
static inline float smoothstep(float edge0, float edge1, float x) {
    float t = std::max(0.0f, std::min(1.0f, (x - edge0) / (edge1 - edge0)));
    return t * t * (3.0f - 2.0f * t);
}

static inline void rgb2hsl(float r, float g, float b, float& h, float& s, float& l) {
    r /= 255.0f; g /= 255.0f; b /= 255.0f;
    float minV = std::min({r, g, b});
    float maxV = std::max({r, g, b});
    float delta = maxV - minV;
    l = (maxV + minV) / 2.0f;

    if (delta <= 1e-4f) {
        h = 0.0f; s = 0.0f;
    } else {
        s = (l <= 0.5f) ? (delta / (maxV + minV)) : (delta / (2.0f - maxV - minV));
        if (r >= maxV) h = (g - b) / delta + (g < b ? 6.0f : 0.0f);
        else if (g >= maxV) h = (b - r) / delta + 2.0f;
        else h = (r - g) / delta + 4.0f;
        h *= 60.0f;
    }
}

static inline float hue2rgb(float p, float q, float t) {
    if (t < 0.0f) t += 1.0f;
    if (t > 1.0f) t -= 1.0f;
    if (t < 1.0f / 6.0f) return p + (q - p) * 6.0f * t;
    if (t < 1.0f / 2.0f) return q;
    if (t < 2.0f / 3.0f) return p + (q - p) * (2.0f / 3.0f - t) * 6.0f;
    return p;
}

static inline void hsl2rgb(float h, float s, float l, float& r, float& g, float& b) {
    if (s <= 1e-4f) {
        r = g = b = l * 255.0f;
    } else {
        h /= 360.0f;
        float q = l < 0.5f ? l * (1.0f + s) : l + s - l * s;
        float p = 2.0f * l - q;
        r = hue2rgb(p, q, h + 1.0f / 3.0f) * 255.0f;
        g = hue2rgb(p, q, h) * 255.0f;
        b = hue2rgb(p, q, h - 1.0f / 3.0f) * 255.0f;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_novastudio24_spectratone_engine_NativeEngine_getEngineStatus(
        JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF("SpectraTone Master Studio Core Online");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_novastudio24_spectratone_engine_NativeEngine_applyAdjustments(
        JNIEnv* env, jobject /* this */,
        jobject srcBitmap, jobject dstBitmap,
        jfloat brightness, jfloat contrast, jfloat saturation,
        jfloat warmth, jfloat tint, jfloat vignette,
        jfloat sharpness,
        jint presetId,
        jfloatArray hslArray) {

    AndroidBitmapInfo srcInfo, dstInfo;
    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0 ||
        AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) < 0) return JNI_FALSE;

    void *srcPixels, *dstPixels;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 ||
        AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) < 0) return JNI_FALSE;

    jfloat* hsl = env->GetFloatArrayElements(hslArray, nullptr);

    const int width = srcInfo.width;
    const int height = srcInfo.height;
    const int srcStride = srcInfo.stride;
    const int dstStride = dstInfo.stride;

    uint8_t* srcBytes = static_cast<uint8_t*>(srcPixels);
    uint8_t* dstBytes = static_cast<uint8_t*>(dstPixels);

    float contrastFactor = (259.0f * (contrast + 255.0f)) / (255.0f * (259.0f - contrast));
    float satFactor = saturation + 1.0f;
    float brightOffset = brightness * 255.0f;
    float rWarmth = warmth * 22.0f;
    float bWarmth = -warmth * 22.0f;
    float gTint = -tint * 18.0f;
    float mTint = tint * 12.0f;

    float cx = width / 2.0f;
    float cy = height / 2.0f;
    float maxDistSq = cx * cx + cy * cy;

    const float centerHues[8] = { 0.0f, 30.0f, 60.0f, 120.0f, 180.0f, 240.0f, 280.0f, 320.0f };

    for (int y = 0; y < height; ++y) {
        float dy = y - cy;
        float dySq = dy * dy;

        uint8_t* srcRow = srcBytes + (y * srcStride);
        uint8_t* dstRow = dstBytes + (y * dstStride);

        for (int x = 0; x < width; ++x) {
            int px = x * 4;
            float r = srcRow[px];
            float g = srcRow[px + 1];
            float b = srcRow[px + 2];
            uint8_t a = srcRow[px + 3];

            // 1. High-Pass Edge Sharpening (Zero Halo on Skin)
            if (sharpness > 0.01f && y > 0 && y < height - 1 && x > 0 && x < width - 1) {
                uint8_t* topRow = srcBytes + ((y - 1) * srcStride);
                uint8_t* botRow = srcBytes + ((y + 1) * srcStride);

                float hpR = 4.0f * r - topRow[px] - botRow[px] - srcRow[px - 4] - srcRow[px + 4];
                float hpG = 4.0f * g - topRow[px + 1] - botRow[px + 1] - srcRow[px - 3] - srcRow[px + 5];
                float hpB = 4.0f * b - topRow[px + 2] - botRow[px + 2] - srcRow[px - 2] - srcRow[px + 6];

                r += hpR * (sharpness * 0.20f);
                g += hpG * (sharpness * 0.20f);
                b += hpB * (sharpness * 0.20f);
            }

            // 2. White Balance & Brightness
            r += (rWarmth + mTint + brightOffset);
            g += (gTint + brightOffset);
            b += (bWarmth + mTint + brightOffset);

            // 3. Contrast
            r = contrastFactor * (r - 128.0f) + 128.0f;
            g = contrastFactor * (g - 128.0f) + 128.0f;
            b = contrastFactor * (b - 128.0f) + 128.0f;

            // 4. Global Saturation
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = lum + (r - lum) * satFactor;
            g = lum + (g - lum) * satFactor;
            b = lum + (b - lum) * satFactor;

            // 5. Professional Presets (Smooth Curves, ZERO Face Blotches)
            float normLum = lum / 255.0f;

            if (presetId == 1) { // Cinematic Hollywood (Teal & Orange with Skin Protection)
                float hiWeight = smoothstep(0.40f, 0.85f, normLum);
                float shWeight = 1.0f - smoothstep(0.15f, 0.60f, normLum);

                r += 14.0f * hiWeight - 10.0f * shWeight;
                g += 4.0f * hiWeight + 3.0f * shWeight;
                b += -12.0f * hiWeight + 16.0f * shWeight;
            } else if (presetId == 2) { // Vintage Matte Film
                float shLift = (1.0f - normLum) * 18.0f;
                r += 10.0f + shLift * 0.4f;
                g += 6.0f + shLift * 0.2f;
                b = b * 0.90f + shLift * 0.5f;
            } else if (presetId == 3) { // Film Noir Rich B&W (Silver Halide S-Curve)
                float mono = 0.299f * r + 0.587f * g + 0.114f * b;
                float mNorm = mono / 255.0f;
                float curved = (mNorm < 0.5f) ? (2.0f * mNorm * mNorm) : (1.0f - 2.0f * (1.0f - mNorm) * (1.0f - mNorm));
                r = g = b = curved * 255.0f;
            } else if (presetId == 4) { // Cyberpunk Neon
                float shWeight = 1.0f - smoothstep(0.25f, 0.75f, normLum);
                r += 10.0f * (1.0f - shWeight);
                b += 22.0f * shWeight;
            }

            // 6. 8-Color HSL Matrix (Continuous smooth hue window)
            float hVal, sVal, lVal;
            rgb2hsl(r, g, b, hVal, sVal, lVal);

            if (sVal > 0.05f) {
                for (int c = 0; c < 8; ++c) {
                    float targetH = centerHues[c];
                    float diff = std::abs(hVal - targetH);
                    if (diff > 180.0f) diff = 360.0f - diff;

                    if (diff < 32.0f) {
                        float weight = 1.0f - (diff / 32.0f);
                        float dH = hsl[c * 3] * 35.0f;
                        float dS = hsl[c * 3 + 1] * 0.8f;
                        float dL = hsl[c * 3 + 2] * 0.4f;

                        hVal += dH * weight;
                        if (hVal < 0.0f) hVal += 360.0f;
                        if (hVal >= 360.0f) hVal -= 360.0f;

                        sVal = std::max(0.0f, std::min(1.0f, sVal + dS * weight));
                        lVal = std::max(0.0f, std::min(1.0f, lVal + dL * weight));
                    }
                }
                hsl2rgb(hVal, sVal, lVal, r, g, b);
            }

            // 7. Natural Vignette
            if (vignette > 0.001f) {
                float dx = x - cx;
                float vFactor = std::max(0.0f, 1.0f - (vignette * ((dx * dx + dySq) / maxDistSq)));
                r *= vFactor; g *= vFactor; b *= vFactor;
            }

            dstRow[px]     = clamp8(r);
            dstRow[px + 1] = clamp8(g);
            dstRow[px + 2] = clamp8(b);
            dstRow[px + 3] = a;
        }
    }

    env->ReleaseFloatArrayElements(hslArray, hsl, 0);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
    return JNI_TRUE;
}
