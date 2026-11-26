/*
 * 本文件的部分实现参照「开源阅读 Legado」（https://github.com/gedoor/legado）。
 * Legado：Copyright (C) 2019-2023 gedoor，GNU General Public License v3.0。
 * 本工程对其的使用方式与修改说明见仓库根目录 NOTICE，完整条款见 LICENSE。
 */
package com.mangjie.reader.reader;

/** 阅读进度：位置键名、百分比显示、按百分比跳句 */
public final class BookProgress {

    private BookProgress() {}

    /** 书身份指纹的进程内缓存：每句存进度都要取键，不能每次都读盘 */
    private static final java.util.Map<String, String> ID_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    /**
     * 书的身份键：按「文件长度 + 首尾各 8KB 的摘要」认书，不再按路径。
     * 改名、换目录都能把进度认回来（对齐 Legado 认书而不是认路径的思路）。
     * 文件不在时退回按路径摘要，至少不会串到别的书。
     */
    public static String keyOf(String bookPath) {
        if (bookPath == null || bookPath.isEmpty()) return "";
        String cached = ID_CACHE.get(bookPath);
        if (cached != null) return cached;
        String k = fingerprint(bookPath);
        ID_CACHE.put(bookPath, k);
        return k;
    }

    private static String fingerprint(String bookPath) {
        try {
            java.io.File f = new java.io.File(bookPath);
            long len = f.length();
            if (!f.isFile() || len <= 0) return "p" + md5(bookPath.getBytes("UTF-8")).substring(0, 10);
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] buf = new byte[8192];
                int n = (int) Math.min(8192, len);
                raf.seek(0);
                raf.readFully(buf, 0, n);
                md.update(buf, 0, n);
                if (len > 8192) {
                    raf.seek(Math.max(8192, len - 8192));
                    int m = (int) Math.min(8192, len);
                    raf.readFully(buf, 0, m);
                    md.update(buf, 0, m);
                }
                md.update((len + "").getBytes("UTF-8"));
                return "f" + len + "-" + hex(md.digest());
            } finally {
                raf.close();
            }
        } catch (Throwable t) {
            return "p" + md5(bookPath.getBytes()).substring(0, 10);
        }
    }

    private static final int DIGEST_SIZE = 4;

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length && i < DIGEST_SIZE; i++) sb.append(String.format("%02x", b[i]));
        return sb.length() == 0 ? "0" : sb.toString();
    }

    private static String md5(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(Math.abs(new String(data).hashCode()));
        }
    }

    public static String posKey(String bookPath) {
        return "pos2:" + keyOf(bookPath);
    }

    /** 老版本按路径存的三个键，只在迁移时用 */
    public static String legacyPosKey(String bookPath) { return "pos:" + bookPath; }
    public static String legacyOffKey(String bookPath) { return "posOff:" + bookPath; }
    public static String legacyTotalKey(String bookPath) { return legacyPosKey(bookPath) + "_n"; }

    /** 按字符偏移存的进度：切句规则再怎么改，这个位置都不动 */
    /** 这本书一共多少句：书架行算百分比要用 */
    public static String totalKey(String bookPath) {
        return posKey(bookPath) + "_n";
    }

    /** 本地大书按章装载时，当前章号（0 起）；没走按章模式就是 0 */
    public static String chapterKey(String bookPath) {
        return "chap:" + keyOf(bookPath);
    }

    public static String posOffKey(String bookPath) {
        return "posOff2:" + keyOf(bookPath);
    }

    /** 前缀和缓存：每句存位置都要取偏移，不能每次从头累加（大书会卡） */
    private static java.util.List<String> sumSent = null;
    private static int sumLen = -1;
    private static int[] sumPrefix = null;

    /** 第 idx 句从头算起的字符偏移（前 idx 句长度之和） */
    public static int offsetOf(java.util.List<String> sent, int idx) {
        if (sent == null || sent.isEmpty()) return 0;
        if (idx < 0) idx = 0;
        if (idx > sent.size()) idx = sent.size();
        int n = sent.size();
        if (sent != sumSent || sumLen != n || sumPrefix == null || sumPrefix.length != n + 1) {
            int[] pre = new int[n + 1];
            int acc = 0;
            for (int i = 0; i < n; i++) {
                String t = sent.get(i);
                acc += t == null ? 0 : t.length();
                pre[i + 1] = acc;
            }
            sumPrefix = pre;
            sumSent = sent;
            sumLen = n;
        }
        return sumPrefix[idx];
    }

    /** 字符偏移反查句子序号：返回偏移落在的那一句，越界夹到首尾 */
    public static int indexAtOffset(java.util.List<String> sent, int off) {
        if (sent == null || sent.isEmpty()) return 0;
        if (off <= 0) return 0;
        int acc = 0;
        for (int i = 0; i < sent.size(); i++) {
            String t = sent.get(i);
            int n = t == null ? 0 : t.length();
            if (off < acc + n) return i;
            acc += n;
        }
        return sent.size() - 1;
    }

    public static String bmKey(String bookPath) {
        return "bm2:" + keyOf(bookPath);      // 书签也跟书走，改名不丢
    }

    public static String bmOffKey(String bookPath) {
        return "bmOff2:" + keyOf(bookPath);
    }

    public static String legacyBmKey(String bookPath) { return "bm:" + bookPath; }
    public static String legacyBmOffKey(String bookPath) { return "bmOff:" + bookPath; }

    /** 第 idx 句（从 0 数）在全书中的百分比，1 起算，至少 1% */
    public static int percent(int total, int idx) {
        if (total <= 0) return 0;
        return Math.round((idx + 1) * 100f / total);
    }

    /** 百分比反查句子序号，夹在 0 到 total-1 */
    public static int indexAtPercent(int total, int pct) {
        if (total <= 0) return 0;
        int idx = (int) Math.floor(total * pct / 100f);
        if (idx < 0) idx = 0;
        if (idx > total - 1) idx = total - 1;
        return idx;
    }
}
