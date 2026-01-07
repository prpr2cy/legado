#if defined(__ARM_NEON) && defined(__aarch64__)
#include <arm_neon.h>
#include "../math_interface.h"

#ifdef __cplusplus
extern "C" {
#endif

int64_t calculate_diff_neon(const int32_t* a, const int32_t* b, int count) {
    int64x2_t total = vdupq_n_s64(0);
    
    for (int i = 0; i < count; i += 4) {
        int32x4x2_t va = vld2q_s32(a + i*2);
        int32x4x2_t vb = vld2q_s32(b + i*2);
        
        int32x4_t dx = vsubq_s32(va.val[0], vb.val[0]);
        int32x4_t dy = vsubq_s32(va.val[1], vb.val[1]);
        
        total = vmlal_s32(total, vget_low_s32(dx), vget_low_s32(dx));
        total = vmlal_s32(total, vget_low_s32(dy), vget_low_s32(dy));
    }
    
    return vaddvq_s64(total);
}

#ifdef __cplusplus
}
#endif

#endif