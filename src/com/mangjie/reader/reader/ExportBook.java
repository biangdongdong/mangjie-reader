package com.mangjie.reader.reader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 整本导出：TXT 与 EPUB。
 *
 * 只管「按章抓正文 → 落盘」，抓取怎么抓由调用方给 Fetcher（阅读器里就是 nbFetchChapterText），
 * 这样在线书和缓存书走同一套，本地书直接给一段全文也能用。
 *
 * EPUB 按最小合规结构写：mimetype 必须是第一个条目且不压缩，否则阅读器会当它不是 EPUB。
 */
public final class ExportBook {

    public interface Fetcher {
        /** 取一章正文，失败返回空串 */
        String fetch(String url);
    }

    public interface Progress {
        /** 每章抓完回一次，出错不要抛 */
        void step(int done, int total, String chapterTitle);
    }

    private ExportBook() {}

    /** 本地书或已有全文：直接写成 TXT */
    public static boolean writeTxt(File out, String title, String author, String text) {
        if (out == null) return false;
        try {
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fo = new FileOutputStream(out);
            OutputStreamWriter w = new OutputStreamWriter(fo, "UTF-8");
            w.write(header(title, author, text));
            w.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 在线书：逐章抓取写成 TXT，返回写成功的章数，0 表示整本失败 */
    public static int exportTxt(File out, String title, String author,
                                List<String[]> chapters, Fetcher fetcher, Progress cb) {
        if (out == null || chapters == null || chapters.isEmpty() || fetcher == null) return 0;
        int ok = 0;
        try {
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fo = new FileOutputStream(out);
            OutputStreamWriter w = new OutputStreamWriter(fo, "UTF-8");
            w.write("书名：" + safe(title) + "\r\n");
            if (author != null && !author.trim().isEmpty()) w.write("作者：" + safe(author) + "\r\n");
            w.write("\r\n");

            StringBuilder body = new StringBuilder();
            for (int i = 0; i < chapters.size(); i++) {
                String[] ch = chapters.get(i);
                String t = ch.length > 0 ? ch[0] : ("第" + (i + 1) + "章");
                String u = ch.length > 1 ? ch[1] : "";
                String text = "";
                try {
                    text = fetcher.fetch(u);
                } catch (Throwable ignored) { }
                if (text == null) text = "";
                text = text.trim();
                if (!text.isEmpty()) ok++;
                body.append("\r\n\r\n").append(t).append("\r\n\r\n").append(text);
                if (cb != null) cb.step(i + 1, chapters.size(), t);
                // 每 20 章落一次盘，跑到一半被系统杀掉也不至于整本白跑
                if ((i + 1) % 20 == 0) {
                    w.write(body.toString());
                    w.flush();
                    body.setLength(0);
                }
            }
            w.write(body.toString());
            w.close();
            return ok;
        } catch (Exception e) {
            return ok;
        }
    }

    /** 在线书：逐章抓取写成 EPUB，返回写进书的章数 */
    public static int exportEpub(File out, String title, String author,
                                 List<String[]> chapters, Fetcher fetcher, Progress cb) {
        if (out == null || chapters == null || chapters.isEmpty() || fetcher == null) return 0;
        int ok = 0;
        ZipOutputStream zos = null;
        try {
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            zos = new ZipOutputStream(new FileOutputStream(out));
            // 1. mimetype：第一个条目，必须 STORED（不压缩），否则不算合规 EPUB
            byte[] mimeBytes = "application/epub+zip".getBytes("UTF-8");
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED);
            mime.setSize(mimeBytes.length);
            mime.setCompressedSize(mimeBytes.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(mimeBytes);
            mime.setCrc(crc.getValue());
            zos.putNextEntry(mime);
            zos.write(mimeBytes);
            zos.closeEntry();

            // 2. META-INF/container.xml
            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                    + "  <rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                    + "media-type=\"application/oebps-package+xml\"/></rootfiles>\n"
                    + "</container>\n").getBytes("UTF-8"));
            zos.closeEntry();

            // 3. 逐章 XHTML
            int written = 0;
            StringBuilder manifest = new StringBuilder();
            StringBuilder spine = new StringBuilder();
            StringBuilder navPoints = new StringBuilder();
            StringBuilder tocTitles = new StringBuilder();
            for (int i = 0; i < chapters.size(); i++) {
                String[] ch = chapters.get(i);
                String t = ch.length > 0 ? ch[0] : ("第" + (i + 1) + "章");
                String u = ch.length > 1 ? ch[1] : "";
                String text = "";
                try {
                    text = fetcher.fetch(u);
                } catch (Throwable ignored) { }
                if (text == null) text = "";
                text = text.trim();
                if (text.isEmpty()) {
                    if (cb != null) cb.step(i + 1, chapters.size(), t);
                    continue;             // 抓不到的章不写空页，免得整本里到处是空章
                }
                ok++;
                written++;
                String id = "ch" + written;
                String fn = "chapter" + written + ".xhtml";
                zos.putNextEntry(new ZipEntry("OEBPS/" + fn));
                zos.write(xhtml(t, text).getBytes("UTF-8"));
                zos.closeEntry();
                manifest.append("    <item id=\"").append(id).append("\" href=\"").append(fn)
                        .append("\" media-type=\"application/xhtml+xml\"/>\n");
                spine.append("    <itemref idref=\"").append(id).append("\"/>\n");
                navPoints.append("    <navPoint id=\"nav").append(written).append("\" playOrder=\"")
                        .append(written).append("\"><navLabel><text>").append(esc(t))
                        .append("</text></navLabel><content src=\"").append(fn).append("\"/></navPoint>\n");
                tocTitles.append("<p><a href=\"").append(fn).append("\">").append(esc(t)).append("</a></p>\n");
                if (cb != null) cb.step(i + 1, chapters.size(), t);
            }
            if (written == 0) {
                zos.close();
                try { out.delete(); } catch (Throwable ignored) { }
                return 0;
            }

            // 4. 目录页
            zos.putNextEntry(new ZipEntry("OEBPS/nav.xhtml"));
            zos.write(xhtml("目录", tocTitles.toString(), true).getBytes("UTF-8"));
            zos.closeEntry();
            manifest.append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\"/>\n");
            spine.insert(0, "    <itemref idref=\"nav\"/>\n");

            // 5. content.opf
            String bid = "mj-" + Math.abs((title + author).hashCode());
            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"BookId\">\n"
                    + "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                    + "    <dc:title>" + esc(title) + "</dc:title>\n"
                    + "    <dc:creator>" + esc(author == null ? "" : author) + "</dc:creator>\n"
                    + "    <dc:language>zh</dc:language>\n"
                    + "    <dc:identifier id=\"BookId\">" + bid + "</dc:identifier>\n"
                    + "  </metadata>\n"
                    + "  <manifest>\n"
                    + "    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n"
                    + manifest
                    + "  </manifest>\n"
                    + "  <spine toc=\"ncx\">\n"
                    + spine
                    + "  </spine>\n"
                    + "</package>\n").getBytes("UTF-8"));
            zos.closeEntry();

            // 6. toc.ncx
            zos.putNextEntry(new ZipEntry("OEBPS/toc.ncx"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n"
                    + "  <head><meta name=\"dtb:uid\" content=\"" + bid + "\"/></head>\n"
                    + "  <docTitle><text>" + esc(title) + "</text></docTitle>\n"
                    + "  <navMap>\n" + navPoints + "  </navMap>\n"
                    + "</ncx>\n").getBytes("UTF-8"));
            zos.closeEntry();
            zos.close();
            return ok;
        } catch (Exception e) {
            try { if (zos != null) zos.close(); } catch (Throwable ignored) { }
            return ok;
        }
    }

    // ---------------- 内部 ----------------

    private static String header(String title, String author, String text) {
        StringBuilder b = new StringBuilder();
        b.append("书名：").append(safe(title)).append("\r\n");
        if (author != null && !author.trim().isEmpty()) b.append("作者：").append(safe(author)).append("\r\n");
        b.append("\r\n").append(text == null ? "" : text);
        return b.toString();
    }

    private static String xhtml(String title, String text) {
        return xhtml(title, text, false);
    }

    private static String xhtml(String title, String text, boolean asIsBody) {
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<!DOCTYPE html>\n")
                .append("<html xmlns=\"http://www.w3.org/1999/xhtml\">\n")
                .append("<head><meta charset=\"utf-8\"/><title>").append(esc(title)).append("</title></head>\n")
                .append("<body>\n");
        if (asIsBody) {
            b.append(text);
        } else {
            b.append("<h2>").append(esc(title)).append("</h2>\n");
            String[] lines = (text == null ? "" : text).split("\\r?\\n");
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                b.append("<p>").append(esc(t)).append("</p>\n");
            }
        }
        b.append("</body>\n</html>\n");
        return b.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace('/', '_').replace('\\', '_').replace(':', '：');
    }
}
