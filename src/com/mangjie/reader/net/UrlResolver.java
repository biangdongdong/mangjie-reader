/*
 * 本文件的部分实现参照「开源阅读 Legado」（https://github.com/LegadoTeam/legado）。
 * Legado：Copyright (C) 2019-2023 gedoor，GNU General Public License v3.0。
 * 本工程对其的使用方式与修改说明见仓库根目录 NOTICE，完整条款见 LICENSE。
 */
package com.mangjie.reader.net;

import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL 模板解析器：逐条对齐 Legado 的 AnalyzeUrl.initUrl()。
 *
 * 三步顺序不能换（源码注释写明）：
 *   1) analyzeJs()        —— 处理 URL 里的 <js></js> 与 @js: 片段
 *   2) replaceKeyPageJs() —— 先 {{...}} 内嵌表达式，再 <a,b,c> 分页模板
 *                          （先处理 {{}} 是为了避免表达式里出现大于小于号时被 <...> 切错）
 *   3) analyzeUrl()       —— 按首个 ",{" 切出 UrlOption，补全相对地址，编码 query / form
 *
 * 自带一个极简 JSON 扫描器，不依赖 org.json：
 * 一是 Android 上的 org.json 在不同版本宽松度不一致，二是这样能脱离设备做离线用例验证。
 *
 * 与 Legado 的差别（Android 上做不到、或这版先不做的）：
 *   · webView / webJs / dnsIp 只解析出来、不执行，留给调用方决定；
 *   · 代理键只剥离，不建代理客户端；
 *   · cookie 罐由调用方按源决定。
 */
public final class UrlResolver {

    /** @js: 与 <js></js>：注意 @js: 那支是贪婪的，和 Legado 一致 */
    private static final Pattern JS_PATTERN =
            Pattern.compile("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", Pattern.CASE_INSENSITIVE);
    /** 逗号紧跟左花括号：URL 与 options 的分界 */
    private static final Pattern PARAM_PATTERN = Pattern.compile("\\s*,\\s*(?=\\{)");
    /** <a,b,c> 分页模板 */
    private static final Pattern PAGE_PATTERN = Pattern.compile("<(.*?)>");

    /** 借宿主的 Rhino 求值：result 是上一段 JS 的结果，对应 Legado 的 @result */
    public interface Js { String eval(String code, String result); }

    /** 解析结果 */
    public static final class Out {
        /** 补全后的完整地址（GET 时 query 已编码） */
        public String url = "";
        /** 去掉 ? 之后的部分，请求时和 encodedQuery 拼起来 */
        public String urlNoQuery = "";
        /** 已编码的 query，不含前导 ? */
        public String encodedQuery = "";
        /** POST 表单：已编码的 a=b&c=d */
        public String encodedForm = "";
        /** POST 原始 body（JSON / XML / 带 Content-Type 的那种） */
        public String body = "";
        public String method = "GET";
        public String charset = "";
        public String type = "";
        /** options 里的 headers，原样 JSON 文本，空表示没有 */
        public String headers = "";
        /** options 里 headers 摊平后的键值，请求层直接用这个 */
        public final Map<String, String> headerMap = new LinkedHashMap<>();
        /** options 里的 js：URL 拼好后执行，结果覆盖 url */
        public String js = "";
        public String webJs = "";
        public String bodyJs = "";
        public String dnsIp = "";
        public int retry = 0;
        public boolean webView = false;
        /** 有没有切到 options、有没有跑过 js，给日志用 */
        public boolean hasOptions = false;
        public boolean jsRan = false;
        /** 解析过程中值得记一笔的事，调用方打日志用 */
        public final List<String> notes = new ArrayList<>();
    }

    private UrlResolver() {}

