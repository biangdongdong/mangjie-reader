/*
 * 极简替代实现，顶替 Backtalk（Apache-2.0）对手势库日志封装的依赖。
 * 转给 android.util.Log，行为一致：只在 VERBOSE/DEBUG 级别输出。
 */
package com.google.android.libraries.accessibility.utils.log;

import android.util.Log;

public final class LogUtils {
    private LogUtils() {}

    public static void v(String tag, String msg) { Log.v(tag, msg); }

    public static void v(String tag, String msg, Object... args) { Log.v(tag, safe(msg, args)); }

    public static void d(String tag, String msg) { Log.d(tag, msg); }

    public static void d(String tag, String msg, Object... args) { Log.d(tag, safe(msg, args)); }

    public static void e(String tag, String msg) { Log.e(tag, msg); }

    public static void e(String tag, String msg, Throwable tr) { Log.e(tag, msg, tr); }

    public static void e(String tag, String msg, Object... args) { Log.e(tag, safe(msg, args)); }

    public static void w(String tag, String msg) { Log.w(tag, msg); }

    private static String safe(String fmt, Object... args) {
        if (args == null || args.length == 0) return fmt;
        try { return String.format(fmt, args); } catch (Throwable t) { return fmt; }
    }
}
