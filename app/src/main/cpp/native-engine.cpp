#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "SpectraTone_Engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_novastudio24_spectratone_engine_NativeEngine_getEngineStatus(
        JNIEnv* env,
        jobject /* this */) {
    LOGI("SpectraTone C++ Engine is active.");
    return env->NewStringUTF("SpectraTone C++ Engine Online (64-bit SIMD Ready)");
}
