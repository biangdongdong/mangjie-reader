package com.mangjie.reader.reader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 朗读切块缓存：把「净化 + 切成 TTS 块」的结果按句序号存起来，
 * 当前句在念的时候顺手把下一句也算好，切句时不用再走一遍正则。
 *
 * 注意：TTS 引擎没有预合成接口，这里只省文本处理，省不了引擎合成时间。
 * 命中时还要核对原文是否一致，避免换书、换句之后序号撞车用错块。
 */
public final class SpeakCache {

    private static final int MAX = 30;

    private static final class Cached {
        final String raw;
        final List<String> chunks;
        Cached(String raw, List<String> chunks) {
            this.raw = raw;
            this.chunks = chunks;
        }
    }

    private static final LinkedHashMap<Integer, Cached> MAP =
            new LinkedHashMap<Integer, Cached>(MAX, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Cached> eldest) {
                    return size() > MAX;
                }
            };

    private SpeakCache() {}

    /** 取某一句已算好的块；原文对不上就当没命中 */
    public static synchronized List<String> get(int idx, String raw) {
        Cached e = MAP.get(idx);
        if (e == null) return null;
        if (raw == null) return e.raw == null ? new ArrayList<String>(e.chunks) : null;
        if (!raw.equals(e.raw)) { MAP.remove(idx); return null; }
        return new ArrayList<String>(e.chunks);
    }

    public static synchronized void put(int idx, String raw, List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        MAP.put(idx, new Cached(raw, new ArrayList<String>(chunks)));
    }

    /** 换书、换引擎、改语速或换净化规则时清空 */
    public static synchronized void clear() {
        MAP.clear();
    }

    public static synchronized int size() {
        return MAP.size();
    }
}
