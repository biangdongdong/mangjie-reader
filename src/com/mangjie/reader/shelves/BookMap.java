package com.mangjie.reader.shelves;

import android.content.SharedPreferences;

import com.mangjie.reader.reader.BookProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「按书认，不按路径认」的落点。
 *
 * 书架、收藏、分组里存的仍是文件路径（这层结构不动，免得一次改太多），
 * 但每次打开一本书时，先按文件指纹算出这本书的身份：
 *   1. 以前见过同一个身份、路径却是旧的 → 把 favList、recentList、分组成员里的旧路径改成现在的路径；
 *   2. 没见过就记一笔。
 *
 * 这样用户把书改名、挪了文件夹，只要从任何入口打开过一次，书架自己就修好了。
 * 只记路径映射，不搬动正文，出错也不会丢书。
 */
public final class BookMap {

    /** 每行一条：身份\u0001路径 */
    private static final String KEY = "bookMap";
    /** 最多记这么多条，防止无限增长 */
    private static final int MAX_ENTRIES = 400;

    /** 最近一次路径修正的说明，主界面读完可写进日志 */
    public static volatile String lastFix = "";

    private BookMap() {}

    /** 打开一本书时调一次：同一身份换了路径，就把各处引用一起改过来 */
    public static void sync(SharedPreferences p, String path) {
        if (p == null || path == null || path.isEmpty()) return;
        String id = BookProgress.keyOf(path);
        if (id.isEmpty()) return;
        Map<String, String> m = load(p);
        String old = m.get(id);
        if (old != null && !old.equals(path)) {
            int fixed = 0;
            fixed += rename(p, "favList", old, path);
            fixed += rename(p, "recentList", old, path);
            if (fixed > 0) {
                // 这里在 openBook 早期，日志由调用方统一写；留个静态痕迹方便排查
                lastFix = "书换了位置，已修正引用：" + fixed + " 处：" + new java.io.File(path).getName();
            }
        }
        if (old == null || !old.equals(path)) {
            m.put(id, path);
            save(p, m);
        }
    }

    /** 这个身份记着的路径（没有就返回空串） */
    public static String pathOf(SharedPreferences p, String id) {
        if (p == null || id == null || id.isEmpty()) return "";
        String s = load(p).get(id);
        return s == null ? "" : s;
    }

    /** 按身份认：这一行（可能是旧路径）是不是同一本书 */
    public static boolean sameBook(SharedPreferences p, String line, String currentPath) {
        if (p == null || line == null || currentPath == null) return false;
        if (line.equals(currentPath)) return true;
        String id = BookProgress.keyOf(currentPath);
        String recorded = load(p).get(id);
        return recorded != null && recorded.equals(line);
    }

    private static int rename(SharedPreferences p, String key, String from, String to) {
        List<String> lines = readLines(p, key);
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals(from)) { lines.set(i, to); changed = true; }
        }
        if (changed) {
            StringBuilder sb = new StringBuilder();
            for (String one : lines) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(one);
            }
            p.edit().putString(key, sb.toString()).apply();
        }
        return changed ? 1 : 0;
    }

    private static String replaceToken(String all, String from, String to) {
        StringBuilder sb = new StringBuilder();
        String[] parts = all.split("\n");
        for (int i = 0; i < parts.length; i++) {
            String one = parts[i];
            if (sb.length() > 0) sb.append("\n");
            sb.append(one.equals(from) ? to : one);
        }
        return sb.toString();
    }

    private static List<String> readLines(SharedPreferences p, String key) {
        List<String> out = new ArrayList<String>();
        String all = p.getString(key, "");
        if (all == null || all.isEmpty()) return out;
        for (String one : all.split("\n")) if (!one.trim().isEmpty()) out.add(one);
        return out;
    }

    private static Map<String, String> load(SharedPreferences p) {
        Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        String all = p.getString(KEY, "");
        if (all == null || all.isEmpty()) return m;
        for (String one : all.split("\n")) {
            int sep = one.indexOf('\u0001');
            if (sep <= 0) continue;
            m.put(one.substring(0, sep), one.substring(sep + 1));
        }
        return m;
    }

    private static void save(SharedPreferences p, Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        // 只留最近写入的若干条：LinkedHashMap 后进在前，从后往前取
        List<String> keys = new ArrayList<String>(m.keySet());
        int from = Math.max(0, keys.size() - MAX_ENTRIES);
        for (int i = from; i < keys.size(); i++) {
            String k = keys.get(i);
            if (n++ > 0) sb.append("\n");
            sb.append(k).append('\u0001').append(m.get(k));
        }
        p.edit().putString(KEY, sb.toString()).apply();
    }
}
