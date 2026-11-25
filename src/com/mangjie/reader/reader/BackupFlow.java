package com.mangjie.reader.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.mangjie.reader.net.DavClient;
import com.mangjie.reader.tools.Workers;

import java.io.File;

/**
 * 备份上传与服务器恢复：网络、落盘、恢复都收在这里，Activity 只负责播报和刷新界面。
 *
 * 服务器路径沿用老约定 /mangjie/配置备份.json（与 Legado 的 WebDAV 备份同一思路：
 * 一份 json 装走设置、书源、书架、进度）。
 * 恢复完必须让调用方重读设置，否则内存里还是旧值，退后台时 savePrefs 会把恢复结果覆盖掉。
 */
public final class BackupFlow {

    public static final String REMOTE_PATH = "/mangjie/" + enc("盲界阅读") + "/" + enc("配置备份.json");
    /** 搬家前的老位置，读不到新位置时兜底 */
    public static final String REMOTE_PATH_OLD = "/mangjie/" + enc("配置备份.json");

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    /** 结果回调，一定在主线程 */
    public interface Cb {
        /**
         * @param msg      给用户念的一句话
         * @param restored 是否真的恢复了内容（true 时调用方要重读设置、重载规则）
         */
        void done(String msg, boolean restored);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BackupFlow() {}

    /** 上传：把当前状态打成一份 json 推到服务器 */
    public static void upload(final Context ctx, final File dataDir, final SharedPreferences prefs, final Cb cb) {
        Workers.run(new Runnable() {
            @Override public void run() {
                String msg;
                boolean ok = false;
                try {
                    String json = Backup.dump(ctx, dataDir, prefs);
                    ok = DavClient.put(REMOTE_PATH, json.getBytes("UTF-8"));
                    msg = ok ? "备份已上传服务器" : "上传失败";
                } catch (Throwable t) {
                    msg = "上传失败：" + t.getMessage();
                }
                post(cb, msg, false, ok);
            }
        });
    }

    /** 从服务器拉回来恢复：先在本机存一份兜底，再往 prefs 里写 */
    public static void downloadRestore(final Context ctx, final File dataDir, final SharedPreferences prefs, final Cb cb) {
        Workers.run(new Runnable() {
            @Override public void run() {
                String msg;
                boolean restored = false;
                try {
                    String json = DavClient.get(REMOTE_PATH);
                    if (json == null || json.trim().isEmpty()) json = DavClient.get(REMOTE_PATH_OLD);   // 老位置兜底
                    if (json == null || json.trim().isEmpty()) {
                        msg = "服务器上还没有备份，先上传一次";
                    } else {
                        File bak = Backup.save(dataDir, json);            // 恢复前先落一份本地，出问题还能退回来
                        msg = "已从服务器恢复：" + Backup.restore(ctx, dataDir, prefs, json)
                                + "（恢复前的状态已存本地 " + bak.getName() + "）";
                        restored = true;
                    }
                } catch (Throwable t) {
                    msg = "恢复失败：" + t.getMessage();
                }
                post(cb, msg, restored, false);
            }
        });
    }

    private static void post(final Cb cb, final String msg, final boolean restored, final boolean okFlag) {
        if (cb == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() { cb.done(msg, restored); }
        });
    }
}
