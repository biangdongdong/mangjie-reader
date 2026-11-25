package com.mangjie.reader.net;

import java.io.File;
import java.io.FileInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 混淆字体解密（字体映射反查）。
 *
 * 有些书源把正文字形打乱：网页里给的是乱码/生僻字，真正的字靠页面上那份 ttf 字体映射回来。
 * QueryTTF 解析 ttf 的 cmap 表，能查「这个字显示成什么轮廓」和「这个轮廓本来是哪个字」。
 * 书源规则里写 {@code java.replaceFont(result, java.queryTTF(错字体), java.queryTTF(对字体))} 就能还原正文。
 *
 * 字体按来源（url / base64 / 本地路径）缓存，最多留 8 份，LRU 淘汰。
 */
public final class TtfFonts {

    /** 宿主提供两件事：按 url 抓字节、写日志 */
    public interface Host {
        byte[] getBytes(String url);
        void log(String tag, String msg);
    }

    private static final int MAX_CACHE = 8;

    private static final LinkedHashMap<String, QueryTTF> CACHE =
            new LinkedHashMap<String, QueryTTF>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, QueryTTF> e) {
                    return size() > MAX_CACHE;
                }
            };

    private TtfFonts() {}

    /** 从 url / base64 / 本地路径拿一份字体解析器；同一来源只解一次 */
    public static QueryTTF from(Host host, String data) {
        if (data == null) return null;
        String d = data.trim();
        if (d.isEmpty()) return null;
        String key = d.length() + "#" + d.hashCode();
        synchronized (CACHE) {
            QueryTTF hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        try {
            byte[] font;
            if (d.startsWith("http://") || d.startsWith("https://") || d.startsWith("//")) {
                String u = d.startsWith("//") ? "https:" + d : d;
                font = host.getBytes(u);
            } else if (d.startsWith("file://")) {
                font = readFile(new File(d.substring(7)));
            } else if (d.startsWith("/")) {
                font = readFile(new File(d));
            } else {
                font = android.util.Base64.decode(d, android.util.Base64.DEFAULT);
            }
            if (font == null || font.length == 0) return null;
            QueryTTF q = new QueryTTF(font);
            synchronized (CACHE) { CACHE.put(key, q); }
            return q;
        } catch (Throwable t) {
            host.log("听书", "字体解析失败：" + t.getClass().getSimpleName() + " " + shortText(d));
            return null;
        }
    }

    /**
     * 把正文按「错字体 → 对字体」的轮廓对应关系还原。
     *
     * @param filter true 时把错字体里根本不存在的字符删掉（有些乱码是纯填充符号）
     */
    public static String replaceFont(String text, QueryTTF error, QueryTTF correct, boolean filter) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        if (error == null || correct == null) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int step = Character.charCount(cp);
            if (error.isBlankUnicode(cp)) {
                sb.appendCodePoint(cp);
                i += step;
                continue;
            }
            String glyph = error.getGlyfByUnicode(cp);
            if (error.getGlyfIdByUnicode(cp) == 0) glyph = null;   // 指向保留索引 0，等于没有轮廓
            if (filter && glyph == null) { i += step; continue; }
            int code = glyph == null ? 0 : correct.getUnicodeByGlyf(glyph);
            if (code != 0) sb.appendCodePoint(code);
            else sb.appendCodePoint(cp);
            i += step;
        }
        return sb.toString();
    }

    private static byte[] readFile(File f) throws Exception {
        if (!f.isFile() || f.length() <= 0) return null;
        byte[] out = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int read = 0;
            while (read < out.length) {
                int n = in.read(out, read, out.length - read);
                if (n <= 0) break;
                read += n;
            }
            return out;
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static String shortText(String s) {
        return s == null ? "" : (s.length() <= 40 ? s : s.substring(0, 40) + "…");
    }
}
