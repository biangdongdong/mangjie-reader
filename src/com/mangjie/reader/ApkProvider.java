package com.mangjie.reader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import android.os.Environment;
import java.io.File;

/**
 * 极简 FileProvider：把本地 apk 以 content:// 暴露给系统安装器（Android 7 起 file:// 会被拒）。
 * 只允许读取外部存储「盲界阅读」目录下的文件，别的路径一律拒绝。
 */
public class ApkProvider extends ContentProvider {

    /** 普通版是 com.mangjie.reader.apk，语音版是 com.mangjie.reader.tts.apk；
     *  写成常量会让语言版把 uri 拼到普通版头上，所以按实际包名算。 */
    public static String authorityOf(android.content.Context c) {
        return c.getPackageName() + ".apk";
    }
    private static String rootDir() {
        return Environment.getExternalStorageDirectory().getAbsolutePath() + "/盲界阅读/";
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        String path = uri.getPath();
        if (path == null) throw new java.io.FileNotFoundException("no path");
        File f = new File(path);
        try { f = f.getCanonicalFile(); } catch (Exception ignored) {}
        if (!f.exists()) throw new java.io.FileNotFoundException(path);
        // 只放行 apk 与日志文本：apk 给系统安装器，txt 给「分享日志」用，
        // 且必须是本应用目录或「盲界阅读」目录下的文件
        String abs = f.getAbsolutePath();
        String lower = abs.toLowerCase();
        if (!lower.endsWith(".apk") && !lower.endsWith(".txt")) throw new java.io.FileNotFoundException("非安装包");
        String selfData = "/Android/data/" + getContext().getPackageName();
        // 1.1.45：数据根搬进私有目录后，安装包也可能在 files/apk 里，这条路要放行，
        // 否则点安装会报「越权路径」，用户只会看到装不上
        String innerData = "";
        try { innerData = getContext().getFilesDir().getAbsolutePath(); } catch (Throwable ignored) { }
        if (!(abs.startsWith(rootDir()) || abs.contains(selfData)
                || abs.contains("/Android/data/com.mangjie.reader")
                || (innerData != null && !innerData.isEmpty() && abs.startsWith(innerData)))) {
            throw new java.io.FileNotFoundException("越权路径");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        return new MatrixCursor(new String[]{"_display_name", "_size"});
    }

    @Override public String getType(Uri uri) {
        String p = uri.getPath();
        if (p != null && p.toLowerCase().endsWith(".txt")) return "text/plain";
        return "application/vnd.android.package-archive";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
