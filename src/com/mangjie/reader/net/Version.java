package com.mangjie.reader.net;

/** 版本号比较与安装包候选路径 */
public final class Version {

    private Version() {}

    public static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * a>b 返回 1，相等 0，a<b 返回 -1；段数不同按 0 补。
     * 支持测试版后缀：0.0.338-beta1。数字相同的时候，正式版 > rc > beta > alpha；
     * 同一种后缀按末尾编号比，beta2 > beta1。
     */
    public static int compare(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] x = numbersOf(a), y = numbersOf(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xa = i < x.length ? parseIntSafe(x[i]) : 0;
            int yb = i < y.length ? parseIntSafe(y[i]) : 0;
            if (xa != yb) return xa > yb ? 1 : -1;
        }
        String sa = suffixOf(a), sb = suffixOf(b);
        if (sa.isEmpty() && sb.isEmpty()) return 0;
        if (sa.isEmpty()) return 1;
        if (sb.isEmpty()) return -1;
        int ra = tagRank(sa), rb = tagRank(sb);
        if (ra != rb) return ra > rb ? 1 : -1;
        int na = tagNumber(sa), nb = tagNumber(sb);
        if (na == nb) return 0;
        return na > nb ? 1 : -1;
    }

    /** 去掉 -beta1 这类后缀，再按点拆数字段 */
    private static String[] numbersOf(String v) {
        int dash = v.indexOf('-');
        String core = dash < 0 ? v : v.substring(0, dash);
        core = core.trim();
        if (core.isEmpty()) core = "0";
        return core.split("\\.");
    }

    /** 取 - 之后的后缀，小写；没有后缀返回空串 */
    public static String suffixOf(String v) {
        int dash = v == null ? -1 : v.indexOf('-');
        return dash < 0 ? "" : v.substring(dash + 1).trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static int tagRank(String tag) {
        if (tag.startsWith("alpha")) return 0;
        if (tag.startsWith("beta")) return 1;
        if (tag.startsWith("rc")) return 2;
        return 3;
    }

    private static int tagNumber(String tag) {
        int i = tag.length();
        while (i > 0 && Character.isDigit(tag.charAt(i - 1))) i--;
        return i == tag.length() ? 0 : parseIntSafe(tag.substring(i));
    }

    /** 形如 1、1.2、1.0.258、0.0.338-beta1 */
    public static boolean looksLikeVersion(String s) {
        return s != null && s.matches("[0-9]+(\\.[0-9]+)*(?:-[A-Za-z]+[0-9]*)?");
    }

    /** 下载安装包的候选路径：先试传进来的，再退回固定名与中文名 */
    public static String[] apkCandidates(String href, String dir, String ver) {
        return new String[]{href, dir + "update.apk",
                dir + encode("盲界阅读-demo-" + ver + ".apk")};
    }

    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
