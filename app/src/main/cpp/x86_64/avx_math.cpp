#if defined(__AVX2__)
#include <immintrin.h>
#include "../math_interface.h"

#ifdef __cplusplus
extern "C" {
#endif

int64_t calculate_diff_avx2(const int32_t* a, const int32_t* b, int count) {
    __m256i total = _mm256_setzero_si256();
    
    for (int i = 0; i < count; i += 8) {
        __m256i va = _mm256_loadu_si256((const __m256i*)(a + i*2));
        __m256i vb = _mm256_loadu_si256((const __m256i*)(b + i*2));
        
        __m256i dxdy = _mm256_sub_epi32(va, vb);
        __m256i dx = _mm256_shuffle_epi32(dxdy, _MM_SHUFFLE(2,0,2,0));
        __m256i dy = _mm256_shuffle_epi32(dxdy, _MM_SHUFFLE(3,1,3,1));
        
        __m256i dx_sq = _mm256_mul_epi32(dx, dx);
        __m256i dy_sq = _mm256_mul_epi32(dy, dy);
        total = _mm256_add_epi64(total, _mm256_add_epi64(dx_sq, dy_sq));
    }
    
    int64_t res[4];
    _mm256_store_si256((__m256i*)res, total);
    return res[0] + res[1] + res[2] + res[3];
}

#ifdef __cplusplus
}
#endif

#endif