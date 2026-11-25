package com.mangjie.reader.view;

import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * 主题参数集中存取：光标条颜色 / 形状 / 不透明度 / 效果，背景与文字色，标题栏跟随。
 *
 * 所有 key 都在 SharedPreferences("reader") 里，缺省值按真机敲定的一套：
 * 圆角矩形、暖红、不透明 100%、内高光关、投影关、渐变上下。
 */
public final class ThemeColors {

    private ThemeColors() {}

    public static final int HL_DEFAULT = 0xFFF0503F;   // 原来的暖红

    // ---- 光标条 ----------------------------------------------------------

    public static int hlColor(SharedPreferences p) {
        return p.getInt("hlColor", HL_DEFAULT);
    }

    public static void setHlColor(SharedPreferences p, int c) {
        p.edit().putInt("hlColor", c | 0xFF000000).apply();
    }

    public static final String[] SHAPE_NAMES = {"圆角矩形", "直角矩形", "胶囊", "下划线", "左侧竖条", "空心描边"};

    public static int hlShape(SharedPreferences p) {
        return Math.max(0, Math.min(SHAPE_NAMES.length - 1, p.getInt("hlShape", 0)));
    }

    public static String shapeName(SharedPreferences p) {
        return SHAPE_NAMES[hlShape(p)];
    }

    public static int hlAlpha(SharedPreferences p) {
        return Math.max(0, Math.min(100, p.getInt("hlAlpha", 100)));
    }

    public static boolean hlGloss(SharedPreferences p) { return p.getBoolean("hlGloss", false); }

    public static boolean hlShadow(SharedPreferences p) { return p.getBoolean("hlShadow", false); }

    /** 渐变方向：0 上下 / 1 左右 / 2 无渐变 */
    public static int hlGrad(SharedPreferences p) {
        return Math.max(0, Math.min(2, p.getInt("hlGrad", 0)));
    }

    public static final String[] GRAD_NAMES = {"上下渐变", "左右渐变", "纯色无渐变"};

    public static String gradName(SharedPreferences p) { return GRAD_NAMES[hlGrad(p)]; }

    /** 把不透明度合成到颜色里 */
    public static int withAlpha(int color, SharedPreferences p) {
        int a = Math.round(hlAlpha(p) * 2.55f);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    // ---- 背景与文字色 ----------------------------------------------------

    public static int bg(SharedPreferences p) { return p.getInt("cusBg", 0xFF101010); }

    public static int fg(SharedPreferences p) { return p.getInt("cusFg", 0xFFFFE082); }

    // ---- 标题栏跟随 ------------------------------------------------------

    public static boolean titleFollow(SharedPreferences p) { return p.getBoolean("titleFollow", true); }

    // ---- 对比度 ----------------------------------------------------------

    /** WCAG 对比度比值，1.0–21.0 之间；低于 3 算看不清 */
    public static double contrast(int bg, int fg) {
        double l1 = luminance(bg), l2 = luminance(fg);
        double hi = Math.max(l1, l2), lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double luminance(int c) {
        double[] v = new double[3];
        v[0] = Color.red(c) / 255.0;
        v[1] = Color.green(c) / 255.0;
        v[2] = Color.blue(c) / 255.0;
        for (int i = 0; i < 3; i++) {
            v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    /** 对比度一句话："对比度 7.2 比 1，足够" 或 "对比度 2.1 比 1，偏低" */
    public static String contrastText(int bg, int fg) {
        double r = contrast(bg, fg);
        String judge;
        if (r >= 7.0) judge = "很清晰";
        else if (r >= 4.5) judge = "清晰";
        else if (r >= 3.0) judge = "勉强";
        else judge = "偏低，可能看不清";
        return "对比度 " + String.format("%.1f", r) + " 比 1，" + judge;
    }

    /** 一行摘要，给设置页与播报共用 */
    public static String summary(SharedPreferences p) {
        return ColorName.nameOf(hlColor(p)) + "、" + shapeName(p) + "、不透明度 " + hlAlpha(p) + "%";
    }
}
