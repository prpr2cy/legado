package io.legado.app.model.analyzeRule;

import android.annotation.SuppressLint;
import android.os.AsyncTask;
import android.os.Build;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

public class GlyphMatcher {
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    private static final int MAX_CACHE_ENTRIES = 50; // 根据实际需求调整缓存容量
    private static final LruCache<CacheKey, Map<Integer, List<GlyphData>>> groupedDataCache =
            new LruCache<>(MAX_CACHE_ENTRIES);    // 预处理后的数据结构
    private final Map<Integer, List<GlyphData>> groupedData;

    private static final int MAX_RESULT_CACHE_SIZE = 1000; // 结果缓存容量
    private static final LruCache<ResultCacheKey, Result> resultCache =
            new LruCache<>(MAX_RESULT_CACHE_SIZE);

    // 新增结果缓存键类
    private static class ResultCacheKey {
        final CacheKey dataKey;
        final String inputHash;

        ResultCacheKey(CacheKey dataKey, String inputGlyph) {
            this.dataKey = dataKey;
            // 使用高效哈希算法
            this.inputHash = Integer.toHexString(inputGlyph.hashCode())
                    + Integer.toHexString(inputGlyph.length());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ResultCacheKey that = (ResultCacheKey) o;
            return dataKey.equals(that.dataKey) &&
                    inputHash.equals(that.inputHash);
        }

        @Override
        public int hashCode() {
            return 31 * dataKey.hashCode() + inputHash.hashCode();
        }
    }

    // 缓存键定义
    private static class CacheKey {
        private final int glyfHash;
        private final int unicodeHash;

        CacheKey(QueryTTF.GlyfLayout[] glyfArray, int[] unicodeArray) {
            this.glyfHash = Arrays.deepHashCode(glyfArray);
            this.unicodeHash = Arrays.hashCode(unicodeArray);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return glyfHash == cacheKey.glyfHash &&
                    unicodeHash == cacheKey.unicodeHash;
        }

        @Override
        public int hashCode() {
            return 31 * glyfHash + unicodeHash;
        }
    }

    private final QueryTTF.GlyfLayout[] originalGlyfArray;
    private final int[] originalUnicodeArray;

    public GlyphMatcher(QueryTTF.GlyfLayout[] glyfArray, int[] unicodeArray) {
        this.originalGlyfArray = glyfArray; // 新增
        this.originalUnicodeArray = unicodeArray; // 新增
        CacheKey key = new CacheKey(glyfArray, unicodeArray);
        Map<Integer, List<GlyphData>> cached = groupedDataCache.get(key);
        if (cached == null) {
            cached = preprocessData(glyfArray, unicodeArray);
            groupedDataCache.put(key, cached);
        }
        this.groupedData = cached;
    }

    // 清空缓存（LRU 会自动淘汰）
    public static void clearCache() {
        groupedDataCache.evictAll();
        resultCache.evictAll();
    }

    public interface MatchCallback {
        void onResult(int unicode);

        void onError(String message);
    }

    // 异步匹配入口
    @SuppressLint("StaticFieldLeak")
    public void findClosestAsync(String inputGlyph, MatchCallback callback) {
        // 构造缓存键
        final ResultCacheKey cacheKey = new ResultCacheKey(
                new CacheKey(originalGlyfArray, originalUnicodeArray), // 需要保存原始数组引用
                inputGlyph
        );

        // 先检查缓存
        Result cachedResult = resultCache.get(cacheKey);
        if (cachedResult != null) {
            callback.onResult(cachedResult.unicode);
            return;
        }


        new AsyncTask<Void, Void, Result>() {
            protected Result doInBackground(Void... params) {
                try {
                    List<GlyphPoint> input = parseGlyph(inputGlyph);
                    if (input == null) return new Result("Invalid input format");

                    List<GlyphData> candidates = groupedData.get(input.size());
                    if (candidates == null) return new Result("No candidates found");

                    return findClosestOptimized(input, candidates);
                } catch (Exception e) {
                    return new Result(e.getMessage());
                }
            }

            protected void onPostExecute(Result result) {
                // 缓存有效结果（仅成功匹配时缓存）
                if (result.error == null) {
                    resultCache.put(cacheKey, result);
                }

                if (result.error != null) {
                    callback.onError(result.error);
                } else {
                    callback.onResult(result.unicode);
                }
            }
        }.execute();
    }

