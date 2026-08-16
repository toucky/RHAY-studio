#include "rhay_gold_engine.h"
#include <jni.h>
#include <string>
#include <vector>

extern "C" JNIEXPORT jfloatArray JNICALL
Java_mg_appmada_rhaystudio_GoldEngine_processRegion(
    JNIEnv* env,
    jclass,
    jfloatArray input,
    jint channels,
    jint sampleRate,
    jdouble pitchScale) {

    if (!input) return nullptr;
    const jsize sampleCount = env->GetArrayLength(input);
    if (channels <= 0 || sampleCount <= 0 || sampleCount % channels != 0) {
        return nullptr;
    }

    jboolean isCopy = JNI_FALSE;
    jfloat* ptr = env->GetFloatArrayElements(input, &isCopy);
    if (!ptr) return nullptr;

    std::string error;
    const std::size_t frames = static_cast<std::size_t>(sampleCount / channels);
    std::vector<float> out = rhaygold::processInterleaved(
        ptr, frames, channels, sampleRate, pitchScale, &error);

    env->ReleaseFloatArrayElements(input, ptr, JNI_ABORT);

    if (out.empty()) {
        jclass exClass = env->FindClass("java/lang/IllegalStateException");
        if (exClass) env->ThrowNew(exClass, error.c_str());
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (!result) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}
