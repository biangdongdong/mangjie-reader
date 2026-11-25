package com.mangjie.reader.input;

/**
 * 输入框里各类值的解析与校验：时间、月日、百分比、分钟数、文件名。
 * 全部是纯函数，失败返回 null 或 -1，由界面决定怎么播报。
 */
public final class InputCheck {

    private InputCheck() {}

    /** "6:30" / "6：30" → {6,30}；不合法返回 null */
    public static int[] clock(String s) {
        if (s == null) return null;
        String t = s.replace("：", ":").trim();
        String[] hm = t.split(":");
        if (hm.length < 2) return null;
        int h, m;
        try {
            h = Integer.parseInt(hm[0].trim());
            m = Integer.parseInt(hm[1].trim());
        } catch (Exception e) {
            return null;
        }
        if (h < 0 || h > 23 || m < 0 || m > 59) return null;
        return new int[]{h, m};
    }

    /** "12-25" / "12/25" → {12,25}；不合法返回 null */
    public static int[] monthDay(String s) {
        if (s == null) return null;
        String[] md = s.replace("/", "-").replace("．", "-").trim().split("-");
        if (md.length < 2) return null;
        int mm, dd;
        try {
            mm = Integer.parseInt(md[0].trim());
            dd = Integer.parseInt(md[1].trim());
        } catch (Exception e) {
            return null;
        }
        if (mm < 1 || mm > 12 || dd < 1 || dd > 31) return null;
        return new int[]{mm, dd};
    }

    /** "50%" / "50" → 50；非数字或超出 0–100 返回 -1 */
    public static int percent(String s) {
        if (s == null) return -1;
        try {
            int v = Integer.parseInt(s.replace("%", "").trim());
            if (v < 0 || v > 100) return -1;
            return v;
        } catch (Exception e) {
            return -1;
        }
    }

    /** "30分钟" → 30；非数字或超出 1–600 返回 -1 */
    public static int minutes(String s) {
        if (s == null) return -1;
        try {
            int v = Integer.parseInt(s.replace("分钟", "").trim());
            if (v <= 0 || v > 600) return -1;
            return v;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 中文逗号统一成英文逗号 */
    public static String commas(String s) {
        return s == null ? "" : s.replace("，", ",");
    }

    /** 文件名里不能带路径分隔符 */
    public static boolean validName(String s) {
        return s != null && !s.isEmpty() && !s.contains("/");
    }
}
