package com.mangjie.reader.reader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户自定义规则（目录规则 / 替换净化 共用一套存法）：
 * 一个 JSON 数组，元素 {name, rule|pattern, replacement, enable}。
 * 目录规则存 `目录规则/自定义.json`，净化规则存 `净化/自定义.json`。
 */
public final class UserRules {

    /** 一条用户规则 */
    public static final class Entry {
        public String name = "";
        public String pattern = "";
        public String replacement = "";
        public boolean enable = true;

        public Entry() {}

        public Entry(String name, String pattern, String replacement, boolean enable) {
            this.name = name;
            this.pattern = pattern;
            this.replacement = replacement;
            this.enable = enable;
        }
    }

    private UserRules() {}

    public static File fileIn(File dir) {
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "自定义.json");
    }

    /** patternKey：净化用 "pattern"，目录规则用 "rule" */
    public static List<Entry> load(File f, String patternKey) {
        List<Entry> out = new ArrayList<Entry>();
        if (f == null || !f.isFile()) return out;
        try {
            JSONArray arr = new JSONArray(readAll(f));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.name = o.optString("name", "规则" + (i + 1));
                e.pattern = o.optString(patternKey, o.optString("pattern", o.optString("rule", "")));
                e.replacement = o.optString("replacement", "");
                e.enable = o.optBoolean("enable", true);
                if (!e.pattern.isEmpty()) out.add(e);
            }
        } catch (Throwable ignored) {
            // 文件坏了当没有
        }
        return out;
    }

    public static boolean save(File f, List<Entry> list, String patternKey, boolean keepReplacement) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                o.put(patternKey, e.pattern);
                if (keepReplacement) o.put("replacement", e.replacement);
                o.put("enable", e.enable);
                arr.put(o);
            }
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(arr.toString(2));
            w.close();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String readAll(File f) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }
}