    /**
     * 解析一条 URL 规则。
     *
     * @param ruleUrl 源里写的原始地址，可能带 {{}}、@js、<1,2,3>、,{...}
     * @param baseUrl 源地址（bookSourceUrl / sourceUrl）
     * @param key     搜索词（原始，未编码），没有就传空
     * @param page    页码，从 1 开始
     */
    public static Out resolve(String ruleUrl, String baseUrl, String key, int page, Js js) {
        Out out = new Out();
        String u = ruleUrl == null ? "" : ruleUrl.trim();
        if (u.isEmpty()) return out;

        // 源地址自身也可能带 ,{...}，先切掉（Legado 在构造里就做了）
        String base = baseUrl == null ? "" : baseUrl.trim();
        Matcher bm = PARAM_PATTERN.matcher(base);
        if (bm.find()) base = base.substring(0, bm.start());

        // 1) @js / <js></js>
        if (JS_PATTERN.matcher(u).find()) u = analyzeJs(u, js, out);

        // 2) {{...}} 再 <a,b,c>
        if (u.contains("{{") && u.contains("}}")) u = replaceBraces(u, base, key, page, js, out);
        if (u.contains("<") && u.contains(">")) u = replacePage(u, page);

        // 3) 切 options、补全地址、编码
        String urlNoOption = u;
        String optStr = "";
        Matcher pm = PARAM_PATTERN.matcher(u);
        if (pm.find()) {
            urlNoOption = u.substring(0, pm.start());
            optStr = u.substring(pm.end());
        }
        String abs = getAbsoluteUrl(base, urlNoOption);

        if (!optStr.isEmpty()) {
            out.hasOptions = true;
            abs = applyOptions(abs, optStr, js, out);
        }
        out.url = abs;

        if ("POST".equals(out.method)) {
            out.urlNoQuery = out.url;
            String body = out.body;
            if (!body.isEmpty() && !looksJson(body) && !looksXml(body)
                    && !hasHeaderKey(out.headerMap, "Content-Type")) {
                out.encodedForm = encodeParams(body, out.charset, false);
            }
        } else {
            int q = out.url.indexOf('?');
            if (q >= 0) {
                out.urlNoQuery = out.url.substring(0, q);
                out.encodedQuery = encodeParams(out.url.substring(q + 1), out.charset, true);
                out.url = out.urlNoQuery + "?" + out.encodedQuery;
            } else {
                out.urlNoQuery = out.url;
            }
        }
        return out;
    }

    // ---------- 第一步：@js / <js></js> ----------

    private static String analyzeJs(String ruleUrl, Js js, Out out) {
        if (js == null) return ruleUrl;
        out.jsRan = true;
        Matcher m = JS_PATTERN.matcher(ruleUrl);
        int start = 0;
        String result = ruleUrl;
        while (m.find()) {
            if (m.start() > start) {
                String between = ruleUrl.substring(start, m.start()).trim();
                if (!between.isEmpty()) result = between.replace("@result", result);
            }
            String code = m.group(2) != null ? m.group(2) : m.group(1);
            String v = js.eval(code, result);
            result = v == null ? "" : v;
            start = m.end();
        }
        if (ruleUrl.length() > start) {
            String rest = ruleUrl.substring(start).trim();
            if (!rest.isEmpty()) result = rest.replace("@result", result);
        }
        return result;
    }

    // ---------- 第二步：{{...}} 与 <a,b,c> ----------

    /**
     * 展开 {{...}}。先认三个最常见的变量（省一次 Rhino 启动），其余交给 JS。
     * 取 }} 的方式和 Legado 一样：取最近的一对，不数花括号深度。
     */
    private static String replaceBraces(String text, String base, String key, int page, Js js, Out out) {
        StringBuilder sb = new StringBuilder(text.length() + 32);
        int i = 0;
        while (true) {
            int st = text.indexOf("{{", i);
            if (st < 0) break;
            int en = text.indexOf("}}", st + 2);
            if (en < 0) break;
            sb.append(text, i, st);
            String expr = text.substring(st + 2, en).trim();
            String val;
            if ("key".equals(expr) || "searchKey".equals(expr)) val = key == null ? "" : key;
            else if ("page".equals(expr)) val = String.valueOf(page);
            else if ("baseUrl".equals(expr)) val = base == null ? "" : base;
            else {
                val = js == null ? "" : js.eval(expr, "");
                out.jsRan = true;
            }
            if (val == null) val = "";
            sb.append(val);
            i = en + 2;
        }
        if (i == 0) return text;
        sb.append(text.substring(i));
        return sb.toString();
    }

    /**
     * <a,b,c>：按页码取第 N 项，page 从 1 开始，超出长度时取最后一项（Legado 注释里点明了）。
     */
    private static String replacePage(String text, int page) {
        String r = text;
        int guard = 0;
        int p = page <= 0 ? 1 : page;
        while (true) {
            Matcher m = PAGE_PATTERN.matcher(r);
            if (!m.find()) break;
            if (++guard > 20) break;
            String inside = m.group(1);
            if (!inside.contains(",")) break;          // 不含逗号的不是分页模板，别再切
            String[] pages = inside.split(",");
            String pick = (p < pages.length) ? pages[p - 1].trim() : pages[pages.length - 1].trim();
            r = r.substring(0, m.start()) + pick + r.substring(m.end());
        }
        return r;
    }

    // ---------- 第三步：options ----------

