package com.mangjie.reader.reader;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 繁体转简体：映射表来自 OpenCC 的 TSCharacters（Apache-2.0），生成时只保留「繁→简」单字对，
 * 打包成 res/raw/t2s.txt，每行两个字：前为繁体，后为简体。
 */
public final class Simplifier {

    private static final String TAG = "Simplifier";
    private static final char[] MAP = new char[0x10000];   // 0 表示没有映射
    private static boolean loaded = false;

    private Simplifier() {}

    public static synchronized void init(Context ctx) {
        if (loaded) return;
        loaded = true;
        try {
            InputStream in = ctx.getResources().openRawResource(
                    ctx.getResources().getIdentifier("t2s", "raw", ctx.getPackageName()));
            int n = loadFromReader(new BufferedReader(new InputStreamReader(in, "UTF-8")));
            Log.i(TAG, "已载入繁简映射 " + n + " 条");
        } catch (Throwable e) {
            Log.w(TAG, "载入繁简映射失败 " + e);
        }
    }

    /** 从「繁简」两字一行的文本载入映射，返回条数 */
    public static int loadFromReader(BufferedReader r) throws java.io.IOException {
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            if (line.length() < 2) continue;
            char t = line.charAt(0);
            char s = line.charAt(1);
            if (t != s) { MAP[t] = s; n++; }
        }
        r.close();
        return n;
    }

    /** 逐字替换；没有映射的字原样保留 */
    public static String toSimplified(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char s = c < 0x10000 ? MAP[c] : 0;
            if (s == 0) {
                if (sb != null) sb.append(c);
            } else {
                if (sb == null) sb = new StringBuilder(text.length()).append(text, 0, i);
                sb.append(s);
            }
        }
        return sb == null ? text : sb.toString();
    }

    /** 有没有被判为需要转换（≥3 个繁体字才动手，避免误伤个别字） */
    public static boolean looksTraditional(String text) {
        if (text == null || text.isEmpty()) return false;
        int hit = 0;
        int sample = Math.min(text.length(), 2000);
        for (int i = 0; i < sample; i++) {
            char c = text.charAt(i);
            if (c < 0x10000 && MAP[c] != 0) {
                hit++;
                if (hit >= 3) return true;
            }
        }
        return false;
    }
}
