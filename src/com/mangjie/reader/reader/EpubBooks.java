package com.mangjie.reader.reader;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * epub 的章表与按章读取：只记 spine 顺序下的条目路径和章标题，正文等到用的时候才解压那一条。
 *
 * 以前 readEpub 一次性把所有章节拼成一个大字符串，一本 5MB 文字的合集打开就撞字数上限，
 * 而且章节结构白白丢掉、目录还得靠正则回头再猜。这里改成按章懒读。
 */
public final class EpubBooks {

    /** 章表：titles 与 entries 一一对应，entries 是 zip 内路径 */
    public static final class Index {
        public final List<String> titles;
        public final List<String> entries;
        public final String note;
        public final long ms;

        Index(List<String> t, List<String> e, String note, long ms) {
            this.titles = t;
            this.entries = e;
            this.note = note;
            this.ms = ms;
        }

        public boolean ok() { return note == null && !entries.isEmpty(); }

        public int count() { return entries.size(); }
    }

    /** 找章标题时每章最多读这么多字节，够看出 <h1>/<title> */
    private static final int HEAD_BYTES = 4096;
    /** 章标题最多这么多字，超了就不用它，改叫「第 N 章」 */
    private static final int TITLE_MAX = 40;

    private static final Pattern H_TAG =
            Pattern.compile("(?is)<h[1-4][^>]*>(.*?)</h[1-4]>");
    private static final Pattern T_TAG =
            Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private EpubBooks() {}

    /** 建章表：只读 container.xml 与 opf，再每章读开头几 KB 抓标题 */
    public static Index index(File f) {
        long t0 = System.currentTimeMillis();
        if (f == null || !f.isFile()) {
            return new Index(new ArrayList<String>(), new ArrayList<String>(), "文件不存在", 0);
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(f);
            String container = EbookParser.readEntry(zip, zip.getEntry("META-INF/container.xml"));
            if (container == null) return new Index(new ArrayList<String>(), new ArrayList<String>(),
                    "缺 META-INF/container.xml", System.currentTimeMillis() - t0);
            String opfPath = null;
            Matcher m = Pattern.compile("full-path=\"([^\"]+)\"").matcher(container);
            if (m.find()) opfPath = m.group(1);
            if (opfPath == null) return new Index(new ArrayList<String>(), new ArrayList<String>(),
                    "opf 路径没写", System.currentTimeMillis() - t0);
            String opf = EbookParser.readEntry(zip, zip.getEntry(opfPath));
            if (opf == null) return new Index(new ArrayList<String>(), new ArrayList<String>(),
                    "opf 读不出来", System.currentTimeMillis() - t0);

            String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            java.util.Map<String, String> hrefById = new java.util.HashMap<String, String>();
            Matcher im = Pattern.compile("<item[^>]*>").matcher(opf);
            while (im.find()) {
                String tag = im.group();
                String id = EbookParser.attr(tag, "id");
                String href = EbookParser.attr(tag, "href");
                if (id != null && href != null) hrefById.put(id, href);
            }
            List<String> order = new ArrayList<String>();
            Matcher sm = Pattern.compile("<itemref[^>]*>").matcher(opf);
            while (sm.find()) {
                String idref = EbookParser.attr(sm.group(), "idref");
                if (idref != null && hrefById.containsKey(idref)) order.add(hrefById.get(idref));
            }
            if (order.isEmpty()) {
                for (String h : hrefById.values()) {
                    if (h.toLowerCase(Locale.ROOT).matches(".*\\.(x?html|htm)$")) order.add(h);
                }
            }

            List<String> titles = new ArrayList<String>();
            List<String> entries = new ArrayList<String>();
            for (String href : order) {
                String path = href.startsWith("/") ? href.substring(1) : base + href;
                ZipEntry e = zip.getEntry(path);
                if (e == null) continue;
                String html = head(zip, e, HEAD_BYTES);
                entries.add(path);
                String t = titleOf(html);
                titles.add(t == null || t.isEmpty() ? ("第 " + entries.size() + " 章") : t);
            }
            if (entries.isEmpty()) return new Index(titles, entries, "spine 里没有正文条目",
                    System.currentTimeMillis() - t0);
            return new Index(titles, entries, null, System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            return new Index(new ArrayList<String>(), new ArrayList<String>(),
                    t.getClass().getSimpleName() + " " + t.getMessage(), System.currentTimeMillis() - t0);
        } finally {
            if (zip != null) { try { zip.close(); } catch (Exception ignored) {} }
        }
    }

    /** 读第 n 章正文（纯文字），章号越界或条目坏了返回 null */
    public static String readChapter(File f, Index idx, int n) {
        if (f == null || idx == null || n < 0 || n >= idx.entries.size()) return null;
        ZipFile zip = null;
        try {
            zip = new ZipFile(f);
            String html = EbookParser.readEntry(zip, zip.getEntry(idx.entries.get(n)));
            if (html == null) return null;
            String text = EbookParser.htmlToText(html);
            return text == null ? null : text.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (zip != null) { try { zip.close(); } catch (Exception ignored) {} }
        }
    }

    /** 只读条目开头一段，标题不在这段里也无所谓 */
    private static String head(ZipFile zip, ZipEntry e, int max) {
        InputStream in = null;
        try {
            in = zip.getInputStream(e);
            byte[] b = new byte[max];
            StringBuilder sb = new StringBuilder();
            int total = 0, r;
            while (total < max && (r = in.read(b, 0, max - total)) > 0) {
                sb.append(new String(b, 0, r, java.nio.charset.Charset.forName("UTF-8")));
                total += r;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
    }

    /** 章标题：优先 <h1>…<h4>，其次 <title>，都取不到返回空串 */
    private static String titleOf(String html) {
        if (html == null || html.isEmpty()) return "";
        Matcher h = H_TAG.matcher(html);
        if (h.find()) {
            String t = EbookParser.htmlToText(h.group(1)).replace('\n', ' ').trim();
            if (!t.isEmpty() && t.length() <= TITLE_MAX) return t;
        }
        Matcher m = T_TAG.matcher(html);
        if (m.find()) {
            String t = EbookParser.htmlToText(m.group(1)).replace('\n', ' ').trim();
            if (!t.isEmpty() && t.length() <= TITLE_MAX) return t;
        }
        return "";
    }
}
