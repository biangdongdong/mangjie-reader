package com.mangjie.reader.reader;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 电子书解析：EPUB / DOCX / MOBI / TXT / Markdown 统一入口。
 * 从 MainActivity 拆出来的纯解析层，不碰界面和偏好，方便单独验证。
 * 入口：{@link #readBookText(File)}；解析不出来返回 null，不抛异常。
 */
public final class EbookParser {

    private static final String TAG = "阅读";

    private EbookParser() {}

    /** 体积显示：KB / MB，日志和界面共用 */
    public static String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / 1024 / 1024) + " MB";
    }

    /** 数一段文本里有多少乱码替换符，用来判断编码猜错 */
    public static int countReplacement(String t) {
        int n = 0;
        if (t == null) return 0;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\uFFFD') n++;
        return n;
    }

    private static void log(String msg) {
        try { Log.d(TAG, msg); } catch (Throwable ignored) {}
    }

    private static byte[] palmDocDecompress(byte[] src, int start, int end) {
        int cap = Math.min(2 * 1024 * 1024, Math.max(256, (end - start) * 3));
        byte[] buf = new byte[cap];
        int n = 0;
        int i = start;
        final int MAX_OUT = 2 * 1024 * 1024;   // 单条记录输出上限，防坏数据无限膨胀
        while (i < end) {
            int c = src[i++] & 0xFF;
            if (c == 0 || (c >= 0x09 && c <= 0x7F)) {
                if (n >= MAX_OUT) break;
                if (n >= buf.length) buf = growBuf(buf, n + 1, MAX_OUT);
                buf[n++] = (byte) c;
            } else if (c <= 0x08) {
                for (int k = 0; k < c && i < end; k++) {
                    if (n >= MAX_OUT) break;
                    if (n >= buf.length) buf = growBuf(buf, n + 1, MAX_OUT);
                    buf[n++] = src[i++];
                }
            } else if (c <= 0xBF) {
                if (i >= end) break;
                int v = ((c & 0x3F) << 8) | (src[i++] & 0xFF);
                int distance = v >> 3;
                int length = (v & 0x7) + 3;
                if (distance == 0 || distance > n) continue;
                for (int k = 0; k < length; k++) {
                    if (n >= MAX_OUT) break;
                    if (n >= buf.length) buf = growBuf(buf, n + 1, MAX_OUT);
                    buf[n] = buf[n - distance];
                    n++;
                }
            } else {
                if (n + 2 > MAX_OUT) break;
                if (n + 2 > buf.length) buf = growBuf(buf, n + 2, MAX_OUT);
                buf[n++] = (byte) ' ';
                buf[n++] = (byte) (c ^ 0x80);
            }
        }
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    public static String readBookText(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".epub")) return readEpub(f);
        if (n.endsWith(".docx")) return readDocx(f);
        if (n.endsWith(".mobi")) return readMobi(f);
        if (n.endsWith(".md") || n.endsWith(".markdown")) {
            String raw = readText(f);
            return raw == null ? null : cleanMarkdown(raw);
        }
        return readText(f);
    }

    public static String cleanMarkdown(String src) {
        StringBuilder sb = new StringBuilder();
        String[] lines = src.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String t = lines[li];
            t = t.replaceAll("^\\s*>\\s?", "");
            t = t.replaceAll("^\\s*#+\\s*", "");
            t = t.replaceAll("^\\s*-\\s+", "");
            t = t.replaceAll("^\\s*\\d+\\.\\s+", "");
            t = t.replace("**", "").replace("`", "");
            t = t.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
            t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
            if (t.trim().matches("(---|===|\\*\\*\\*)")) t = "";
            sb.append(t).append("\n");
        }
        return sb.toString();
    }

    public static String readDocx(File f) {
        try {
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(f);
            java.util.zip.ZipEntry e = zip.getEntry("word/document.xml");
            if (e == null) { zip.close(); return null; }
            String xml = readEntry(zip, e);
            zip.close();
            if (xml == null) return null;
            String t = xml.replaceAll("<w:tab[^>]*/>", " ")
                    .replaceAll("<w:br[^>]*/>", "\n")
                    .replaceAll("</w:p>", "\n")
                    .replaceAll("<[^>]+>", "");
            return unescapeXml(t);
        } catch (Exception ex) { return null; }
    }

    public static String readEpub(File f) {
        try {
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(f);
            String container = readEntry(zip, zip.getEntry("META-INF/container.xml"));
            if (container == null) { zip.close(); return null; }
            String opfPath = null;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("full-path=\"([^\"]+)\"").matcher(container);
            if (m.find()) opfPath = m.group(1);
            if (opfPath == null) { zip.close(); return null; }
            String opf = readEntry(zip, zip.getEntry(opfPath));
            if (opf == null) { zip.close(); return null; }

            String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            java.util.Map<String, String> hrefById = new java.util.HashMap<String, String>();
            java.util.regex.Matcher im = java.util.regex.Pattern.compile("<item[^>]*>").matcher(opf);
            while (im.find()) {
                String tag = im.group();
                String id = attr(tag, "id");
                String href = attr(tag, "href");
                if (id != null && href != null) hrefById.put(id, href);
            }
            java.util.List<String> order = new ArrayList<String>();
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("<itemref[^>]*>").matcher(opf);
            while (sm.find()) {
                String idref = attr(sm.group(), "idref");
                if (idref != null && hrefById.containsKey(idref)) order.add(hrefById.get(idref));
            }
            if (order.isEmpty()) {
                for (String h : hrefById.values()) {
                    if (h.toLowerCase(Locale.ROOT).matches(".*\\.(x?html|htm)$")) order.add(h);
                }
            }

            StringBuilder out = new StringBuilder();
            int chapter = 0;
            for (String href : order) {
                String path = href.startsWith("/") ? href.substring(1) : base + href;
                java.util.zip.ZipEntry e2 = zip.getEntry(path);
                if (e2 == null) continue;
                String html = readEntry(zip, e2);
                if (html == null) continue;
                chapter++;
                out.append("\n第").append(chapter).append("章\n");
                out.append(htmlToText(html));
            }
            zip.close();
            return out.length() == 0 ? null : out.toString();
        } catch (Exception ex) { return null; }
    }

    public static String readMobi(File f) {
        // 这个 1 万行的单文件里，18MB 的 mobi 曾经把进程直接打死：
        // 原来一次 new byte[f.length()]、再整体解压、再整本切句，堆峰值远超 192MB。
        // 现在改成随机读 + 逐记录解压 + 硬上限，宁可截断也不让进程死。
        java.io.RandomAccessFile raf = null;
        try {
            Runtime rt = Runtime.getRuntime();
            long freeHeap = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
            log("mobi 可用堆=" + fmtSize(freeHeap) + "　文件=" + fmtSize(f.length()));
            if (freeHeap < 40L * 1024 * 1024) {
                log("mobi 堆不够，不读");
                return null;
            }
            raf = new java.io.RandomAccessFile(f, "r");
            long flen = raf.length();
            if (flen < 78) return null;

            int numRecords = readU16At(raf, 76);
            if (numRecords < 2 || numRecords > 200000) return null;
            if (78L + (long) numRecords * 8L > flen) return null;
            long[] offs = new long[numRecords];
            for (int i = 0; i < numRecords; i++) offs[i] = readU32At(raf, 78 + i * 8);
            for (int i = 0; i < numRecords; i++) {
                if (offs[i] < 0 || offs[i] > flen) return null;
                if (i > 0 && offs[i] < offs[i - 1]) return null;
            }

            long r0 = offs[0];
            if (r0 + 32 >= flen) return null;
            raf.seek(r0);
            byte[] head = new byte[32];
            raf.readFully(head);
            int compression = u16(head, 0);
            long textLength = u32(head, 4);
            int textRecords = u16(head, 8);
            int encryption = u16(head, 12);
            if (encryption != 0) { log("mobi 有 DRM，读不了"); return null; }
            if (compression != 1 && compression != 2) {
                log("mobi 压缩方式 " + compression + " 暂不支持");
                return null;
            }
            int encoding = 65001;
            if (head[16] == 'M' && head[17] == 'O' && head[18] == 'B' && head[19] == 'I') {
                encoding = (int) u32(head, 28);
            }

            // 解压后的字节上限 12MB（约 600 万汉字），再往上不读了
            final int MAX_BYTES = 12 * 1024 * 1024;
            final int MAX_CHARS = 1200000;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
            for (int i = 1; i <= textRecords && i < numRecords; i++) {
                long start = offs[i];
                long end = (i + 1 < numRecords) ? offs[i + 1] : flen;
                if (start <= 0 || start >= flen || end <= start) continue;
                if (end > flen) end = flen;
                long len = end - start;
                if (len > 4L * 1024 * 1024) continue;
                byte[] rec = new byte[(int) len];
                raf.seek(start);
                raf.readFully(rec);
                if (compression == 2) {
                    byte[] dec = palmDocDecompress(rec, 0, rec.length);
                    if (dec != null) out.write(dec, 0, dec.length);
                } else {
                    out.write(rec, 0, rec.length);
                }
                if (out.size() >= MAX_BYTES) {
                    log("mobi 已到字节上限，后面截断");
                    break;
                }
            }
            log("mobi 解压完成　字节 " + out.size());
            byte[] raw = out.toByteArray();
            int len = (textLength > 0 && textLength < raw.length) ? (int) textLength : raw.length;
            if (len > MAX_CHARS * 3) len = MAX_CHARS * 3;
            String plain;
            if (encoding == 1252) {
                plain = new String(raw, 0, len, java.nio.charset.Charset.forName("windows-1252"));
            } else {
                plain = new String(raw, 0, len, java.nio.charset.Charset.forName("UTF-8"));
                int bad = countReplacement(plain);
                if (bad > 0) {
                    try {
                        String gb = new String(raw, 0, len, java.nio.charset.Charset.forName("GB18030"));
                        int bad2 = countReplacement(gb);
                        if (bad2 < bad) {
                            log("mobi 编码改按 GB18030：坏字 " + bad + " → " + bad2);
                            plain = gb;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            // 语音引擎遇到 U+FFFD / 控制符会卡住，先清干净再说
            int pua = 0;
            for (int i = 0; i < plain.length(); i++) {
                char c = plain.charAt(i);
                if (c >= 0xE000 && c <= 0xF8FF) pua++;
            }
            if (pua > 0) log("mobi 私用区坏字 " + pua + " 个，已清成空格");
            plain = plain.replaceAll("[\\uE000-\\uF8FF]", " ").replace('\uFFFD', ' ')
                    .replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
            log("mobi 坏字清理后仍有 " + countReplacement(plain) + " 个");
            if (plain.length() > MAX_CHARS) plain = plain.substring(0, MAX_CHARS);
            log("mobi 解码完成　字 " + plain.length());
            if (plain.indexOf("<") >= 0) {
                log("mobi 去标签…");
                plain = htmlToText(plain);
                log("mobi 去标签完成　字 " + plain.length());
            }
            return plain;
        } catch (Throwable ex) {
            log("mobi 解析异常：" + ex.getClass().getSimpleName() + "　" + ex.getMessage());
            return null;
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
    }

    private static int readU16At(java.io.RandomAccessFile raf, long pos) throws java.io.IOException {
        raf.seek(pos);
        byte[] b = new byte[2];
        raf.readFully(b);
        return u16(b, 0);
    }

    private static long readU32At(java.io.RandomAccessFile raf, long pos) throws java.io.IOException {
        raf.seek(pos);
        byte[] b = new byte[4];
        raf.readFully(b);
        return u32(b, 0);
    }

    private static byte[] growBuf(byte[] buf, int need, int max) {
        int cap = Math.max(need, Math.min(max, Math.max(256, buf.length * 2)));
        byte[] nb = new byte[cap];
        System.arraycopy(buf, 0, nb, 0, Math.min(buf.length, cap));
        return nb;
    }

    private static int u16(byte[] d, int off) {
        if (off + 1 >= d.length) return 0;
        return ((d[off] & 0xFF) << 8) | (d[off + 1] & 0xFF);
    }

    private static long u32(byte[] d, int off) {
        if (off + 3 >= d.length) return 0;
        return ((long) (d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    public static String readEntry(java.util.zip.ZipFile zip, java.util.zip.ZipEntry e) {
        if (e == null) return null;
        java.io.InputStream in = null;
        try {
            in = zip.getInputStream(e);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            return new String(bos.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
        } catch (Exception ex) { return null; }
        finally { try { if (in != null) in.close(); } catch (Exception ignored) {} }
    }

    public static String attr(String tag, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    public static String htmlToText(String html) {
        String body = html;
        body = body.replaceAll("(?is)<head.*?</head>", "");
        body = body.replaceAll("(?is)<script.*?</script>", "");
        body = body.replaceAll("(?is)<style.*?</style>", "");
        body = body.replaceAll("(?is)<br[^>]*>", "\n");
        body = body.replaceAll("(?is)</p>|</div>|</h[1-6]>", "\n");
        body = body.replaceAll("(?is)<[^>]+>", "");
        return unescapeXml(body);
    }

    public static String unescapeXml(String t) {
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(x?[0-9A-Fa-f]+);").matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String code = m.group(1);
            int v = (code.startsWith("x") || code.startsWith("X"))
                    ? Integer.parseInt(code.substring(1), 16) : Integer.parseInt(code);
            m.appendReplacement(sb, String.valueOf((char) v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String readText(File f) {
        return com.mangjie.reader.reader.TextFile.read(f, 12 * 1024 * 1024);
    }
}