    private static String applyOptions(String abs, String optStr, Js js, Out out) {
        Object parsed = parseJson(optStr);
        if (!(parsed instanceof Map)) {
            out.notes.add("链接参数 JSON 解析失败，已忽略：" + brief(optStr));
            return abs;
        }
        Map<?, ?> o = (Map<?, ?>) parsed;

        String m = optStr(o, "method");
        if (!m.isEmpty()) {
            String up = m.toUpperCase();
            out.method = ("POST".equals(up) || "HEAD".equals(up)) ? up : "GET";
        }
        out.charset = optStr(o, "charset");
        out.type = optStr(o, "type");
        Object h = o.get("headers");
        if (h instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) h).entrySet()) {
                String k = strOf(e.getKey());
                String v = strOf(e.getValue());
                if ("proxy".equalsIgnoreCase(k)) {
                    out.notes.add("源里配了 proxy，这版不用代理，已跳过");
                    continue;
                }
                out.headerMap.put(k, v);
            }
            out.headers = toJsonMap(out.headerMap);
        } else if (h != null) {
            out.headers = strOf(h);
        }
        Object b = o.get("body");
        if (b != null) out.body = b instanceof String ? (String) b : strOf(b);
        out.retry = toInt(o.get("retry"), 0);
        out.webJs = optStr(o, "webJs");
        out.bodyJs = optStr(o, "bodyJs");
        out.dnsIp = optStr(o, "dnsIp");
        out.webView = truthy(o.get("webView"));
        String jsStr = optStr(o, "js");
        out.js = jsStr;
        if (!jsStr.isEmpty() && js != null) {
            String v = js.eval(jsStr, abs);
            if (v != null && !v.trim().isEmpty()) abs = v.trim();
            out.jsRan = true;
        }
        return abs;
    }

    private static String optStr(Map<?, ?> o, String k) {
        return strOf(o.get(k));
    }

    /** JSON 里的数字是 Double，转字符串时整数要去掉 ".0"（headers 里 cookie:0 这种很常见） */
    static String strOf(Object v) {
        if (v == null) return "";
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(v);
    }

    private static int toInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return (int) Double.parseDouble(String.valueOf(v).trim()); } catch (Throwable t) { return def; }
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        String s = String.valueOf(v).trim();
        return !s.isEmpty() && !"false".equalsIgnoreCase(s) && !"0".equals(s);
    }

    private static boolean hasHeaderKey(Map<String, String> map, String name) {
        for (String k : map.keySet()) if (k.equalsIgnoreCase(name)) return true;
        return false;
    }

    private static boolean looksJson(String s) {
        String t = s.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    private static boolean looksXml(String s) {
        String t = s.trim();
        return t.startsWith("<?xml")
                || (t.startsWith("<") && t.endsWith(">") && !t.startsWith("</") && t.indexOf('>') > 1);
    }

    private static String toJsonMap(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------- 极简 JSON 扫描器 ----------

    /** 只有 parseJson 一个入口：失败返回 null，不抛异常 */
    static Object parseJson(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            P p = new P(t);
            Object v = p.value();
            p.ws();
            return p.i >= t.length() ? v : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static final class P {
        final String s;
        int i;
        P(String s) { this.s = s; }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"' || c == '\'') return string(c);
            if (c == 't' && s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (c == 'f' && s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (c == 'n' && s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            i++;                                   // {
            Map<String, Object> m = new HashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k;
                char c = s.charAt(i);
                if (c == '"' || c == '\'') k = string(c);
                else k = bare();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new IllegalArgumentException();
                i++;
                Object v = value();
                m.put(k, v);
                ws();
                if (i >= s.length()) throw new IllegalArgumentException();
                if (s.charAt(i) == '}') { i++; return m; }
                if (s.charAt(i) == ',') {
                    i++;
                    ws();
                    // 容许尾逗号：源里手写的参数经常留着
                    if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
                    continue;
                }
                throw new IllegalArgumentException();
            }
        }

        List<Object> array() {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException();
                if (s.charAt(i) == ']') { i++; return l; }
                if (s.charAt(i) == ',') {
                    i++;
                    ws();
                    if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
                    continue;
                }
                throw new IllegalArgumentException();
            }
        }

        String string(char q) {
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == q) return sb.toString();
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    if (e == 'n') sb.append('\n');
                    else if (e == 't') sb.append('\t');
                    else if (e == 'r') sb.append('\r');
                    else if (e == 'u') {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    } else sb.append(e);
                } else sb.append(c);
            }
            throw new IllegalArgumentException();
        }

        String bare() {
            int st = i;
            while (i < s.length() && "\":,}] \t\r\n".indexOf(s.charAt(i)) < 0) i++;
            if (i == st) throw new IllegalArgumentException();
            return s.substring(st, i);
        }

        Double number() {
            int st = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            if (i == st) throw new IllegalArgumentException();
            return Double.valueOf(s.substring(st, i));
        }
    }

    // ---------- 地址补全 ----------

    /** 对齐 NetworkUtils.getAbsoluteURL：能走 URL 解析就走，走不了退回手工拼 */
    public static String getAbsoluteUrl(String baseUrl, String relative) {
        String rel = relative == null ? "" : relative.trim();
        if (rel.isEmpty()) return rel;
        if (baseUrl == null || baseUrl.trim().isEmpty()) return rel;
        String lower = rel.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) return rel;
        if (lower.startsWith("data:")) return rel;
        if (lower.startsWith("javascript")) return "";
        try {
            String b = baseUrl.trim();
            int cut = b.indexOf(',');
            if (cut >= 0) b = b.substring(0, cut);
            return new URL(new URL(b), rel).toString();
        } catch (Throwable e) {
            if (rel.startsWith("//")) {
                int c = baseUrl.indexOf(':');
                return c > 0 ? baseUrl.substring(0, c) + ":" + rel : rel;
            }
            if (rel.startsWith("/")) return baseUrl + rel;
            String b = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
            return b + rel;
        }
    }

    /** 对齐 NetworkUtils.getBaseUrl：scheme + host，不含路径 */
    public static String getBaseUrl(String url) {
        if (url == null) return "";
        String t = url.trim();
        String lower = t.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return "";
        int idx = t.indexOf('/', 9);
        return idx < 0 ? t : t.substring(0, idx);
    }

    // ---------- 编解码 ----------

    /**
     * 对齐 AnalyzeUrl.encodeParams：
     *   charset 空      → 先看整条是否已编码，未编码按 UTF-8 编
     *   charset "escape"→ 走自己的 escape，不按字符集编
     *   其它            → 按该字符集编
     * isQuery=true 时，整条 query 判定已编码就原样返回（Legado 的分支顺序如此）。
     */
    public static String encodeParams(String params, String charset, boolean isQuery) {
        String p = params == null ? "" : params;
        boolean checkEncoded = charset == null || charset.trim().isEmpty();
        String cs;
        if (checkEncoded) cs = "utf-8";
        else if ("escape".equalsIgnoreCase(charset.trim())) cs = null;
        else cs = charset.trim();

        if (isQuery && cs != null && encodedQuery(p)) return p;

        StringBuilder sb = new StringBuilder(p.length() + 16);
        int len = p.length();
        int pos = 0;
        while (pos <= len) {
            if (sb.length() > 0) sb.append('&');
            int amp = p.indexOf('&', pos);
            if (amp < 0) amp = len;
            int eq = p.indexOf('=', pos);
            String k;
            String v = null;
            if (eq < 0 || eq > amp) {
                k = p.substring(pos, amp);
            } else {
                k = p.substring(pos, eq);
                v = p.substring(eq + 1, amp);
            }
            appendEncoded(sb, k, checkEncoded, cs, isQuery);
            if (v != null) {
                sb.append('=');
                appendEncoded(sb, v, checkEncoded, cs, isQuery);
            }
            if (amp >= len) break;
            pos = amp + 1;
        }
        return sb.toString();
    }

    private static void appendEncoded(StringBuilder sb, String value, boolean checkEncoded,
                                      String charset, boolean isQuery) {
        String v = value == null ? "" : value;
        if (checkEncoded && (isQuery ? encodedQuery(v) : encodedForm(v))) {
            sb.append(v);
            return;
        }
        if (charset == null) {
            sb.append(escape(v));
            return;
        }
        try {
            sb.append(URLEncoder.encode(v, charset));
        } catch (Throwable e) {
            sb.append(v);
        }
    }

    /** 对齐 NetworkUtils.encodedQuery：保留集比 form 宽 */
    public static boolean encodedQuery(String s) {
        if (s == null) return true;
        String keep = "!$&()*+,-./:;=?@[]^_`{|}~";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isAlnum(c) || keep.indexOf(c) >= 0) continue;
            if (c == '%' && i + 2 < s.length()
                    && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                i += 2;
                continue;
            }
            return false;
        }
        return true;
    }

    /** 对齐 NetworkUtils.encodedForm：保留集窄得多 */
    public static boolean encodedForm(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isAlnum(c) || c == '*' || c == '-' || c == '.' || c == '_') continue;
            if (c == '%' && i + 2 < s.length()
                    && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                i += 2;
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean isAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** charset="escape" 时的编码：不用字符集，只按字节转义 */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isAlnum(c) || "-_.!~*'()".indexOf(c) >= 0) sb.append(c);
            else if (c == ' ') sb.append('+');
            else sb.append('%').append(String.format("%02X", (int) c & 0xFF));
        }
        return sb.toString();
    }

    private static String brief(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }
}
