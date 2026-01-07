#if defined(__SSE3__)
#include <pmmintrin.h>
#include "../math_interface.h"

#ifdef __cplusplus
extern "C" {
#endif

int64_t calculate_diff_sse(const int32_t* a, const int32_t* b, int count) {
    __m128i total = _mm_setzero_si128();
    
    for (int i = 0; i < count; i += 4) {
        __m128i va = _mm_loadu_si128((const __m128i*)(a + i*2));
        __m128i vb = _mm_loadu_si128((const __m128i*)(b + i*2));
        
        __m128i dxdy = _mm_sub_epi32(va, vb);
        __m128i dx = _mm_shuffle_epi32(dxdy, _MM_SHUFFLE(2,0,2,0));
        __m128i dy = _mm_shuffle_epi32(dxdy, _MM_SHUFFLE(3,1,3,1));
        
        __m128i dx_sq = _mm_mul_epi32(dx, dx);
        __m128i dy_sq = _mm_mul_epi32(dy, dy);
        total = _mm_add_epi64(total, _mm_add_epi64(dx_sq, dy_sq));
    }
    
    int64_t res[2];
    _mm_store_si128((__m128i*)res, total);
    return res[0] + res[1];
}

#ifdef __cplusplus
}
#endif

#endif