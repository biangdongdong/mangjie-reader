package com.mangjie.reader.net;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新建书源的两条懒人路：套一套通用兜底规则、或者抓一下这个站自己嗅探搜索入口。
 *
 * 通用兜底：规则用最粗的 CSS（列表里所有 li、链接里的文字当书名），能跑但八成不准，
 * 得靠「校验书源」跑一遍，再进详情页逐项改。这不是魔法，只是省掉从零敲规则那一步。
 *
 * 嗅探：先看首页有没有 a 搜书表单（form + input），拿到 action 和参数名就拼 searchUrl；
 * 没有就按几个常见路径猜（/search?keyword= 之类），真发一次带着关键词的请求，看回的内容里
 * 有没有这个词、长度够不够，第一个过得去就用它。
 */
public final class SourceSniffer {

    public interface Net {
        String get(String url);
        /** 带超时的请求，慢站别把线程拖死 */
        String getT(String url, int timeoutMs);
    }

    public static class Sniff {
        public String base = "";
        public String searchUrl = "";
        public String found = "";        // 怎么找到的：表单 / 猜路径
        public String note = "";
        public List<String> tried = new ArrayList<String>();

        public boolean ok() { return searchUrl != null && !searchUrl.isEmpty(); }

        public String text() {
            if (!ok()) return "没嗅探到搜索入口。" + (note.isEmpty() ? "" : note);
            return "搜索地址：" + searchUrl + "（" + found + "）";
        }
    }

    private SourceSniffer() {}

    public static Sniff sniff(String url, Net net) {
        Sniff s = new Sniff();
        s.base = normalize(url);
        if (s.base.isEmpty() || net == null) { s.note = "地址为空"; return s; }
        String html = null;
        try { html = net.getT(s.base, 15000); } catch (Throwable ignored) { }
        if (html == null || html.trim().isEmpty()) {
            s.note = "连这个站都没打开";
            return s;
        }

        // 1. 先看表单
        String[] fromForm = sniffForm(html, s.base);
        if (fromForm != null) {
            s.searchUrl = fromForm[0];
            s.found = fromForm[1];
            if (verify(s, net)) return s;
        }

        // 2. 猜常见路径
        String[] guesses = new String[]{
                "/search?keyword={{key}}",
                "/search?q={{key}}",
                "/search?wd={{key}}",
                "/search?key={{key}}",
                "/s?q={{key}}",
                "/search.php?keyword={{key}}",
                "/index.php?keyword={{key}}",
                "/search.html?keyword={{key}}"
        };
        for (String g : guesses) {
            String u = s.base + g;
            s.tried.add(u);
            s.searchUrl = u;
            s.found = "猜的路径 " + g;
            if (verify(s, net)) return s;
        }

        // 3. 都不行：把表单那条留着当参考，提示要手改
        if (fromForm != null) {
            s.searchUrl = fromForm[0];
            s.found = fromForm[1] + "（没能验证，可能要是 POST 或参数不对）";
            s.note = "这个站可能不吃 GET 搜索，规则要手改";
            return s;
        }
        s.searchUrl = "";
        s.note = "首页没找到搜书表单，常见路径也都没响应，只能手填";
        return s;
    }

