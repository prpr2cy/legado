#include <jni.h>
#include "math_interface.h"
#include <omp.h>

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_legado_app_model_analyzeRule_GlyphNative_calculateDifferencesBatch(
        JNIEnv* env,
        jclass clazz,
        jintArray inputArr,
        jintArray allCandidateArr,
        jintArray offsets,
        jintArray allCandidateMxy) {

    static DiffCalculator calculator = get_optimized_calculator();

    jint* input = env->GetIntArrayElements(inputArr, nullptr);
    jint* candidates = env->GetIntArrayElements(allCandidateArr, nullptr);
    jint* offsetData = env->GetIntArrayElements(offsets, nullptr);
    jint* mxyData = env->GetIntArrayElements(allCandidateMxy, nullptr);

    const jsize numCandidates = env->GetArrayLength(offsets) / 2;
    jlongArray resultArray = env->NewLongArray(numCandidates);
    jlong* results = env->GetLongArrayElements(resultArray, nullptr);

    const int inputLen = env->GetArrayLength(inputArr);

#pragma omp parallel for
    for (int i = 0; i < numCandidates; ++i) {
        const int start = offsetData[i*2];
        const int length = offsetData[i*2+1];

        if (length != inputLen) {
            results[i] = INT64_MAX;
            continue;
        }

        int64_t diff = calculator(
                input,
                candidates + start,
                length / 2 // 计算点数
        );
        results[i] = diff;
    }

    // 释放资源
    env->ReleaseIntArrayElements(inputArr, input, JNI_ABORT);
    env->ReleaseIntArrayElements(allCandidateArr, candidates, JNI_ABORT);
    env->ReleaseIntArrayElements(offsets, offsetData, JNI_ABORT);
    env->ReleaseIntArrayElements(allCandidateMxy, mxyData, JNI_ABORT);
    env->ReleaseLongArrayElements(resultArray, results, 0);

    return resultArray;
}