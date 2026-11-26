/*
 * 本文件的部分实现参照「开源阅读 Legado」（https://github.com/gedoor/legado）。
 * Legado：Copyright (C) 2019-2023 gedoor，GNU General Public License v3.0。
 * 本工程对其的使用方式与修改说明见仓库根目录 NOTICE，完整条款见 LICENSE。
 */
package com.mangjie.reader.net;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.util.Set;

/**
 * 网文章节正文的磁盘缓存。
 *
 * 对齐 Legado 的 BookHelp 做法：
 *  - 正文落盘在 <dataDir>/netcache/<书URL哈希>/<%05d-章名哈希>.txt，章序在文件名前 5 位，排序即章序；
 *  - 读正文先看磁盘，没有再取网络（取到就写盘）；
 *  - 清理不在换书时做，只在启动时「对账」：书目录还在、但书架（收藏 + 最近读过的书）里没有的，才删。
 */
public class ChapterCache {

    public static final String DIR_NAME = "netcache";

    private ChapterCache() {}

    /** 一本书一个目录，名字只取书 URL 的哈希：书名改了也不会变成孤儿 */
    /** 最近一次读写失败的原因，排查缓存不出章时看这个 */
    public static volatile String lastError = "";

    public static String bookKey(String bookUrl) {
        return md5(bookUrl == null ? "" : bookUrl).substring(0, 10);
    }

    public static File root(String dataDir) {
        return new File(dataDir == null ? "" : dataDir, DIR_NAME);
    }

    public static File bookDir(String dataDir, String bookUrl) {
        return new File(root(dataDir), bookKey(bookUrl));
    }

    private static String chapterName(int idx, String title) {
        return String.format("%05d-%s.txt", idx, md5(title == null ? "" : title).substring(0, 8));
    }

    private static File chapterFile(String dataDir, String bookUrl, int idx, String title) {
        return new File(bookDir(dataDir, bookUrl), chapterName(idx, title));
    }

    /** 读缓存；标题被书源改过、精确名找不到时，按章序前缀兜底 */
    public static String read(String dataDir, String bookUrl, int idx, String title) {
        try {
            File f = chapterFile(dataDir, bookUrl, idx, title);
            if (f.isFile() && f.length() > 0) return readText(f);
            File dir = bookDir(dataDir, bookUrl);
            String prefix = String.format("%05d-", idx);
            File[] all = dir.listFiles();
            if (all != null) {
                for (File g : all) {
                    if (g.isFile() && g.length() > 0 && g.getName().startsWith(prefix)) return readText(g);
                }
            }
        } catch (Throwable t) {
            lastError = "读缓存失败：" + t.getMessage();
        }
        return null;
    }

    /** 写缓存；同一章旧的（标题变过）顺手删掉，别留两份 */
    public static boolean write(String dataDir, String bookUrl, int idx, String title, String body) {
        if (body == null || body.trim().isEmpty()) return false;
        try {
            File dir = bookDir(dataDir, bookUrl);
            if (!dir.exists() && !dir.mkdirs()) return false;
            File f = chapterFile(dataDir, bookUrl, idx, title);
            String prefix = String.format("%05d-", idx);
            File[] all = dir.listFiles();
            if (all != null) {
                for (File g : all) {
                    if (g.isFile() && g.getName().startsWith(prefix) && !g.getName().equals(f.getName())) {
                        g.delete();
                    }
                }
            }
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
            w.write(body);
            w.close();
            writeBookMark(dataDir, bookUrl, title);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 目录里留一行书名，方便排查时知道这是哪本书（不参与对账） */
    private static void writeBookMark(String dataDir, String bookUrl, String lastChapter) {
        try {
            File dir = bookDir(dataDir, bookUrl);
            File mark = new File(dir, "_book.txt");
            if (mark.isFile() && mark.length() > 0) return;
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(mark, false), "UTF-8");
            w.write("bookUrl=" + (bookUrl == null ? "" : bookUrl) + "\n先读到=" + (lastChapter == null ? "" : lastChapter) + "\n");
            w.close();
        } catch (Throwable t) {
            lastError = "写缓存失败：" + t.getMessage();
        }
    }

    public static void clearBook(String dataDir, String bookUrl) {
        deleteDir(bookDir(dataDir, bookUrl));
    }

    public static void clearAll(String dataDir) {
        deleteDir(root(dataDir));
    }

    /** 本书已缓存多少章（只数 %05d-*.txt） */
    public static int chapterCount(String dataDir, String bookUrl) {
        File dir = bookDir(dataDir, bookUrl);
        File[] all = dir.listFiles();
        if (all == null) return 0;
        int n = 0;
        for (File f : all) {
            String name = f.getName();
            if (f.isFile() && name.length() > 5 && name.startsWith("0") && name.endsWith(".txt")
                    && name.indexOf('-') == 5) n++;
        }
        return n;
    }

    /** 本书缓存占用（字节） */
    public static long sizeOf(String dataDir, String bookUrl) {
        return sizeOfDir(bookDir(dataDir, bookUrl));
    }

    /**
     * 启动对账：把 netcache 下的书目录和「还留在书架里的书」比对，不在的删掉。
     *
     * @param aliveKeys 仍然有效的书的 bookKey（收藏 + 最近读过的书）
     * @return 删掉的书目录数
     */
    public static int reconcile(String dataDir, Set<String> aliveKeys) {
        File r = root(dataDir);
        if (!r.isDirectory()) return 0;
        File[] dirs = r.listFiles();
        if (dirs == null) return 0;
        int n = 0;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            if (aliveKeys == null || !aliveKeys.contains(d.getName())) {
                deleteDir(d);
                n++;
            }
        }
        return n;
    }

    private static long sizeOfDir(File d) {
        if (d == null || !d.exists()) return 0;
        if (d.isFile()) return d.length();
        File[] all = d.listFiles();
        if (all == null) return 0;
        long s = 0;
        for (File g : all) s += sizeOfDir(g);
        return s;
    }

    private static void deleteDir(File d) {
        if (d == null || !d.exists()) return;
        if (d.isFile()) { d.delete(); return; }
        File[] all = d.listFiles();
        if (all != null) for (File g : all) deleteDir(g);
        d.delete();
    }

    private static String readText(File f) throws Exception {
        InputStreamReader r = new InputStreamReader(new FileInputStream(f), "UTF-8");
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) {
            return "00000000000000000000000000000000";
        }
    }
}