    /** 真发一次带关键词的请求，回内容里得有这个词才算通 */
    private static boolean verify(Sniff s, Net net) {
        try {
            String u = s.searchUrl.replace("{{key}}", "我的");
            String html = net.getT(u, 15000);
            if (html == null) return false;
            String t = html.trim();
            if (t.length() < 300) return false;
            // 搜出来的页面至少会出现关键词，或者是站内的搜索结果框
            return t.contains("我的") || t.contains("search") || t.contains("搜索");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 返回 {搜索地址, 来源说明}，找不到表单返回 null */
    private static String[] sniffForm(String html, String base) {
        try {
            Pattern pForm = Pattern.compile("<form[^>]*>(.*?)</form>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher mForm = pForm.matcher(html);
            while (mForm.find()) {
                String tag = html.substring(mForm.start(), Math.min(html.length(), mForm.start() + 300));
                String action = attr(tag, "action");
                String method = attr(tag, "method");
                if (method != null && method.toLowerCase().contains("post")) continue;   // 只做 GET
                String body = mForm.group(1);
                String name = pickInputName(body);
                if (name == null || name.isEmpty()) continue;
                String act = action == null || action.trim().isEmpty() || "#".equals(action.trim())
                        ? base : absolutize(base, action.trim());
                String sep = act.contains("?") ? "&" : "?";
                return new String[]{act + sep + name + "={{key}}", "首页表单（参数 " + name + "）"};
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 表单里挑一个最像关键词的输入框：名字命中关键词，或者干脆第一个文本框 */
    private static String pickInputName(String formBody) {
        try {
            Pattern pIn = Pattern.compile("<input[^>]*>", Pattern.CASE_INSENSITIVE);
            Matcher m = pIn.matcher(formBody);
            String fallback = null;
            while (m.find()) {
                String tag = m.group();
                String type = attr(tag, "type");
                if (type != null && (type.equalsIgnoreCase("hidden")
                        || type.equalsIgnoreCase("submit")
                        || type.equalsIgnoreCase("button")
                        || type.equalsIgnoreCase("checkbox"))) continue;
                String name = attr(tag, "name");
                if (name == null || name.trim().isEmpty()) continue;
                String low = name.toLowerCase();
                if (low.contains("key") || low.contains("search") || low.contains("word")
                        || low.contains("wd") || low.contains("kw") || low.equals("q")) return name;
                if (fallback == null) fallback = name;
            }
            return fallback;
        } catch (Throwable ignored) { }
        return null;
    }

    private static String attr(String tag, String key) {
        try {
            Pattern p = Pattern.compile(key + "\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(tag);
            if (m.find()) return m.group(2);
            Pattern p2 = Pattern.compile(key + "\\s*=\\s*([^\\s>]+)", Pattern.CASE_INSENSITIVE);
            Matcher m2 = p2.matcher(tag);
            if (m2.find()) return m2.group(1);
        } catch (Throwable ignored) { }
        return null;
    }

    /** 补成绝对地址；只留协议+主机+路径起点，别把查询串带上 */
    public static String normalize(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        try {
            java.net.URL uu = new java.net.URL(u);
            String host = uu.getHost();
            String proto = uu.getProtocol();
            int port = uu.getPort();
            String path = uu.getPath();
            if (path == null || "/".equals(path) || path.isEmpty()) path = "";
            else {
                // 给了具体页面就当站点根，后面再拼
                int last = path.lastIndexOf('/');
                path = last > 0 ? path.substring(0, last + 1) : "/";
                if (!path.endsWith("/")) path = path + "/";
            }
            String b = proto + "://" + host + (port > 0 && port != 80 && port != 443 ? ":" + port : "") + path;
            if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            return b;
        } catch (Throwable t) {
            return u;
        }
    }

    private static String absolutize(String base, String u) {
        try {
            if (u.startsWith("http://") || u.startsWith("https://")) return u;
            if (u.startsWith("/")) {
                java.net.URL b = new java.net.URL(base);
                return b.getProtocol() + "://" + b.getHost()
                        + (b.getPort() > 0 ? ":" + b.getPort() : "") + u;
            }
            return base + "/" + u;
        } catch (Throwable t) {
            return u;
        }
    }

    /**
     * 通用兜底规则：粗到家的 CSS，能出东西就行，准不准靠校验和手改。
     * 字段都按源码格式写，规则引擎认 @css: 和 text/href 这些叶子记号。
     */
    public static JSONObject genericSource(String name, String baseUrl) {
        return genericSource(name, baseUrl, "");
    }

    public static JSONObject genericSource(String name, String baseUrl, String searchUrl) {
        JSONObject o = new JSONObject();
        try {
            o.put("bookSourceName", name == null || name.trim().isEmpty() ? "自建源" : name.trim());
            o.put("bookSourceUrl", baseUrl == null ? "" : baseUrl.trim());
            o.put("bookSourceGroup", "自建");
            o.put("bookSourceType", 0);
            o.put("enabled", true);
            o.put("enabledExplore", true);
            o.put("customOrder", 0);
            o.put("searchUrl", searchUrl == null ? "" : searchUrl.trim());
            o.put("bookSourceComment", "通用兜底规则：列表用常见的 ul li，书名取链接文字，书链取 href。"
                    + "能跑但八成不准，先跑一次校验，再进详情页逐项改。");

            JSONObject rs = new JSONObject();
            rs.put("bookList", "@css:ul li");
            rs.put("name", "tag.a.0@text");
            rs.put("bookUrl", "tag.a.0@href");
            rs.put("author", "");
            o.put("ruleSearch", rs);

            JSONObject ri = new JSONObject();
            ri.put("name", "@css:h1");
            ri.put("author", "@css:.author");
            ri.put("intro", "@css:.intro");
            o.put("ruleBookInfo", ri);

            JSONObject rt = new JSONObject();
            rt.put("chapterList", "@css:ul li");
            rt.put("chapterName", "text");
            rt.put("chapterUrl", "tag.a.0@href");
            o.put("ruleToc", rt);

            JSONObject rc = new JSONObject();
            rc.put("content", "@css:.content");
            o.put("ruleContent", rc);
        } catch (Throwable ignored) { }
        return o;
    }
}
