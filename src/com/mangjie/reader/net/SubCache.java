package com.mangjie.reader.net;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;

/**
 * 订阅正文的本地缓存：打开过的文章存一份纯文本，断网也能听。
 *
 * 目录 数据目录/订阅缓存/，文件名取文章链接的 md5 前 20 位——链接里常带斜杠问号，
 * 直接拿来当文件名在部分机型上会写不进去。正文按 UTF-8 存，原文太长也不压缩，
 * 现在一篇也就几十 KB，不值得为省空间引入压缩。
 */
public final class SubCache {

    public static final String DIR_NAME = "订阅缓存";

    private SubCache() {}

    public static File dir(String dataDir) {
        File d = new File(dataDir, DIR_NAME);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File fileOf(String dataDir, String url) {
        String name;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dg = md.digest((url == null ? "" : url).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : dg) sb.append(String.format("%02x", b));
            name = sb.substring(0, 20);
        } catch (Throwable t) {
            name = "u" + Math.abs((url == null ? "" : url).hashCode());
        }
        return new File(dir(dataDir), name + ".txt");
    }

    /** 读缓存；没有或太短（多半是上次写一半失败）都当没缓存 */
    public static String read(String dataDir, String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            File f = fileOf(dataDir, url);
            if (!f.isFile() || f.length() < 10) return null;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean write(String dataDir, String url, String text) {
        if (url == null || url.isEmpty() || text == null || text.trim().isEmpty()) return false;
        try {
            File f = fileOf(dataDir, url);
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(text);
            w.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 联网重取前先把旧的删掉，免得写一半旧的新的掺在一起 */
    public static boolean clear(String dataDir, String url) {
        if (url == null || url.isEmpty()) return false;
        try { return fileOf(dataDir, url).delete(); } catch (Throwable t) { return false; }
    }

    /** 清掉整个订阅缓存目录（设置页「清空订阅缓存」用） */
    public static boolean clearAll(String dataDir) {
        File d = dir(dataDir);
        File[] all = d == null ? null : d.listFiles();
        boolean ok = true;
        if (all != null) for (File f : all) if (f.isFile() && !f.delete()) ok = false;
        return ok;
    }

    /** 一共占多少字节，设置页显示用 */
    public static long sizeOf(String dataDir) {
        long n = 0;
        File[] all = dir(dataDir).listFiles();
        if (all == null) return 0;
        for (File f : all) if (f.isFile()) n += f.length();
        return n;
    }
}
