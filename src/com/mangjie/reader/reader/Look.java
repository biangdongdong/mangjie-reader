package com.mangjie.reader.reader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地词典：读 盲界阅读/词典 下的 txt，一行一词条。
 * 分隔符认 Tab、竖线、冒号、或第一个空格，按常见词库格式都能吃。
 */
public final class Look {

    private static final Map<String, String> INDEX = new HashMap<String, String>();
    private static int loadedCount = 0;
    private static String loadedFrom = "";

    private Look() {}

    public static int count() { return loadedCount; }
    public static String from() { return loadedFrom; }

    /** 载入词典目录（可多次调用，目录变了会重新载入） */
    public static synchronized int load(File dir) {
        String key = dir == null ? "" : dir.getAbsolutePath();
        if (key.equals(loadedFrom) && loadedCount > 0) return loadedCount;
        INDEX.clear();
        loadedCount = 0;
        loadedFrom = key;
        if (dir == null || !dir.exists()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            String n = f.getName().toLowerCase();
            if (!(n.endsWith(".txt") || n.endsWith(".csv") || n.endsWith(".tsv"))) continue;
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(f),
                        java.nio.charset.Charset.forName("UTF-8")));
                String line;
                int guard = 0;
                while ((line = br.readLine()) != null) {
                    if (++guard > 2000000) break;
                    put(line);
                }
            } catch (Exception e) {
                // 编码不对就换 GB18030 再试一遍
                try {
                    if (br != null) br.close();
                } catch (Exception ignored) {}
                br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(new FileInputStream(f),
                            java.nio.charset.Charset.forName("GB18030")));
                    String line;
                    int guard = 0;
                    while ((line = br.readLine()) != null) {
                        if (++guard > 2000000) break;
                        put(line);
                    }
                } catch (Exception ignored2) {}
            } finally {
                try { if (br != null) br.close(); } catch (Exception ignored) {}
            }
        }
        return loadedCount;
    }

    private static void put(String line) {
        if (line == null) return;
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) return;
        String word = null, def = null;
        int tab = t.indexOf('\t');
        int bar = t.indexOf('|');
        int colon = t.indexOf('：');
        int comma = t.indexOf(',');
        int sp = t.indexOf(' ');
        if (tab > 0) { word = t.substring(0, tab); def = t.substring(tab + 1); }
        else if (bar > 0) { word = t.substring(0, bar); def = t.substring(bar + 1); }
        else if (colon > 0) { word = t.substring(0, colon); def = t.substring(colon + 1); }
        else if (comma > 0) { word = t.substring(0, comma); def = t.substring(comma + 1); }
        else if (sp > 0) { word = t.substring(0, sp); def = t.substring(sp + 1); }
        if (word == null) return;
        word = word.trim();
        def = def == null ? "" : def.trim();
        if (word.isEmpty() || def.isEmpty()) return;
        if (!INDEX.containsKey(word)) INDEX.put(word, def);
        loadedCount++;
    }

    /**
     * 查词：先完全相同，再「词是查询的前缀」、再「查询是词的前缀」，最多 5 条。
     * 返回 「词　释义」 形式的行。
     */
    public static List<String> lookup(String word) {
        List<String> out = new ArrayList<String>();
        String q = word == null ? "" : word.trim();
        if (q.isEmpty()) return out;
        String exact = INDEX.get(q);
        if (exact != null) out.add(q + "　" + exact);
        if (out.size() >= 5) return out;
        StringBuilder tail = new StringBuilder();
        for (Map.Entry<String, String> e : INDEX.entrySet()) {
            String w = e.getKey();
            if (w.equals(q)) continue;
            if (w.startsWith(q) || q.startsWith(w)) {
                String line = w + "　" + e.getValue();
                if (line.length() > 200) line = line.substring(0, 200) + "…";
                out.add(line);
                if (out.size() >= 5) break;
            }
        }
        return out;
    }

    /** 有多少条以这个字开头的词，给「查不到」时提示用 */
    public static int startsWithCount(String word) {
        if (word == null || word.trim().isEmpty()) return 0;
        int n = 0;
        for (String w : INDEX.keySet()) if (w.startsWith(word.trim())) n++;
        return n;
    }
}
