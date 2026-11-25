package com.mangjie.reader.tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把公有存储里的旧数据整体搬进应用私有目录，只跑一次。
 *
 * 规矩（用户定的）：逐文件比对大小，全部通过才算成功，成功后删掉外部旧目录；
 * 任何一个文件失败就不删源、不写标记，下次启动重试。删源不可逆，所以宁可不删也不半途删。
 */
public class LegacyMigrate {

    /** 迁移结果，给调用方播报和写日志用 */
    public static class R {
        public boolean ok;
        public boolean noSource;      // 外部旧目录本来就没有，视为已完成
        public int files;             // 成功搬过去的文件数
        public int skipped;           // 目标已存在且大小一致，没重搬
        public long bytes;
        public String failName;       // 第一个失败的文件名
        public int failCount;
    }

    public static R run(File src, File dst) {
        R r = new R();
        if (src == null || !src.exists()) {
            r.ok = true;
            r.noSource = true;
            return r;
        }
        try {
            if (!dst.exists()) dst.mkdirs();
        } catch (Throwable t) {
            r.failName = "创建私有目录失败：" + t.getMessage();
            r.failCount = 1;
            return r;
        }
        copyTree(src, dst, r, 0);
        r.ok = r.failCount == 0;
        return r;
    }

    /** 搬完后删掉外部旧目录，调用方确认 r.ok 之后才能调 */
    public static void deleteSource(File src) {
        if (src == null) return;
        try { deleteRecursive(src); } catch (Throwable ignored) { }
    }

    private static void copyTree(File src, File dst, R r, int depth) {
        if (depth > 8) return;      // 防符号链接套娃
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                File nd = new File(dst, k.getName());
                try { if (!nd.exists()) nd.mkdirs(); } catch (Throwable ignored) { }
                copyTree(k, nd, r, depth + 1);
            } else if (k.isFile()) {
                File to = new File(dst, k.getName());
                // 目标已存在且大小一致：不重搬，算成功（重复启动不会反复拷一遍）
                if (to.exists() && to.length() == k.length()) {
                    r.skipped++;
                    continue;
                }
                if (copyOne(k, to)) {
                    r.files++;
                    r.bytes += k.length();
                } else {
                    r.failCount++;
                    if (r.failName == null) r.failName = k.getName();
                }
            }
        }
    }

    /** 拷一个文件并按大小校验；大小不对就当失败，目标也删掉免得留半截 */
    private static boolean copyOne(File from, File to) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
        if (to.length() != from.length()) {
            try { to.delete(); } catch (Throwable ignored) { }
            return false;
        }
        return true;
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursive(k);
        }
        try { f.delete(); } catch (Throwable ignored) { }
    }
}
