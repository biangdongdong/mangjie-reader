package com.mangjie.reader.reader;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;

/** 读纯文本：最多读 maxBytes，自动认编码（UTF-8 / UTF-16 / GB18030 / Big5），读不到返回 null */
public final class TextFile {

    private static final String TAG = "TextFile";

    private TextFile() {}

    public static String read(File f, int maxBytes) {
        if (f == null) return null;
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), (long) maxBytes)];
            FileInputStream in = new FileInputStream(f);
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            in.close();

            EncodingDetect.Result r2 = EncodingDetect.detect(buf, n);
            if (r2 == null) return null;
            Log.i(TAG, f.getName() + "　编码=" + r2.name + "　字节=" + n);
            return new String(buf, r2.offset, n - r2.offset, r2.charset);
        } catch (Exception e) {
            Log.w(TAG, "读取失败 " + f, e);
            return null;
        }
    }
}
