package com.mangjie.reader;

import android.app.Application;

/**
 * 只干一件事：把应用私有目录尽早拿到手并存成静态。
 *
 * 为什么非要这么绕：MainActivity 的数据根 DATA_DIR 是 static final，类加载时就要算出值，
 * 那时候还没有 Activity 实例，调不到 getFilesDir()。Application.onCreate 一定早于任何
 * Activity，所以在这里存一份，那边直接用。
 *
 * 1.1.45 起数据根从 /sdcard/盲界阅读 改成 files/，书源、订阅源、规则、字体、词典、
 * 备份、缓存、日志全都跟着走，外部只剩「导出」一个落地口。
 */
public class App extends Application {

    private static Application self;
    private static java.io.File files;

    @Override public void onCreate() {
        super.onCreate();
        self = this;
        try { files = getFilesDir(); } catch (Throwable ignored) { }
    }

    public static Application ctx() { return self; }

    /** 私有数据根；理论上不会为空，取不到才退回旧路径兜底 */
    public static String filesPath() {
        try {
            if (files != null) return files.getAbsolutePath();
        } catch (Throwable ignored) { }
        return "/sdcard/盲界阅读";
    }

    public static java.io.File filesDir() { return files; }

    /**
     * 导出落地目录：优先公有存储 /sdcard/盲界阅读导出（文件管理器看得见、能拷走），
     * 没有「所有文件访问」权限时退回私有 files/导出，绝不因为写不进去就报失败。
     */
    public static java.io.File exportDir() {
        try {
            java.io.File pub = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(), "盲界阅读导出");
            if (!pub.exists()) pub.mkdirs();
            java.io.File probe = new java.io.File(pub, ".probe");
            boolean ok = probe.exists() || probe.createNewFile();
            if (ok) {
                probe.delete();
                return pub;
            }
        } catch (Throwable ignored) { }
        java.io.File inner = new java.io.File(files, "导出");
        try { inner.mkdirs(); } catch (Throwable ignored) { }
        return inner;
    }

    /** 导出到私有目录时要把话说清楚，免得用户以为导出丢了 */
    public static boolean exportIsPublic() {
        java.io.File d = exportDir();
        return d.getAbsolutePath().startsWith(
                android.os.Environment.getExternalStorageDirectory().getAbsolutePath());
    }
}
