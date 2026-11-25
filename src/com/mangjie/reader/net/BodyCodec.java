package com.mangjie.reader.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * 响应体的压缩/编码还原。
 *
 * 要解决的是两类「规则没错、数据也在，就是解不开」：
 *
 * 一、字节层面被压过，但没按 Content-Encoding 走透明解压。
 *    书源里不少站点的响应其实是 gzip 字节，HttpURLConnection 只有在请求里明确带
 *    Accept-Encoding 时才会替你解开；书源自带的请求头常常没带，于是拿到的是一串乱码，
 *    往下无论按 HTML 还是按 JSON 解，都只能得到空。
 *
 * 二、整串 base64 包着压缩数据。
 *    日志里出现过 `N4Igxg9g…` 这种响应，那是 brotli 压缩后再 base64 的典型开头；
 *    gzip 的同类开头是 `H4sI`。前者系统没有 brotli 解码器，只能认出来别硬解；
 *    后者可以就地解掉。
 *
 * 认不出来就原样返回，绝不猜——猜错的代价是把正常页面弄坏。
 */
public final class BodyCodec {

    private BodyCodec() {}

    /** gzip 魔数 1f 8b；deflate 没有魔数，只能试着解一次 */
    public static boolean isGzip(byte[] d) {
        return d != null && d.length > 3 && (d[0] & 0xff) == 0x1f && (d[1] & 0xff) == 0x8b;
    }

    /** 字节层还原：gzip 一定解；zlib/deflate 试解一次，解不出原样返回 */
    public static byte[] unwrapBytes(byte[] data) {
        if (data == null || data.length == 0) return data;
        if (isGzip(data)) {
            byte[] g = gunzip(data);
            if (g != null && g.length > 0) return g;
        }
        // 没有魔数的那类：先按 zlib 解一次，失败就按裸 deflate 再解一次。
        // 只在解出来的东西像文本（开头是 < 、{ 、[ 或可打印）时才采用，免得把二进制弄成半截
        byte[] z = inflate(data, false);
        if (z != null && looksLikeText(z)) return z;
        byte[] r = inflate(data, true);
        if (r != null && looksLikeText(r)) return r;
        return data;
    }

    /**
     * 文本层还原：整串是 base64、解出来是 gzip 的，解压并返回正文；
     * 是 brotli 则返回 null（调用方保留原文，只在日志里写一句）。
     */
    public static String unwrapText(String body) {
        if (body == null) return null;
        String t = body.trim();
        if (t.length() < 40) return null;
        if (!looksBase64(t)) return null;
        byte[] raw;
        try { raw = android.util.Base64.decode(t, android.util.Base64.DEFAULT); }
        catch (Throwable e) { return null; }
        if (raw == null || raw.length == 0) return null;
        if (isGzip(raw)) {
            byte[] g = gunzip(raw);
            if (g != null && g.length > 0) return new String(g, java.nio.charset.Charset.forName("UTF-8"));
            return null;
        }
        // 不是 gzip 但解出来就是文本，也可能是「base64 转了一层」的页面
        if (looksLikeText(raw)) {
            String s = new String(raw, java.nio.charset.Charset.forName("UTF-8"));
            String s2 = s.trim();
            if (s2.startsWith("<") || s2.startsWith("{") || s2.startsWith("[")) return s;
        }
        return null;
    }

    /** brotli 的 base64 常见开头（N4Ig / N4Igxg9g…）：系统没有解码器，只能认出来写日志 */
    public static boolean looksBrotliBase64(String body) {
        if (body == null) return false;
        String t = body.trim();
        return t.startsWith("N4Ig") && looksBase64(t);
    }

    private static boolean looksBase64(String t) {
        int n = t.length();
        if (n < 40) return false;
        int pad = 0;
        for (int i = 0; i < n; i++) {
            char ch = t.charAt(i);
            if (ch == '=') { pad++; if (pad > 2) return false; continue; }
            if (pad > 0) return false;                 // = 只能出现在末尾
            if (ch == '\n' || ch == '\r') continue;
            boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '+' || ch == '/';
            if (!ok) return false;
        }
        int real = n - pad;
        return real % 4 == 0 || real % 4 == 2 || real % 4 == 3;
    }

    private static boolean looksLikeText(byte[] d) {
        if (d == null || d.length == 0) return false;
        int check = Math.min(d.length, 64);
        for (int i = 0; i < check; i++) {
            int c = d[i] & 0xff;
            if (c == 0) return false;
            if (c < 0x09) return false;               // 控制字符
            if (i == 0) {
                char ch = (char) c;
                if (!(ch == '<' || ch == '{' || ch == '[' || Character.isWhitespace(ch)
                        || Character.isLetterOrDigit(ch))) return false;
            }
        }
        return true;
    }

    private static byte[] gunzip(byte[] data) {
        try {
            GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] inflate(byte[] data, boolean nowrap) {
        try {
            Inflater inf = new Inflater(nowrap);
            inf.setInput(data);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                } else {
                    bos.write(buf, 0, n);
                }
            }
            inf.end();
            return bos.size() == 0 ? null : bos.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }
}
