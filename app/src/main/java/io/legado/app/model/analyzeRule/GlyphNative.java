package io.legado.app.model.analyzeRule;

public class GlyphNative {
    static {
        System.loadLibrary("glyphdiff");
    }

    // 输入：两组坐标的扁平化int数组
    public static native long[] calculateDifferencesBatch(
            int[] inputArr,
            int[] allCandidateArr,
            int[] offsets,
            int[] allCandidateMxy
    );
}