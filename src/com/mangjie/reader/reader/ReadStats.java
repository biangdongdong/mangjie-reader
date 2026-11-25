package com.mangjie.reader.reader;

import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 阅读时长统计：今日 / 累计 / 近七天 / 单本。
 * 只存分钟数，键都在设置里，跟着备份一起走。
 *
 *   stat.day    今天是什么日子（yyyy-MM-dd），跨天时用它判断归零
 *   stat.today  今天读了几分钟
 *   stat.total  累计读了几分钟
 *   stat.d7     近七天："2026-11-15:12|2026-11-14:35"
 *   stat.book.<书路径>  这本书读了几分钟
 */
public final class ReadStats {

    public static final String K_DAY = "stat.day";
    public static final String K_TODAY = "stat.today";
    public static final String K_TOTAL = "stat.total";
    public static final String K_D7 = "stat.d7";
    public static final String K_BOOK = "stat.book.";

    private ReadStats() {}

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    /** 跨天就把「今天」清零，别把昨天的分钟算到今天 */
    private static void rollDay(SharedPreferences p, String day) {
        String saved = p.getString(K_DAY, "");
        if (!day.equals(saved)) {
            p.edit().putString(K_DAY, day).putInt(K_TODAY, 0).apply();
        }
    }

    /** 累加一分钟；bookPath 可为 null（只记总计） */
    public static void addMinute(SharedPreferences p, String bookPath) {
        if (p == null) return;
        String day = today();
        rollDay(p, day);
        int today = p.getInt(K_TODAY, 0) + 1;
        int total = p.getInt(K_TOTAL, 0) + 1;
        SharedPreferences.Editor ed = p.edit().putInt(K_TODAY, today).putInt(K_TOTAL, total);
        if (bookPath != null && !bookPath.isEmpty()) {
            ed.putInt(K_BOOK + bookPath, p.getInt(K_BOOK + bookPath, 0) + 1);
        }
        ed.putString(K_D7, touchD7(p.getString(K_D7, ""), day, today));
        ed.apply();
    }

    /** 近七天里把今天那格改成 today，超出七条就丢掉最老的 */
    private static String touchD7(String cur, String day, int today) {
        List<String> rows = new ArrayList<String>();
        if (cur != null && !cur.isEmpty()) {
            for (String one : cur.split("\\|")) {
                if (one == null || one.trim().isEmpty()) continue;
                if (one.startsWith(day + ":")) continue;
                rows.add(one.trim());
            }
        }
        rows.add(day + ":" + today);
        while (rows.size() > 7) rows.remove(0);
        StringBuilder sb = new StringBuilder();
        for (String one : rows) { if (sb.length() > 0) sb.append('|'); sb.append(one); }
        return sb.toString();
    }

    public static int todayMin(SharedPreferences p) {
        if (p == null) return 0;
        String saved = p.getString(K_DAY, "");
        String day = today();
        if (!day.equals(saved)) return 0;
        return p.getInt(K_TODAY, 0);
    }

    public static int totalMin(SharedPreferences p) {
        return p == null ? 0 : p.getInt(K_TOTAL, 0);
    }

    public static int bookMin(SharedPreferences p, String path) {
        if (p == null || path == null || path.isEmpty()) return 0;
        return p.getInt(K_BOOK + path, 0);
    }

    /** 近七天给页面用的几行文字（新的在上） */
    public static List<String> d7Lines(SharedPreferences p) {
        List<String> out = new ArrayList<String>();
        String cur = p == null ? "" : p.getString(K_D7, "");
        if (cur == null || cur.isEmpty()) return out;
        String[] parts = cur.split("\\|");
        for (int i = parts.length - 1; i >= 0; i--) {
            String one = parts[i].trim();
            int c = one.indexOf(':');
            if (c < 0) continue;
            String day = one.substring(0, c);
            String min = one.substring(c + 1);
            if (day.equals(today())) out.add("今天  " + min + " 分钟");
            else out.add(day + "  " + min + " 分钟");
        }
        return out;
    }

    public static String hm(int minutes) {
        if (minutes <= 0) return "0 分钟";
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) return m + " 分钟";
        return h + " 小时" + (m > 0 ? " " + m + " 分钟" : "");
    }

    /** 清空统计（包括各本书的） */
    public static void clear(SharedPreferences p) {
        if (p == null) return;
        SharedPreferences.Editor ed = p.edit().putString(K_DAY, today())
                .putInt(K_TODAY, 0).putInt(K_TOTAL, 0).putString(K_D7, "");
        List<String> bookKeys = new ArrayList<String>();
        for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if (e.getKey().startsWith(K_BOOK)) bookKeys.add(e.getKey());
        }
        for (String k : bookKeys) ed.remove(k);
        ed.apply();
    }
}
