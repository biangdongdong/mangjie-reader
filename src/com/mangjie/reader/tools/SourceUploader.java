package com.mangjie.reader.tools;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.provider.OpenableColumns;

import com.mangjie.reader.net.DavClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发者专用：把本地书源文件传到服务器「书源」目录，顺手重建 清单.txt。
 *
 * 为什么要重建清单：Android 上没法列目录（PROPFIND 被 HttpURLConnection 挡死），
 * 客户端只能靠清单知道远端有哪些书源文件。清单原来要手工改，容易忘、
 * 忘了就拉不到新书源。这里在每次上传后把清单整体重写一遍：
 *   先 GET 现有清单 → 合并本次的文件名和字节数 → 再 PUT 回去。
 * 合并而不是覆盖，所以换设备上传也不会把之前传的抹掉。
 *
 * 只在开发者页调用；普通用户界面里没有这个入口。
 */
public final class SourceUploader {

    /** 服务器书源目录（路径里的中文要编码） */
    public static final String DIR = DavClient.newSub(enc("书源"));          // 新目录
    public static final String DIR_OLD = "/mangjie/" + enc("书源") + "/";    // 旧位置，读清单时兜底
    public static final String LIST = "清单.txt";

    private SourceUploader() {}

    public interface Done {
        /** 成功时 data 是这次传上去的原始内容，调用方可以直接在本地落一份；listed 是清单里现在有几个书源文件 */
        void onDone(boolean ok, String fileName, byte[] data, int listed, String msg);
    }

    public static void upload(final Context ctx, final Uri uri, final Handler ui, final Done done) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                byte[] keep = null;
                String name = "";
                int len = 0;
                int listed = 0;
                String msg = "";
                try {
                    name = displayName(ctx, uri);
                    if (name == null) name = "";
                    name = name.trim().replace('/', '_').replace('\\', '_');
                    if (name.isEmpty()) name = "书源-" + System.currentTimeMillis() + ".json";
                    String low = name.toLowerCase();
                    if (!low.endsWith(".json") && !low.endsWith(".txt")) {
                        msg = "只收 .json 或 .txt 的书源文件，这个没传";
                    } else if (LIST.equals(name)) {
                        msg = "不能拿清单当书源传，换个文件";
                    } else {
                        byte[] data = readAll(ctx, uri);
                        if (data == null || data.length == 0) {
                            msg = "这个文件读出来是空的，没传";
                        } else if (!DavClient.putApp(DIR + enc(name), data)) {
                            msg = "上传失败：" + DavClient.lastError();
                        } else {
                            ok = true;
                            len = data.length;
                            keep = data;
                            String listTxt = DavClient.getApp(DIR + enc(LIST));
                            if (listTxt == null && DavClient.lastError() != null
                                    && DavClient.lastError().contains("404"))
                                listTxt = DavClient.getApp(DIR_OLD + enc(LIST));   // 旧位置兜底
                            // 清单读不到又不能确定是「本来就没有」（404），就不敢覆盖，
                            // 否则一次网络抖动会把线上其他书源从清单里抹掉
                            String le = DavClient.lastError();
                            if (listTxt == null && (le == null || !le.contains("404"))) {
                                msg = "已上传 " + name + "，" + len / 1024 + " KB；但清单读不到（" + le
                                        + "），这次没覆盖清单，下次启动还拉不到它";
                            } else {
                                Map<String, Long> map = parseList(listTxt);
                                map.put(name, (long) len);
                                listed = map.size();
                                if (!DavClient.putApp(DIR + enc(LIST), buildList(map).getBytes("UTF-8"))) {
                                    msg = "已上传 " + name + "，" + len / 1024 + " KB；但清单没更新："
                                            + DavClient.lastError() + "。下次启动还拉不到它";
                                } else {
                                    msg = "已上传 " + name + "，" + len / 1024 + " KB，清单里现在 "
                                            + listed + " 个书源文件";
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    ok = false;
                    msg = "上传出错：" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : "：" + t.getMessage());
                }
                final boolean fok = ok;
                final String fname = name;
                final byte[] fdata = keep;
                final int flist = listed;
                final String fmsg = msg;
                ui.post(new Runnable() {
                    @Override public void run() { done.onDone(fok, fname, fdata, flist, fmsg); }
                });
            }
        }, "src-upload").start();
    }

    /**
     * 清空服务器「书源」目录：按清单把里面的书源文件逐个删掉，再删清单本身。
     * 只能清清单里列着的文件（列目录在 Android 上做不到），所以清单外的东西动不到。
     */
    public static void clearAll(final Handler ui, final Done done) {
        new Thread(new Runnable() {
            @Override public void run() {
                int deleted = 0;
                int failed = 0;
                String msg = "";
                try {
                    String listTxt = DavClient.getApp(DIR + enc(LIST));
                    Map<String, Long> map = parseList(listTxt);
                    for (String name : map.keySet()) {
                        if (DavClient.deleteApp(DIR + enc(name))) deleted++;
                        else failed++;
                    }
                    DavClient.deleteApp(DIR + enc(LIST));
                    msg = "已删 " + deleted + " 个书源文件" + (failed > 0 ? "，" + failed + " 个没删掉" : "")
                            + "，清单也删了";
                } catch (Throwable t) {
                    msg = "清空出错：" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : "：" + t.getMessage());
                }
                final int n = deleted;
                final String m = msg;
                ui.post(new Runnable() {
                    @Override public void run() { done.onDone(n > 0, "", null, 0, m); }
                });
            }
        }, "src-clear").start();
    }

    /** 取 SAF 选中的文件名 */
    private static String displayName(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String s = c.getString(i);
                    if (s != null && !s.isEmpty()) return s;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        String p = uri.getLastPathSegment();
        return p == null ? "" : p;
    }

    private static byte[] readAll(Context ctx, Uri uri) {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /** 读现有清单：每行「文件名|字节数」，字节数可省，# 是注释 */
    private static Map<String, Long> parseList(String txt) {
        Map<String, Long> map = new LinkedHashMap<String, Long>();
        if (txt == null) return map;
        for (String line : txt.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int bar = t.lastIndexOf('|');
            String name;
            long size = -1;
            if (bar > 0) {
                name = t.substring(0, bar).trim();
                try { size = Long.parseLong(t.substring(bar + 1).trim()); } catch (Throwable ignored) {}
            } else {
                name = t;
            }
            if (name.isEmpty()) continue;
            String low = name.toLowerCase();
            if (!low.endsWith(".json") && !low.endsWith(".txt")) continue;
            if (LIST.equals(name)) continue;
            map.put(name, size);
        }
        return map;
    }

    private static String buildList(Map<String, Long> map) {
        List<String> keys = new ArrayList<String>(map.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        sb.append("# 每行一个书源文件名，可写「文件名|字节数」\n");
        sb.append("# 这份清单由开发者页的「上传书源」自动重建，一般不用手改\n");
        for (String k : keys) {
            Long v = map.get(k);
            sb.append(k).append('|').append(v == null ? -1 : v).append('\n');
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s;
        }
    }
}
