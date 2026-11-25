package com.mangjie.reader.reader;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线词典：规则文件放 盲界阅读/词典规则/，一个词典一条规则（也可以是数组）。
 *
 * 规则长这样：
 * {
 *   "name": "汉典",
 *   "url":  "https://www.zdic.net/hans/{{key}}",
 *   "type": "html",              // html 或 json
 *   "css":  "div.entry",         // type=html 时用，取第一个命中的元素的文字
 *   "path": "result.explain",    // type=json 时用，点号路径，支持 a.b[0].c
 *   "times": 1                   // 可选，请求次数，默认 1
 * }
 *
 * 查词顺序：先在线（按文件名排序挨个试），都没结果再落回本地词库（Look）。
 * 请求由调用方给，模块自己不摸网络栈。
 */
public final class DictOnline {

    public interface Net {
        /** 取一个地址的文本，失败返回 null */
        String get(String url);
    }

    public static class Rule {
        public String name = "";
        public String url = "";
        public String type = "html";
        public String css = "";
        public String path = "";
        public File file;

        public String text() {
            return name + "（" + type + (type.equals("json") ? "，" + path : "，" + css) + "）";
        }
    }

    private static final Map<String, Rule> RULES = new LinkedHashMap<String, Rule>();
    private static String loadedFrom = "";
    private static int loadedCount = 0;

    private DictOnline() {}

    public static int count() { return loadedCount; }
    public static List<Rule> rules() { return new ArrayList<Rule>(RULES.values()); }

    /** 读规则目录；目录变了会重载 */
    public static synchronized int load(File dir) {
        String key = dir == null ? "" : dir.getAbsolutePath();
        if (key.equals(loadedFrom)) return loadedCount;
        RULES.clear();
        loadedCount = 0;
        loadedFrom = key;
        if (dir == null || !dir.exists()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            if (!f.getName().toLowerCase().endsWith(".json")) continue;
            String raw = readAll(f);
            if (raw == null) continue;
            try {
                String t = raw.trim();
                if (t.startsWith("[")) {
                    JSONArray arr = new JSONArray(t);
                    for (int i = 0; i < arr.length(); i++) add(arr.optJSONObject(i), f);
                } else {
                    add(new JSONObject(t), f);
                }
            } catch (Exception ignored) { }
        }
        return loadedCount;
    }

    private static void add(JSONObject o, File f) {
        if (o == null) return;
        Rule r = new Rule();
        r.name = o.optString("name", "").trim();
        r.url = o.optString("url", "").trim();
        if (r.name.isEmpty() || r.url.isEmpty()) return;
        r.type = o.optString("type", "html").trim().toLowerCase();
        r.css = o.optString("css", "").trim();
        r.path = o.optString("path", "").trim();
        r.file = f;
        String id = r.name + "@" + (f == null ? "" : f.getName());
        RULES.put(id, r);
        loadedCount = RULES.size();
    }

    /** 在线查词，查不到返回空串；命中的词典名放 hint[0] */
    public static String lookup(String word, Net net, String[] hint) {
        if (word == null || word.trim().isEmpty() || net == null) return "";
        for (Rule r : RULES.values()) {
            try {
                String u = r.url.replace("{{key}}", encode(word.trim()));
                String body = net.get(u);
                if (body == null || body.trim().isEmpty()) continue;
                String out = "json".equals(r.type) ? fromJson(body, r.path) : fromHtml(body, r.css);
                if (out != null && !out.trim().isEmpty()) {
                    if (hint != null && hint.length > 0) hint[0] = r.name;
                    return out.trim();
                }
            } catch (Throwable ignored) { }
        }
        return "";
    }

    /** type=html：取第一个命中元素的文字；css 空则取整页正文 */
    private static String fromHtml(String html, String css) {
        Document d = Jsoup.parse(html);
        if (css == null || css.trim().isEmpty()) {
            return d.body() == null ? "" : d.body().text();
        }
        java.util.List<Element> els = d.select(css);
        if (els == null || els.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Element e : els) {
            if (++n > 6) break;         // 一个词条撑死六段，别把整页都搬来
            String t = e.text();
            if (t == null || t.trim().isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(t.trim());
        }
        return b.toString();
    }

    /** type=json：点号路径，支持 a.b[0].c */
    private static String fromJson(String body, String path) {
        try {
            String t = body.trim();
            Object root = t.startsWith("[") ? (Object) new JSONArray(t) : (Object) new JSONObject(t);
            if (path == null || path.trim().isEmpty()) return String.valueOf(root);
            Object cur = root;
            for (String seg : path.split("\\.")) {
                if (cur == null) return "";
                // a[0] / a[1] 这种下标
                String rest = seg;
                if (rest.endsWith("]")) {
                    int lb = rest.lastIndexOf('[');
                    if (lb > 0) {
                        int idx = -1;
                        try { idx = Integer.parseInt(rest.substring(lb + 1, rest.length() - 1).trim()); }
                        catch (Exception ignored) { }
                        String field = rest.substring(0, lb);
                        if (!field.isEmpty()) {
                            cur = cur instanceof JSONObject ? ((JSONObject) cur).opt(field) : null;
                        }
                        if (cur instanceof JSONArray && idx >= 0) cur = ((JSONArray) cur).opt(idx);
                        else return "";
                        continue;
                    }
                }
                if (cur instanceof JSONObject) cur = ((JSONObject) cur).opt(rest);
                else if (cur instanceof JSONArray) {
                    cur = ((JSONArray) cur).opt(0);
                    if (cur instanceof JSONObject) cur = ((JSONObject) cur).opt(rest);
                    else return "";
                } else return "";
            }
            if (cur == null) return "";
            if (cur instanceof String) return (String) cur;
            return String.valueOf(cur);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readAll(File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f),
                    java.nio.charset.Charset.forName("UTF-8")));
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) b.append(line).append('\n');
            return b.toString();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) { }
        }
    }

    private static String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
