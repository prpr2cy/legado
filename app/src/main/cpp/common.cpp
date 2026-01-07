#include "math_interface.h"

int64_t calculate_diff_fallback(const int32_t* a, const int32_t* b, int count) {
    int64_t total = 0;
    for (int i = 0; i < count; ++i) {
        int dx = a[2*i] - b[2*i];
        int dy = a[2*i+1] - b[2*i+1];
        total += (int64_t)dx*dx + (int64_t)dy*dy;
    }
    return total;
}

DiffCalculator get_optimized_calculator() {
    #if defined(__AVX2__)
        return calculate_diff_avx2;
    #elif defined(__SSE3__)
        return calculate_diff_sse;
    #elif defined(__ARM_NEON) && defined(__aarch64__)
        return calculate_diff_neon;
    #elif defined(__ARM_NEON__)
        return calculate_diff_neon;
    #else
        return calculate_diff_fallback;
    #endif
}