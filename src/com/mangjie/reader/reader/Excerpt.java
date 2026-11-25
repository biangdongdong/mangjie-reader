package com.mangjie.reader.reader;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 摘抄落地：剪贴板 + 摘抄本文件，两处都写；任一处失败都要如实返回 */
public final class Excerpt {

    public static final int OK_BOTH = 0;
    public static final int OK_CLIP = 1;      // 剪贴板成功、文件失败
    public static final int FAIL = 2;         // 两处都失败

    private Excerpt() {}

    public static boolean copy(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("摘抄", text));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 追加一段到 摘抄/<书名>.txt，返回是否写成功 */
    public static boolean append(File dataDir, String bookName, String chapter,
                                 int fromSent, int toSent, String text) {
        try {
            File dir = new File(dataDir, "摘抄");
            if (!dir.exists() && !dir.mkdirs()) return false;
            String name = bookName == null || bookName.trim().isEmpty() ? "未命名" : bookName.trim();
            name = name.replace('/', '_').replace('\\', '_');
            File f = new File(dir, name + ".txt");
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date());
            StringBuilder head = new StringBuilder();
            head.append("【").append(name);
            if (chapter != null && !chapter.isEmpty()) head.append(' ').append(chapter);
            head.append(" · 第").append(fromSent + 1).append('–').append(toSent + 1).append("句】")
                .append(stamp);
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write((head.toString() + "\n").getBytes("UTF-8"));
                out.write((text == null ? "" : text).getBytes("UTF-8"));
                out.write("\n\n".getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一次走两处；结果码给调用方拼播报 */
    public static int save(Context ctx, File dataDir, String bookName, String chapter,
                           int fromSent, int toSent, String text) {
        boolean clip = copy(ctx, text);
        boolean file = append(dataDir, bookName, chapter, fromSent, toSent, text);
        if (clip && file) return OK_BOTH;
        if (clip) return OK_CLIP;
        return FAIL;
    }
}
