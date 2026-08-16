#include "rhay_gold_engine.h"
#include <jni.h>
#include <string>
#include <vector>

namespace {

void throwState(JNIEnv* env, const std::string& message) {
    jclass exClass = env->FindClass("java/lang/IllegalStateException");
    if (exClass) env->ThrowNew(exClass, message.c_str());
}

jfloatArray toJavaFloatArray(JNIEnv* env, const std::vector<float>& out) {
    if (out.empty()) return nullptr;
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (!result) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

} // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_mg_appmada_rhaystudio_GoldEngine_nativeProcessRegion(
    JNIEnv* env,
    jclass,
    jfloatArray input,
    jint channels,
    jint sampleRate,
    jdouble pitchScale) {

    if (!input) return nullptr;
    const jsize sampleCount = env->GetArrayLength(input);
    if (channels <= 0 || sampleCount <= 0 || sampleCount % channels != 0) {
        throwState(env, "invalid PCM arguments");
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
        throwState(env, error.empty() ? "native render failed" : error);
        return nullptr;
    }
    return toJavaFloatArray(env, out);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_mg_appmada_rhaystudio_GoldEngine_nativeProcessCurve(
    JNIEnv* env,
    jclass,
    jfloatArray input,
    jint channels,
    jint sampleRate,
    jdoubleArray pitchScales,
    jint controlFrames) {

    if (!input || !pitchScales) return nullptr;
    const jsize sampleCount = env->GetArrayLength(input);
    const jsize scaleCount = env->GetArrayLength(pitchScales);
    if (channels <= 0 || sampleCount <= 0 || sampleCount % channels != 0 ||
        scaleCount <= 0 || controlFrames <= 0) {
        throwState(env, "invalid curve arguments");
        return nullptr;
    }

    jboolean inputCopy = JNI_FALSE;
    jboolean scaleCopy = JNI_FALSE;
    jfloat* inputPtr = env->GetFloatArrayElements(input, &inputCopy);
    jdouble* scalePtr = env->GetDoubleArrayElements(pitchScales, &scaleCopy);
    if (!inputPtr || !scalePtr) {
        if (inputPtr) env->ReleaseFloatArrayElements(input, inputPtr, JNI_ABORT);
        if (scalePtr) env->ReleaseDoubleArrayElements(pitchScales, scalePtr, JNI_ABORT);
        return nullptr;
    }

    std::string error;
    const std::size_t frames = static_cast<std::size_t>(sampleCount / channels);
    std::vector<float> out = rhaygold::processInterleavedCurve(
        inputPtr,
        frames,
        channels,
        sampleRate,
        scalePtr,
        static_cast<std::size_t>(scaleCount),
        static_cast<std::size_t>(controlFrames),
        &error);

    env->ReleaseFloatArrayElements(input, inputPtr, JNI_ABORT);
    env->ReleaseDoubleArrayElements(pitchScales, scalePtr, JNI_ABORT);

    if (out.empty()) {
        throwState(env, error.empty() ? "native curve render failed" : error);
        return nullptr;
    }
    return toJavaFloatArray(env, out);
}
