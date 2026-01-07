#pragma once
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

typedef int64_t (*DiffCalculator)(const int32_t* a, const int32_t* b, int count);

// 添加前置声明
#if defined(__ARM_NEON__) || defined(__ARM_NEON)
int64_t calculate_diff_neon(const int32_t* a, const int32_t* b, int count);
#endif

#if defined(__SSE3__)
int64_t calculate_diff_sse(const int32_t* a, const int32_t* b, int count);
#endif

#if defined(__AVX2__)
int64_t calculate_diff_avx2(const int32_t* a, const int32_t* b, int count);
#endif

int64_t calculate_diff_fallback(const int32_t* a, const int32_t* b, int count);
DiffCalculator get_optimized_calculator();

#ifdef __cplusplus
}
#endif