    // 核心匹配逻辑
    private Result findClosestOptimized(List<GlyphPoint> input, List<GlyphData> candidates) {
        PointData pointData = flattenPoints(input);
        final int[] flatInput = pointData.arr;
        final int[] mxyInput = pointData.mxy;
        final int inputLength = flatInput.length;

        // 预处理：过滤候选并收集数据
        List<GlyphData> filtered = new ArrayList<>();
        for (GlyphData data : candidates) {
            int[] mxy = data.flatPoints.mxy;
            if (Math.abs(flatInput.length - data.flatPoints.arr.length) < 2 &&
                    IntStream.range(0, 4).allMatch(i -> Math.abs(mxyInput[i] - mxy[i]) <= 3)) {
                filtered.add(data);
            }
        }

        // 准备批量数据
        int totalArrSize = filtered.stream().mapToInt(d -> d.flatPoints.arr.length).sum();
        int[] allCandidateArr = new int[totalArrSize];
        int[] offsets = new int[filtered.size() * 2];
        int[] allCandidateMxy = new int[filtered.size() * 4];

        int arrIndex = 0;
        for (int i = 0; i < filtered.size(); i++) {
            GlyphData data = filtered.get(i);
            int[] arr = data.flatPoints.arr;
            System.arraycopy(arr, 0, allCandidateArr, arrIndex, arr.length);
            offsets[i*2] = arrIndex;   // 起始位置
            offsets[i*2+1] = arr.length; // 长度
            arrIndex += arr.length;

            System.arraycopy(data.flatPoints.mxy, 0, allCandidateMxy, i*4, 4);
        }

        // 单次JNI调用
        long[] diffs = GlyphNative.calculateDifferencesBatch(flatInput, allCandidateArr, offsets, allCandidateMxy);

        // 处理结果
        long minDiff = Long.MAX_VALUE;
        int unicode = -1;
        for (int i = 0; i < diffs.length; i++) {
            if (diffs[i] < minDiff) {
                minDiff = diffs[i];
                unicode = filtered.get(i).unicode;
            }
        }

        return unicode != -1 ? new Result(unicode) : new Result("No match");
    }

    private Map<Integer, List<GlyphData>> preprocessData(QueryTTF.GlyfLayout[] glyfArray, int[] unicodeArray) {
        Map<Integer, List<GlyphData>> map = new HashMap<>();
        int length = glyfArray.length;
        for (int index = 0; index < length; index++) {
            List<GlyphPoint> points = parseGlyph(glyfArray[index]);
            if (points == null) continue;

            GlyphData data = new GlyphData(
                    unicodeArray[index],
                    flattenPoints(points)
            );

            map.computeIfAbsent(points.size(), k -> new ArrayList<>())
                    .add(data);
        }
        return map;
    }

    // 数据转换辅助方法
    private static List<GlyphPoint> parseGlyph(QueryTTF.GlyfLayout glyph) {
        List<GlyphPoint> pointList = new ArrayList<>();
        if (null == glyph || null == glyph.glyphSimple) {
            return null;
        }
        // 简单字形
        int dataCount = glyph.glyphSimple.flags.length;
        for (int i = 0; i < dataCount; i++) {
            try {
                int x = glyph.glyphSimple.xCoordinates[i];
                int y = glyph.glyphSimple.yCoordinates[i];
                pointList.add(new GlyphPoint(x, y));
            } catch (NumberFormatException e) {
                return null; // 数据格式错误
            }
        }
        return pointList;
    }

    // 数据转换辅助方法
    private static List<GlyphPoint> parseGlyph(String glyphStr) {
        String[] points = glyphStr.split("\\|");
        List<GlyphPoint> pointList = new ArrayList<>();
        for (String point : points) {
            String[] xy = point.split(",");
            if (xy.length != 2) continue;
            try {
                int x = Integer.parseInt(xy[0].trim());
                int y = Integer.parseInt(xy[1].trim());
                pointList.add(new GlyphPoint(x, y));
            } catch (NumberFormatException e) {
                return null; // 数据格式错误
            }
        }
        return pointList;
    }

    private static PointData flattenPoints(List<GlyphPoint> points) {
        int[] arr = new int[points.size() * 2];
        int[] mxy = new int[4];
        for (int i = 0; i < points.size(); i++) {
            GlyphPoint p = points.get(i);
            arr[2 * i] = p.x;
            arr[2 * i + 1] = p.y;
            if (mxy[0] < p.x) {
                mxy[0] = p.x;
            }
            if (mxy[1] > p.x) {
                mxy[1] = p.x;
            }
            if (mxy[2] < p.y) {
                mxy[2] = p.y;
            }
            if (mxy[3] > p.y) {
                mxy[3] = p.y;
            }
        }
        return new PointData(arr, mxy);
    }

    // 内部数据类
    private static class PointData {
        final int[] mxy;
        final int[] arr;

        PointData(int[] arr, int[] mxy) {
            this.arr = arr;
            this.mxy = mxy;
        }
    }

    // 内部数据类
    private static class GlyphData {
        final int unicode;
        final PointData flatPoints;

        GlyphData(int unicode, PointData flatPoints) {
            this.unicode = unicode;
            this.flatPoints = flatPoints;
        }
    }

    private static class Result {
        final int unicode;
        final String error;

        Result(int unicode) {
            this.unicode = unicode;
            this.error = null;
        }

        Result(String error) {
            this.unicode = -1;
            this.error = error;
        }
    }

    private static class MatchResult {
        final int unicode;
        final long diff;

        MatchResult(int unicode, long diff) {
            this.unicode = unicode;
            this.diff = diff;
        }
    }
}

