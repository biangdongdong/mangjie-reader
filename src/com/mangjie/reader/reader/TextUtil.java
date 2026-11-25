package com.mangjie.reader.reader;

import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 纯文本/系统小工具：TTS 分块、首行摘要、体积显示、设备名。不依赖界面状态。 */
public final class TextUtil {

    private TextUtil() {}

    // ---------------- 隐藏字符清理（对齐 Legado 的 HtmlFormatter 那组规则）
    /**
     * 网页正文里常夹一批看不见的字符：零宽空格、窄空格、软连字符、BOM、HTML 注释。
     * 读屏摸到它们会莫名停顿，分句也会被从中间切断，所以在分句和朗读之前统一清一遍。
     */
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
    private static final Pattern ENT_NBSP = Pattern.compile("(?i)(?:&nbsp;|&#160;|&#xa0;)+");
    private static final Pattern ENT_SPACE = Pattern.compile("(?i)&(?:ensp|emsp|thinsp|hairsp|punctuationspace);");
    private static final Pattern ENT_ZERO = Pattern.compile("(?i)&(?:zwnj|zwj|lrm|rlm|shy);|&#8203;|&#8204;|&#8205;");
    /** 零宽类：直接删 */
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B\\u200C\\u200D\\u200E\\u200F\\u2060\\uFEFF\\u00AD]");
    /** 各种窄空格：换成普通空格，不能删，删了会把两个词粘一块；全角空格 \u3000 是中文段首缩进，不能动，所以范围只到 \u200A */
    private static final Pattern NARROW_SPACE = Pattern.compile("[\\u2000-\\u200A\\u202F\\u205F]");
    private static final Pattern BLANK_LINE_RUN = Pattern.compile("\\n[ \\t]*\\n[ \\t]*\\n+");

    /** 清掉正文里的隐藏字符与残留实体；null 返回空串 */
    public static String stripInvisible(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        String out = HTML_COMMENT.matcher(s).replaceAll("");
        out = ENT_NBSP.matcher(out).replaceAll(" ");
        out = ENT_SPACE.matcher(out).replaceAll(" ");
        out = ENT_ZERO.matcher(out).replaceAll("");
        out = ZERO_WIDTH.matcher(out).replaceAll("");
        out = NARROW_SPACE.matcher(out).replaceAll(" ");
        out = BLANK_LINE_RUN.matcher(out).replaceAll("\n\n");
        return out;
    }

    public static String firstLineOf(File f) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line = r.readLine();
            r.close();
            if (line == null) return "";
            return line.length() > 12 ? line.substring(0, 12) + "…" : line;
        } catch (Exception e) { return ""; }
    }

    public static String urlEnc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    public static String deviceModel() {
        String brand = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT))) return model;
        return brand.isEmpty() ? model : brand + " " + model;
    }

    public static String mbText(long bytes) {
        return Math.round(bytes / 1024.0 / 1024.0) + " MB";
    }

    public static String gb(long bytes) {
        double g = bytes / 1024.0 / 1024.0 / 1024.0;
        return String.format(Locale.CHINA, "%.1f GB", g);
    }

    public static String shortText(String t) {
        if (t == null) return "";
        t = t.replace("\n", " ").trim();
        return t.length() <= 24 ? t : (t.substring(0, 24) + "…");
    }

    public static int countReplacement(String t) {
        int n = 0;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\uFFFD') n++;
        return n;
    }

    public static List<String> chunkForTts(String text, int limit) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        if (text.length() <= limit) { out.add(text); return out; }
        String stops = "。！？…；，,;、：:）)」』”\"'";
        int from = 0;
        while (from < text.length()) {
            int end = Math.min(text.length(), from + limit);
            if (end < text.length()) {
                int cut = -1;
                for (int i = end; i > from + limit / 3; i--) {
                    if (stops.indexOf(text.charAt(i - 1)) >= 0) { cut = i; break; }
                }
                if (cut > from) end = cut;
            }
            String t = text.substring(from, end).trim();
            if (!t.isEmpty()) out.add(t);
            from = end;
        }
        return out;
    }

    public static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    public static int parseIntSafe(String s) {
        return com.mangjie.reader.net.Version.parseIntSafe(s);
    }

    public static String deviceShort() {
        String brand = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (brand != null && model.startsWith(brand)) brand = "";
        return (brand + " " + model).trim() + " / Android " + Build.VERSION.RELEASE;
    }

    public static int clampInt(String s, int lo, int hi) {
        try {
            int v = Integer.parseInt(s.trim());
            return Math.max(lo, Math.min(hi, v));
        } catch (Throwable e) {
            return lo;
        }
    }
}
