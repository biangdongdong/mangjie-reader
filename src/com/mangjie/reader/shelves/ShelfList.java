package com.mangjie.reader.shelves;

import android.content.SharedPreferences;

import com.mangjie.reader.reader.BookProgress;

import java.text.Collator;
import java.util.Locale;

/**
 * 书架行与排序：只管文字和比较，不碰 View。
 * 行文字只给书名，不再带读到第几句（1.1.44 起）。
 */
public final class ShelfList {

    public static final int SORT_RECENT = 0;
    public static final int SORT_NAME = 1;
    public static final String[] SORT_NAMES = {"最近阅读", "书名"};

    private ShelfList() {}

    public static String sortName(int i) {
        int n = Math.max(0, Math.min(i, SORT_NAMES.length - 1));
        return SORT_NAMES[n];
    }

    public static int nextSort(int i) {
        return (i + 1) % SORT_NAMES.length;
    }

    /**
     * 书行：书名 + 读到百分之几。
     * 1.1.44 起不再缀「· 第 N 句」——那句数字读出来没意义，留百分比够用；
     * 没有总句数可算时就只给书名。
     */
    public static String line(String name, String path, SharedPreferences p) {
        if (name == null) return "";
        if (p == null || path == null || path.isEmpty()) return name;
        int idx = p.getInt(BookProgress.posKey(path), -1);
        int total = p.getInt(BookProgress.totalKey(path), 0);
        if (idx > 0 && total > 0) {
            return name + " · " + Math.round((idx + 1) * 100f / total) + "%";
        }
        return name;
    }

    /** 按书名比较：忽略「上次读：」前缀，中文按拼音 */
    public static int compareName(String a, String b) {
        Collator c = Collator.getInstance(Locale.CHINA);
        return c.compare(strip(a), strip(b));
    }

    public static String strip(String s) {
        if (s == null) return "";
        String t = s;
        if (t.startsWith("上次读：")) t = t.substring(4);
        // 老格式「· 第 N 句 · 43%」整段从「· 第」切掉
        int dot = t.indexOf(" · 第");
        if (dot > 0) t = t.substring(0, dot);
        // 新格式只带百分比，切掉行尾的「· 43%」，别让书名带上它去排序、去导出
        t = t.replaceFirst(" · \\d+%$", "");
        if (t.endsWith("　最近读")) t = t.substring(0, t.length() - 4);
        return t;
    }
}
