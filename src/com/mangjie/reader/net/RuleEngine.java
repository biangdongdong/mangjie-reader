/*
 * 本文件的部分实现参照「开源阅读 Legado」（https://github.com/LegadoTeam/legado）。
 * Legado：Copyright (C) 2019-2023 gedoor，GNU General Public License v3.0。
 * 本工程对其的使用方式与修改说明见仓库根目录 NOTICE，完整条款见 LICENSE。
 */
package com.mangjie.reader.net;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 书源规则引擎：选择器串、JSONPath、XPath、@js: 片段、Jinja 模板、连接符（|| && %% ## -）。
 * 独立模块：自带规则变量表，只向宿主借「日志」和「JS 求值」两件事。
 */
public final class RuleEngine {

    /** 宿主能力：只要日志和 JS 求值 */
    public interface Host {
        void log(String tag, String msg);
        String evalJs(String expr, String data, String base);
    }

    static final class IdxSpec {
        String beforeRule = "";
        char split = '.';
        final List<Integer> indexDefault = new ArrayList<>();
        final List<Object> indexes = new ArrayList<>();
    }

    private final Host host;

    /** 标签名表：规则里写 @a、@li 是标签选择器，不能当成属性名取 */
    private static final java.util.Set<String> TAG_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "a", "li", "dd", "dt", "div", "span", "p", "ul", "ol", "tr", "td", "th", "table", "tbody",
            "thead", "tfoot", "h1", "h2", "h3", "h4", "h5", "h6", "img", "em", "b", "i", "strong",
            "body", "html", "head", "meta", "link", "script", "style", "br", "hr", "dl", "pre", "code",
            "section", "article", "main", "nav", "header", "footer", "form", "input", "button", "select",
            "option", "label", "iframe", "font", "small", "sub", "sup", "u", "s", "abbr", "blockquote",
            "caption", "frame", "center", "nobr"));

    /** 规则变量表：@put/@get 用的就是它 */
    private final Map<String, String> ruleVars = new HashMap<>();

    public RuleEngine(Host host) { this.host = host; }

    public String varGet(String k) {
        String v = ruleVars.get(k);
        return v == null ? "" : v;
    }

    public void varPut(String k, String v) { ruleVars.put(k, v == null ? "" : v); }

    /**
     * 对"纯文本"跑替换规则，对齐 Legado 的 replaceRegex（整章文本过一遍规则）：
     * 支持 `##正则##替换`、`##正则`（删掉）、`###` 只替第一个、以及 `@js:result.replace(...)`。
     */
    public String applyReplace(String rule, String text) {
        if (text == null) return "";
        String r = rule == null ? "" : rule.trim();
        if (r.isEmpty()) return text;
        // 整条是 JS：把正文当作 result 交给脚本
        if (r.startsWith("@js:") || r.startsWith("<js>")) {
            String out = runJsText(r, text);
            return out == null ? text : out;
        }
        int h = r.indexOf("##");
        if (h < 0) {
            // 只有选择器、没有替换 → 当无操作，别把正文弄没了
            return text;
        }
        String head = r.substring(0, h).trim();
        if (!head.isEmpty() && (head.startsWith("@js:") || head.contains("<js>"))) {
            String out = runJsText(head, text);
            if (out != null) text = out;
        }
        try {
            // 对齐 Legado AnalyzeRule.kt:762-772 的段数口径：
            // 规则##正则          → 删掉匹配（replacement 为空）
            // 规则##正则##替换     → 全部替换
            // 规则##正则##替换##   → 只替换第一次（第 4 段只当开关，内容丢弃）
            // 所以 `###` 结尾切出来也是 4 段（末段是 "#"），同样只替换第一次。
            String[] pp = r.substring(h).split("##", -1);
            if (pp.length < 2 || pp[1].isEmpty()) return text;
            String rep = pp.length > 2 ? pp[2] : "";
            boolean only = pp.length > 3;
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pp[1], java.util.regex.Pattern.MULTILINE);
            java.util.regex.Matcher m = p.matcher(text);
            text = only ? m.replaceFirst(rep) : m.replaceAll(rep);
        } catch (Exception e) {
            host.log("听书", "替换规则失败：" + e.getMessage());
        }
        return text;
    }

    /** 外面要用 js 规则（订阅源的分类表就是 <js> 算出来的） */
    public String runJsPublic(String rule, String text) {
        String r = rule == null ? "" : rule.trim();
        String t = text == null ? "" : text;
        // 整段就是一个 JS：剥掉外壳后必须直接交给 evalJs。
        // 以前剥完再交给 runJsText，而 runJsText 只认「<js>…</js> 或 @js:」这种带标签的写法，
        // 剥完的纯代码它一条步骤都切不出来，于是什么都不执行、返回空串——还一句日志都不打。
        if (r.toLowerCase().startsWith("@js:")) return host.evalJs(r.substring(4), t, "");
        if (r.toLowerCase().startsWith("<js>")) {
            int end = r.lastIndexOf('<');
            String code = r.substring(4, end > 4 ? end : r.length());
            return host.evalJs(code, t, "");
        }
        return runJsText(r, t);
    }

    private String runJsText(String rule, String text) {
        try {
            java.util.List<String[]> steps = splitJsSteps(rule);
            String cur = text;
            for (String[] st : steps) {
                if ("js".equals(st[0])) cur = host.evalJs(st[1], cur, "");
                else {
                    java.util.List<String> one = ruleAll(st[1], "", cur);
                    if (!one.isEmpty()) cur = one.get(0);
                }
                if (cur == null) cur = "";
            }
            return cur;
        } catch (Throwable t) {
            return null;
        }
    }

    public String pickElem(String rule, org.jsoup.nodes.Element e, String base) {
        if (rule == null || e == null) return null;
        String r = rule.trim();
        if (r.isEmpty()) return null;
        String tail = null;
        int h = hashHashOutsideBraces(r);   // {{$.x##.*_}} 里的 ## 是模板内部的，不能当替换尾切
        if (h > 0) { tail = r.substring(h); r = r.substring(0, h).trim(); }
        String v;
        if (isLeafToken(r) || "all".equals(r) || "ownText".equals(r)) v = valueOf(e, r);
        else {
            List<String> ls = ruleAll(r, base, e.outerHtml());
            v = ls.isEmpty() ? null : ls.get(0);
        }
        if (v != null && tail != null) v = applyReplace(tail, v);
        return v;
    }

    /**
     * 取一条规则字符串。注意不能只用 optString：书源里 exploreUrl 有时是 JSON 数组，
     * optString 会把数组读成空串，结果误判成「这个源没有发现规则」。
     */
    public String ruleStr(JSONObject o, String key) {
        if (o == null) return "";
        Object v = o.opt(key);
        if (v == null) return "";
        if (v instanceof String) return ((String) v).trim();
        if (v instanceof org.json.JSONArray || v instanceof org.json.JSONObject) return v.toString();
        String s = String.valueOf(v);
        return s == null ? "" : s.trim();
    }

    public JSONObject sub(JSONObject o, String key) {
        return o == null ? null : o.optJSONObject(key);
    }

    public String hostOf(String url) {
        try { return new java.net.URL(url).getHost(); } catch (Exception e) { return ""; }
    }

    public boolean isRuleToken(String t) {
        if (t == null) return false;
        String x = t.trim();
        return x.startsWith("@") || x.startsWith("$.") || x.startsWith("$[") || x.startsWith("//");
    }

    public String evalJsBlocks(String body, String base, String html) {
        String b = body;
        int guard = 0;
        while (guard++ < 20) {
            int st = b.indexOf("<js>");
            if (st < 0) break;
            int en = b.indexOf("</js>", st);
            if (en < 0) break;
            String code = b.substring(st + 4, en);
            String v = host.evalJs(code, html, base);
            b = b.substring(0, st) + v + b.substring(en + 5);
        }
        return b;
    }

    public String expandJinja(String body, String base, String html) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int guard = 0;
        while (guard++ < 200) {
            int st = body.indexOf("{{", i);
            if (st < 0) { sb.append(body.substring(i)); break; }
            sb.append(body.substring(i, st));
            // 跟 Legado 的 evalPattern 一致：{{ ... }} 取到最近的一对 }}，不数花括号深度
            // （模板里常写 if(){...}else{...}，数深度会把模板切碎，碎片当正文漏出来）
            int en = body.indexOf("}}", st + 2);
            if (en < 0) { sb.append(body.substring(st)); break; }
            String inner = body.substring(st + 2, en).trim();
            String val;
            if (isRuleToken(inner)) {
                List<String> v = ruleAll(inner, base, html);
                val = v.isEmpty() ? "" : v.get(0);
            } else {
                val = host.evalJs(inner, html, base);
            }
            sb.append(val == null ? "" : val);
            i = en + 2;
        }
        return sb.toString();
    }

    public String stripPut(String body) {
        if (body == null) return "";
        String b = body;
        int guard = 0;
        while (guard++ < 20) {
            int idx = b.indexOf("@put:");
            if (idx < 0) break;
            int brace = b.indexOf('{', idx);
            if (brace < 0) break;
            int depth = 0, end = -1;
            boolean inS = false, inD = false;
            for (int i = brace; i < b.length(); i++) {
                char c = b.charAt(i);
                if (c == '"' && !inS) inD = !inD;
                else if (c == '\'' && !inD) inS = !inS;
                if (inS || inD) continue;
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
            }
            if (end < 0) break;
            try {
                JSONObject o = new JSONObject(b.substring(brace, end));
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    ruleVars.put(k, o.optString(k, ""));
                }
            } catch (Exception ignored) { }
            b = b.substring(0, idx) + b.substring(end);
        }
        return b;
    }

    public String applyGet(String body) {
        if (body == null) return "";
        String b = body;
        int guard = 0;
        while (guard++ < 50) {
            int idx = b.indexOf("@get:");
            if (idx < 0) break;
            int st = idx + 5;
            String key;
            int endPos;
            // 对齐 Legado evalPattern 的 `@get:\{[^}]+?\}` 写法：@get:{key} 与 @get:key 等价
            if (st < b.length() && b.charAt(st) == '{') {
                int close = b.indexOf('}', st + 1);
                if (close < 0) break;
                key = b.substring(st + 1, close).trim();
                endPos = close + 1;
            } else {
                int en = st;
                while (en < b.length()) {
                    char c = b.charAt(en);
                    if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') en++;
                    else break;
                }
                key = b.substring(st, en);
                endPos = en;
            }
            String v = ruleVars.containsKey(key) ? ruleVars.get(key) : "";
            b = b.substring(0, idx) + v + b.substring(endPos);
        }
        return b;
    }

    public List<String> ruleAll(String rule, String base, String html) {
        if (rule == null || rule.trim().isEmpty() || html == null) return new ArrayList<>();
        String r = rule.trim();
        /*
         * 规则以单个 @ 开头（@onclick、@data-id 这类）：Legado 的语义是「对当前元素取该属性」。
         * 以前 splitTop("@") 会切出一个空段，属性名落到最后一段，取值对象却是 Jsoup.parse(html)
         * 解析出来的整篇 document，document 上没有这个属性，结果恒为空——
         * 阅友小说的 bookUrl 就是 @onclick@js:result.match(...)[1]，所以「搜索成功、书地址取空」。
         * 标签选择器（@a、@li 这种）不能当属性，用已知标签名挡住。
         */
        if (r.startsWith("@")) {
            String nm = r.substring(1).trim();
            if (!nm.isEmpty() && nm.indexOf('@') < 0 && nm.indexOf(':') < 0 && !TAG_NAMES.contains(nm.toLowerCase())) {
                org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html, base == null ? "" : base);
                org.jsoup.nodes.Element root = doc;
                if (doc.body() != null && doc.body().children().size() == 1) root = doc.body().child(0);
                String v = valueOf(root, nm);
                List<String> one = new ArrayList<>();
                if (v != null && !v.trim().isEmpty()) one.add(v.trim());
                return one;
            }
        }
        // 对齐 Legado 的 splitSourceRule：一条规则是由「选择器 / @js: / <js>」拼成的步骤链，
        // 上一步的结果就是下一步的 result。书源里 sel##替换@js:result.replace(...) 都这么写。
        java.util.List<String[]> steps = splitJsSteps(r);
        if (steps.size() > 1) {
            List<String> out = new ArrayList<>();
            String cur = html;
            for (String[] st : steps) {
                List<String> one;
                if ("js".equals(st[0])) one = java.util.Collections.singletonList(host.evalJs(st[1], cur, base));
                else one = ruleAll(st[1], base, cur);
                if (!one.isEmpty()) cur = one.get(0);
                else cur = "";
                if (cur == null) cur = "";
            }
            if (cur != null && !cur.isEmpty()) out.add(cur);
            return out;
        }
        return ruleWithConnectors(r, base, html);
    }

    /** 把规则切成步骤：{类型, 片段}，类型是 sel 或 js */
    private java.util.List<String[]> splitJsSteps(String rule) {
        java.util.List<String[]> list = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)");
        java.util.regex.Matcher m = p.matcher(rule);
        int start = 0;
        int count = 0;
        while (m.find()) {
            if (m.start() > start) {
                String seg = rule.substring(start, m.start()).trim();
                if (!seg.isEmpty()) list.add(new String[]{"sel", seg});
            }
            String code = m.group(2) != null ? m.group(2) : m.group(1);
            list.add(new String[]{"js", code == null ? "" : code});
            start = m.end();
            count++;
        }
        if (rule.length() > start) {
            String seg = rule.substring(start).trim();
            if (!seg.isEmpty()) list.add(new String[]{"sel", seg});
        }
        return count == 0 ? java.util.Collections.<String[]>emptyList() : list;
    }

    private static final java.util.regex.Pattern REGEX_GROUP = java.util.regex.Pattern.compile("\\$[0-9]{1,2}");

    /**
     * 对齐 Legado 的正则规则模式（AnalyzeByRegex）：规则文本里出现 $1…$99 时，
     * 整条规则不再当 CSS/XPath 选择器，而是按 && 拆成多段正则逐层过滤，
     * 最后一段返回 group(0..groupCount)。上游 getElement/getElements 走这条通路，
     * 用正则写目录或正文的源全靠它，缺了就是整段取空。
     */
    private boolean looksRegexChain(String body) {
        if (body == null) return false;
        String b = body.trim();
        if (b.isEmpty()) return false;
        if (b.startsWith("@") || b.startsWith("//") || b.startsWith("/")) return false;
        if (b.startsWith("$.") || b.startsWith("$[")) return false;
        if (b.contains("<js>") || b.contains("{{")) return false;
        // 选择器里不可能出现的几种形态：捕获组前后文、字符类取反、跨行通配、$n 组引用
        return REGEX_GROUP.matcher(b).find()
                || b.contains("[\\s\\S]") || b.contains("([^") || b.contains(".*?")
                || b.contains("</") || b.contains("[^>");
    }

    public List<String> regexChain(String rule, String html) {
        List<String> out = new ArrayList<>();
        if (rule == null || html == null) return out;
        List<String> regs = splitTop(rule.trim(), "&&");
        String cur = html;
        for (int i = 0; i < regs.size(); i++) {
            String reg = regs.get(i).trim();
            if (reg.isEmpty()) continue;
            java.util.regex.Pattern p;
            try {
                // 与上游一致：不传 DOTALL，要跨行就自己写 [\s\S]
                p = java.util.regex.Pattern.compile(reg);
            } catch (Exception e) {
                noteRuleFail(rule, e);
                return out;
            }
            java.util.regex.Matcher m = p.matcher(cur);
            if (i == regs.size() - 1) {
                while (m.find()) {
                    int gc = m.groupCount();
                    /*
                     * 跳过 group(0)（整段匹配）：写正则规则的人要的是捕获组。
                     * 上游 getElement 把 group0 放在下标 0、$1 取第一个捕获组，
                     * 我们这里不给下标体系，直接以 group1 起作为结果，等价于默认取 $1。
                     * 整条正则没有捕获组时才退回 group0。
                     */
                    if (gc == 0) {
                        String v = m.group();
                        out.add(v == null ? "" : v);
                    } else {
                        for (int g = 1; g <= gc; g++) {
                            String v = m.group(g);
                            out.add(v == null ? "" : v);
                        }
                    }
                }
                return out;
            }
            StringBuilder sb = new StringBuilder();
            while (m.find()) sb.append(m.group());
            if (sb.length() == 0) return out;   // 这一层没命中，整条链断
            cur = sb.toString();
        }
        return out;
    }

    public List<String> ruleSimple(String rule, String base, String html) {
        List<String> out = new ArrayList<>();
        String body = applyGet(stripPut(rule));
        // URL 不是选择器。对齐 Legado：地址走 AnalyzeUrl 独立通道，从不进 jsoup。
        // 以前整条 https://boxnovel…?bookid={$.bookId} 被当选择器，抛
        // 「Could not parse query … unexpected token at '{$.bookId}'」（日志 a11y-20261121-135720）。
        String head = body == null ? "" : body.trim();
        // 注意只认 http(s)：Legado 里 //div 是 XPath，当成地址会毁掉所有 XPath 规则
        if (head.startsWith("http://") || head.startsWith("https://")) {
            String u = urlOf(head, base, html);
            if (!u.isEmpty()) out.add(u);
            return out;
        }
        if ("all".equals(body)) {
            out.add(org.jsoup.Jsoup.parse(html == null ? "" : html).text());
            return out;
        }
        /*
         * $ 开头的规则要按内容判定走 JSON：模板 {{$..resourceID}} 求值时数据源就是一段 JSON 原文，
         * 以前这里直接进 jsoup，$..xxx 当选择器必然取空（精选源 tocUrl 里 15 条都是这种）。
         */
        if (head.startsWith("$") || head.startsWith("@json:") || head.startsWith("@JSON:")) {
            List<Object> j = jsonObjectsOf(html, head);
            if (!j.isEmpty() && j.get(0) != null) out.add(cleanJsonVal(String.valueOf(j.get(0))));
            return out;
        }
        boolean onlyOne = false;
        if (body.endsWith("###")) { onlyOne = true; body = body.substring(0, body.length() - 3); }
        // {{{ ... }}} 模板：整段是 {{规则}} 时先算内层
        if (body.startsWith("{{") && body.endsWith("}}") && body.length() > 4) {
            String inner = body.substring(2, body.length() - 2).trim();
            List<String> vals = ruleAll(inner, base, html);
            if (!vals.isEmpty()) {
                out.add(vals.get(0));
                return out;
            }
        }
        // 阅读2.0 的 {$._id} 写法等价于 $. 路径
        if (body.startsWith("{") && body.endsWith("}") && body.length() > 2) {
            String inner = body.substring(1, body.length() - 1).trim();
            if (inner.startsWith("$.") || inner.startsWith("$[")) return jsonPathAll(html, inner);
        }
        String replacePattern = null, replaceWith = null;
        int hash = body.indexOf("##");
        if (hash >= 0) {
            replacePattern = body.substring(hash + 2);
            body = body.substring(0, hash);
            int h2 = replacePattern.indexOf("##");
            if (h2 >= 0) { replaceWith = replacePattern.substring(h2 + 2); replacePattern = replacePattern.substring(0, h2); }
        }
        if (looksRegexChain(body)) {
            // 正则规则模式：$1 触发，与选择器互斥，命中就不进 jsoup
            out.addAll(regexChain(body, html));
        } else {
        try {
            // 对齐 Legado 的 JS_PATTERN：选择器后面跟 @js: 时，先按选择器取到结果，
            // 再把结果当 result 交给 JS（书源里 class.intro@js:result.replace(...) 都是这个写法）
            int jsAt = body.indexOf("@js:");
            if (jsAt > 0 && !body.contains("<js>")) {
                String sel = body.substring(0, jsAt).trim();
                String code = body.substring(jsAt + 4);
                List<String> pre = sel.isEmpty()
                        ? java.util.Collections.singletonList(html)
                        : ruleAll(sel, base, html);
                for (String pv : pre) {
                    String v = host.evalJs(code, pv, base);
                    if (v != null && !v.isEmpty()) out.add(v);
                }
                return out;
            }
            if (body.startsWith("@js:")) {
                String v = host.evalJs(body.substring(4), html, base);
                if (!v.isEmpty()) out.add(v);
            } else if (body.contains("<js>")) {
                out.add(evalJsBlocks(body, base, html));
            } else if (body.contains("{{")) {
                out.add(expandJinja(body, base, html));
            } else if (body.startsWith("$.") || body.startsWith("$[")) {
                out.addAll(jsonPathAll(html, body));
            } else if (body.startsWith("@@")) {
                out.addAll(ruleAll(body.substring(2).trim(), base, html));
            } else if (body.startsWith("//")) {
                // isRuleToken 认 //，之前却没人接手，落进 defaultRuleAll 被 jsoup 当非法选择器吞掉
                out.addAll(xpathAll(html, body, base));
            } else if (body.startsWith("/") && !body.startsWith("//")) {
                out.addAll(xpathAll(html, body, base));
            } else if (body.startsWith("@json:")) {
                out.addAll(jsonPathAll(html, body.substring(6)));
            } else if (body.startsWith("@XPath:") || body.startsWith("@xpath:")) {
                out.addAll(xpathAll(html, body.substring(7), base));
            } else if (body.startsWith("id.") || body.startsWith("class.") || body.startsWith("tag.")
                    || body.startsWith("[") || (body.contains("@") && !body.contains("#"))) {
                out.addAll(defaultRuleAll(body, base, html));
            } else {
                String sel = body;
                String attr = "text";
                if (sel.startsWith("@css:")) sel = sel.substring(5);
                int at = sel.lastIndexOf('@');
                if (at > 0) {
                    String suf = sel.substring(at + 1).trim();
                    /*
                     * 对齐 Legado AnalyzeByJSoup.getResultLast：规则最后一段不是 text/textNodes/ownText/html/all
                     * 时一律当属性名取（else -> element.attr(lastRule)），没有白名单。
                     * 我们原来只认 href/src/text/html/title，于是 meta[property="og:novel:book_name"]@content
                     * 这类取 meta 的写法全部取空 —— 精选源里 @content 出现 95 次。
                     */
                    if (isAttrSuffix(suf)) {
                        attr = suf;
                        sel = sel.substring(0, at);
                    }
                }
                org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html, base);
                org.jsoup.select.Elements els = doc.select(sel);
                for (org.jsoup.nodes.Element e : els) {
                    String v;
                    if ("href".equals(attr)) v = e.hasAttr("href") ? e.absUrl("href") : "";
                    else if ("src".equals(attr) || "data-src".equals(attr)) v = e.hasAttr(attr) ? e.absUrl(attr) : "";
                    else if ("html".equals(attr)) v = e.html();
                    else if ("text".equals(attr)) v = e.text();
                    else if ("ownText".equals(attr)) v = e.ownText();
                    else if ("textNodes".equals(attr)) {
                        // Legado getResultLast 的 textNodes：把元素的直接文本节点按换行拼起来
                        StringBuilder tb = new StringBuilder();
                        for (org.jsoup.nodes.TextNode t : e.textNodes()) {
                            String tt = t.text() == null ? "" : t.text().trim();
                            if (tt.isEmpty()) continue;
                            if (tb.length() > 0) tb.append("\n");
                            tb.append(tt);
                        }
                        v = tb.toString();
                    }
                    else v = e.attr(attr);          // content / title / 任意属性名
                    if (v != null && !v.trim().isEmpty()) out.add(v.trim());
                    if (attr.equals("href") || attr.equals("src")) break;  // 取链接只取第一个
                }
            }
        } catch (Throwable e) {
            noteRuleFail(rule, e);
        }
        }
        if (replacePattern != null && !out.isEmpty()) {
            List<String> rep = new ArrayList<>();
            for (String v : out) {
                try {
                    if (onlyOne) rep.add(java.util.regex.Pattern.compile(replacePattern).matcher(v).replaceFirst(replaceWith == null ? "" : replaceWith));
                    else rep.add(v.replaceAll(replacePattern, replaceWith == null ? "" : replaceWith));
                } catch (Exception e) { rep.add(v); }
            }
            out = rep;
        }
        return out;
    }

    public String ruleFirst(String rule, String base, String html) {
        List<String> v = ruleAll(rule, base, html);
        return v.isEmpty() ? "" : v.get(0);
    }

    /** 同一条坏规则一次进程里只留一行日志，之后静默计数：坏源不该把日志刷满 */
    private final java.util.Set<String> ruleFailSeen =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private int ruleFailMuted = 0;

    private void noteRuleFail(String rule, Throwable e) {
        String key = String.valueOf(rule);
        if (ruleFailSeen.size() > 300) ruleFailSeen.clear();
        boolean first = ruleFailSeen.add(key);
        if (!first) { ruleFailMuted++; return; }
        host.log("听书", "规则执行失败 " + rule + "：" + (e == null ? "" : e.getMessage())
                + (ruleFailMuted > 0 ? "（同期重复 " + ruleFailMuted + " 次已略）" : ""));
        ruleFailMuted = 0;
    }

    /**
     * URL 取值的专用通道，对齐 Legado 的 AnalyzeUrl / AnalyzeRule.getString(…, isUrl=true)。
     *
     * <p>Legado 里地址是独立流程：initUrl = analyzeJs(@js:、&lt;js&gt;) → replaceKeyPageJs({{ }}) →
     * analyzeUrl(拼 base、处理 query)；收尾再由 getAbsoluteURL 绝对化。它从不把地址交给 jsoup，
     * 所以不会出现「Could not parse query」。
     *
     * <p>规则形态分两路：整条就是地址（http:// ├ https:// ├ // 开头）→ 只做模板填装 + 绝对化；
     * 否则当普通规则取值（如 a.toc@href、$.data.url），取到后再绝对化。
     * @param content 上一步的响应原文，模板里的 {{ }} 与 {$.x} 都以它为数据源
     */
    public String urlOf(String rule, String base, String content) {
        if (rule == null) return "";
        for (String cand : splitTop(rule.trim(), "||")) {
            String one = cand == null ? "" : cand.trim();
            if (one.isEmpty()) continue;
            if (one.startsWith("@put:") || one.endsWith("###")) continue;   // 这两类不是地址候选
            String v = urlOne(one, base, content);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private String urlOne(String r, String base, String content) {
        // 地址里也允许挂 @put: / @get: 段（企鹅阅读就是 ...?bookId={{$..resourceID}}@put:{bid:...},{"headers":...}）。
        // 以前地址通道不剥这两段，整串被原样当 URL 发出去，服务端只回 65 字节。
        String body = applyGet(stripPut(r == null ? "" : r.trim()));
        if (body.isEmpty()) return "";
        String src = content == null ? "" : content;
        // 整条规则是 JS（@js: / <js>…</js>）的，先求值再判是不是地址。
        // 爱听小说的 tocUrl 整条就是 <js>…</js>（把 /bookinfo/ 换成 /audiolist-4/），
        // 以前 JS 分支只在 http 开头的地址里跑，这条规则被当成选择器，取空 → 回退成详情页 URL。
        if (body.startsWith("@js:") || body.indexOf("<js>") >= 0)
            body = fillUrlTpl(body, base, src);
        boolean rawUrl = body.startsWith("http://") || body.startsWith("https://");
        if (rawUrl) {
            if (body.indexOf('{') >= 0) body = fillUrlTpl(body, base, src);
            // 模板没填完就别把半成品当地址发出去（Legado 也是模板块求值为空就整体判空）；
            // 这一类是真·取不到（响应不是 JSON / 变量不存在），留一行可查，别静默
            if (body.contains("{{") || body.contains("{$")) {
                noteRuleFail(body, new RuntimeException("URL 模板填不满，响应里取不到模板变量"));
                return "";
            }
            // 保留 ,{...} 这种「链接选项」尾巴：method/body/headers 全在里面，
            // 以前直接当整体做绝对化，尾巴被吃掉，POST 目录页全都变成 GET 去请求（趣悦小说就是这样）
            int cut = optionStart(body);
            // 尾巴里没有 method/body/headers/charset 的（比如 ,{'webView': true}），
            // 只是阅读端的 UI 选项，拼进地址会把请求打歪 —— 顶点小说就是这么被拼坏的
            String tail = (cut > 0 && hasRequestOption(body.substring(cut))) ? body.substring(cut) : "";
            String main = cut > 0 ? body.substring(0, cut).trim() : body;
            String abs = NetBookController.absUrl(base, main);
            String u = abs.isEmpty() ? main : abs;
            return u + tail;
        }
        // 选择器 / JSONPath / @js 取值后绝对化（Legado 的 isUrl 只影响收尾，不影响取法）
        String srcRule = body;
        if ((body.startsWith("@js:") || body.contains("<js>")) && body.indexOf("{{") >= 0)
            srcRule = expandJinja(body, base, src);   // 同上：JS 里写模板要先填
        String v = ruleFirst(srcRule, base, src);
        if (v == null || v.trim().isEmpty()) return "";
        if (v.contains("{{") || v.contains("{$")) {
            String t = fillUrlTpl(v.trim(), base, src);
            if (t != null && !t.trim().isEmpty()) v = t;
        }        String abs = NetBookController.absUrl(base, v.trim());
        return abs.isEmpty() ? v.trim() : abs;
    }

    /**
     * 规则最后一段要不要当「属性名」取。
     * 不能见标识符就当属性：@a / @li / @p 这类是标签选择器（class.book@a 取链接），
     * 只有 text/href/src/content/data-src… 这些才是属性。以前只认 href/src/text/html/title，
     * 于是 meta[property="og:novel:book_name"]@content 全取空（精选源里出现 95 次）。
     */
    private boolean isAttrSuffix(String s) {
        if (s == null || s.isEmpty()) return false;
        switch (s) {
            case "text": case "href": case "src": case "html": case "title":
            case "content": case "property": case "alt": case "value": case "poster":
            case "data-src": case "data-original": case "data-url": case "data-id":
            case "data-video": case "data-cover": case "datetime":
            case "textNodes": case "ownText":
                return true;
            default:
                return false;
        }
    }

    /** 尾巴里是否带真正的请求选项（method/body/headers/charset/retry） */
    private boolean hasRequestOption(String tail) {
        String t = tail == null ? "" : tail.trim();
        if (t.startsWith(",")) t = t.substring(1);
        Object o = UrlResolver.parseJson(t);
        if (!(o instanceof java.util.Map)) return false;
        java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
        return m.containsKey("method") || m.containsKey("body") || m.containsKey("headers")
                || m.containsKey("charset") || m.containsKey("retry");
    }

    /** 找「链接选项」起点：花括号外的 ,{，{{ }} 里面的逗号不算 */
    private int optionStart(String body) {
        if (body == null) return -1;
        int depth = 0;
        for (int i = 0; i + 1 < body.length(); i++) {
            if (depth == 0 && body.charAt(i) == '{' && body.charAt(i + 1) == '{') { depth++; i++; continue; }
            if (depth > 0 && body.charAt(i) == '}' && body.charAt(i + 1) == '}') { depth--; i++; continue; }
            if (depth == 0 && body.charAt(i) == ',' && body.charAt(i + 1) == '{') return i;
        }
        return -1;
    }

    /** URL 模板填装：@js / &lt;js&gt; → {{ }} 内插 → JSON 条目的 {$.id}（都填不了就原样返回，由调用方判空） */
    private String fillUrlTpl(String body, String base, String content) {
        String b = body;
        // 源码里的 {{$.bid}} 先填一遍再进 JS：全免小说的 tocUrl 是
        // @js: t="...&bid={{$.bid}}&..."; "http://...?"+t —— 把模板写在 JS 字符串里，
        // 先跑 JS 就只能拿到字面 "{{$.bid}}" 去拼链接，服务器回「参数为空」。
        // Legado 的模板替换也发生在规则执行之前，这里对齐它。
        if (b.indexOf("{{") >= 0) b = expandJinja(b, base, content);
        if (b.startsWith("@js:") || b.contains("<js>")) {
            String v = b.startsWith("@js:") ? host.evalJs(b.substring(4), content, base)
                    : evalJsBlocks(b, base, content);
            if (v != null && !v.trim().isEmpty()) b = v.trim();
        }
        if (b.indexOf("{{") >= 0) b = expandJinja(b, base, content);
        if (b.indexOf("{$.") >= 0 || b.indexOf("{$[") >= 0) {
            JSONObject root = jsonRootOf(content);
            if (root != null) b = fillJsonTemplate(b, root);
        }
        return b;
    }

    /** 响应原文如果是 JSON 对象（或数组），解析出根供 URL 模板取值；不是 JSON 返回 null */
    private JSONObject jsonRootOf(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) {
            // 模板要从响应里取 {$.x}，但响应是 HTML 页面的：同样从内嵌数据里取
            String emb = embeddedJsonText(s);
            if (emb == null) return null;
            t = emb.trim();
        }
        try {
            if (t.startsWith("[")) return new JSONObject("{\"__arr\":" + t + "}");
            return new JSONObject(t);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * ruleBookInfo 的 init 规则：Legado 允许把详情响应先归一化一次，之后 name/author/tocUrl
     * 等字段规则都作用在归一化结果上。洋柿子酱就是靠它把 {code,data:{...}} 拆成 data 层
     * （<js>fqBookInfo(result)</js>）；漏了这一步，$.bookName 取不到、tocUrl 里的 JS 拿到
     * 的也是外层包，info.bookId 为 undefined，直接抛「无效的书籍或章节标识」，目录就永远出不来。
     * init 没写或执行结果为空时退回原文，别把整条链接死。
     */
    public String applyInit(JSONObject ruleBookInfo, String base, String html) {
        if (ruleBookInfo == null || html == null) return html;
        String init = ruleStr(ruleBookInfo, "init");
        if (init == null || init.trim().isEmpty()) return html;
        String v = ruleFirst(init, base, html);
        if (v == null || v.trim().isEmpty()) return html;
        return v;
    }

    /**
     * 规则要的是 JSON，站点给的却是 HTML 页面——这时页面里通常还留着同一份结构化数据：     * 详情页的 application/ld+json（书名、作者最全）、SPA 的 __NEXT_DATA__、
     * Vue 的 __INITIAL_STATE__ 或 __NUXT__。把它们捞出来顶上，比直接判空强得多。
     *
     * 只认「能完整闭合的对象/数组」，捞不到就返回 null，让调用方按原样失败——不猜。
     */
    public static String embeddedJsonText(String html) {
        if (html == null || html.isEmpty()) return null;
        String hit = firstJsonLd(html);
        if (hit != null) return hit;
        hit = scriptBodyAfter(html, "__NEXT_DATA__");
        if (hit != null) return hit;
        hit = assignedObjectAfter(html, "__INITIAL_STATE__");
        if (hit != null) return hit;
        hit = assignedObjectAfter(html, "__NUXT__");
        if (hit != null) return hit;
        return null;
    }

    /** 详情页的 JSON-LD；有多个时取第一个能闭合、且带 name 的 */
    private static String firstJsonLd(String html) {
        int from = 0;
        while (true) {
            int i = html.indexOf("ld+json", from);
            if (i < 0) return null;
            from = i + 7;
            int gt = html.indexOf('>', i);
            if (gt < 0) return null;
            int end = html.indexOf("</script", gt);
            if (end < 0) return null;
            String c = html.substring(gt + 1, end).trim();
            if (!(c.startsWith("{") || c.startsWith("["))) continue;
            String cut = cutJson(c);
            if (cut == null) continue;
            if (cut.indexOf("\"name\"") >= 0 || cut.indexOf("\"@type\"") >= 0) return cut;
        }
    }

    /** <script id="__NEXT_DATA__">{…}</script> */
    private static String scriptBodyAfter(String html, String marker) {
        int i = html.indexOf(marker);
        if (i < 0) return null;
        int gt = html.indexOf('>', i);
        if (gt < 0) return null;
        int end = html.indexOf("</script", gt);
        if (end < 0) return null;
        String c = html.substring(gt + 1, end).trim();
        return cutJson(c);
    }

    /** window.__X__ = {…} 或 window.__X__ = JSON.parse("…")；只取直接写成对象字面量的那种 */
    private static String assignedObjectAfter(String html, String marker) {
        int i = html.indexOf(marker);
        if (i < 0) return null;
        int eq = html.indexOf('=', i);
        if (eq < 0) return null;
        int brace = -1;
        for (int k = eq + 1; k < html.length() && k < eq + 200; k++) {
            char ch = html.charAt(k);
            if (ch == '{' || ch == '[') { brace = k; break; }
            if (ch == ';') break;
        }
        if (brace < 0) return null;
        return cutJson(html.substring(brace));
    }

    /** 从一段文本开头切出一个括号完整闭合的 JSON 片段；切不出来返回 null */
    private static String cutJson(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        char open = t.charAt(0);
        char close = open == '[' ? ']' : (open == '{' ? '}' : 0);
        if (close == 0) return null;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (inStr) {
                if (esc) { esc = false; continue; }
                if (ch == '\\') { esc = true; continue; }
                if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') { inStr = true; continue; }
            if (ch == open) depth++;
            else if (ch == close) {
                depth--;
                if (depth == 0) return t.substring(0, i + 1);
            }
        }
        return null;
    }

    /** 响应是什么形态：空 / HTML / JSON / brotli 的 base64 / base64 / 其它文本。只用于日志定位 */
    public static String bodyKind(String s) {
        if (s == null || s.trim().isEmpty()) return "空响应";
        String t = s.trim();
        if (t.startsWith("<")) return "HTML";
        if (t.startsWith("{") || t.startsWith("[")) return "JSON";
        if (BodyCodec.looksBrotliBase64(t)) return "brotli的base64";
        if (t.length() >= 40 && t.matches("(?s)^[A-Za-z0-9+/=\\s]+$")) return "base64";
        return "其它文本";
    }

    /** 规则是哪一路：js / json / css / xpath / 纯文本。只用于日志定位 */
    public static String ruleKind(String rule) {
        if (rule == null) return "空";
        String r = rule.trim();
        if (r.isEmpty()) return "空";
        if (r.startsWith("@js:") || r.contains("<js>")) return "js";
        if (r.startsWith("@json:") || r.startsWith("$.") || r.startsWith("$[")) return "json";
        if (r.startsWith("@css:")) return "css";
        if (r.startsWith("@xpath:") || r.startsWith("//") || r.startsWith("(/")) return "xpath";
        return "text";
    }

    /** 取开头几个字，去掉换行，日志里一眼看出这一页到底给了什么 */
    public static String headOf(String s, int n) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() > n) t = t.substring(0, n);
        return t.replace('\n', ' ').replace('\r', ' ');
    }

    public List<String> jsonPathAll(String json, String path) {
        List<String> out = new ArrayList<>();
        String raw = json == null ? "" : json;
        String head = raw.trim();
        if (head.length() > 24) head = head.substring(0, 24);
        head = head.replace('\n', ' ').replace('\r', ' ');
        try {
            String t = raw.trim();
            if (!(t.startsWith("{") || t.startsWith("["))) {
                // 规则按 JSON 取、拿到的却是 HTML：先把页面里内嵌的那份结构化数据捞出来顶上
                String emb = embeddedJsonText(raw);
                if (emb == null) {
                    noteRuleFail("json:" + path + " → 响应不是 JSON（开头「" + head + "」）", null);
                    return out;
                }
                host.log("听书", "规则取 JSON、响应是 HTML，改从页面内嵌数据取值：" + path);
                t = emb.trim();
            }
            Object root;
            root = t.startsWith("[") ? new org.json.JSONArray(t) : (Object) new JSONObject(t);
            String p = path.trim();
            if (p.startsWith("$")) p = p.substring(1);
            while (p.startsWith(".")) p = p.substring(1);
            p = p.replace(".[", "[");
            List<Object> cur = new ArrayList<>();
            cur.add(root);
            for (String seg : p.split("\\.")) {
                List<Object> next = new ArrayList<>();
                for (Object o : cur) {
                    if (seg.endsWith("[*]")) {
                        String key = seg.substring(0, seg.length() - 3);
                        if (key.isEmpty()) {
                            if (o instanceof org.json.JSONArray) {
                                org.json.JSONArray a = (org.json.JSONArray) o;
                                for (int i = 0; i < a.length(); i++) next.add(a.opt(i));
                            }
                        } else if (o instanceof JSONObject) {
                            org.json.JSONArray a = ((JSONObject) o).optJSONArray(key);
                            for (int i = 0; a != null && i < a.length(); i++) next.add(a.opt(i));
                        }
                    } else if (seg.contains("[") && seg.endsWith("]")) {
                        String key = seg.substring(0, seg.indexOf('['));
                        int idx;
                        try { idx = Integer.parseInt(seg.substring(seg.indexOf('[') + 1, seg.length() - 1)); } catch (Exception e) { idx = 0; }
                        Object arr = key.isEmpty() ? o : (o instanceof JSONObject ? ((JSONObject) o).opt(key) : null);
                        if (arr instanceof org.json.JSONArray) next.add(((org.json.JSONArray) arr).opt(idx));
                    } else {
                        if (o instanceof JSONObject) {
                            Object v = ((JSONObject) o).opt(seg);
                            if (v != null) next.add(v);
                        }
                    }
                }
                cur = next;
            }
            for (Object o : cur) if (o != null && o != org.json.JSONObject.NULL) out.add(String.valueOf(o));
        } catch (Exception e) {
            noteRuleFail("json:" + path, e);
        }
        return out;
    }

    public List<String> xpathAll(String html, String expr, String base) {
        List<String> out = new ArrayList<>();
        try {
            String xml = org.jsoup.Jsoup.parse(html, base).html();
            xml = xml.replace("&nbsp;", "&#160;");
            // jsoup 输出的是 HTML，<link>/<meta>/<img> 这类空元素没有自闭合，XML 解析器会直接拒绝
            xml = xml.replaceAll("(?i)<(link|meta|img|br|hr|input|base|col|area|embed|param|source|track|wbr)([^>]*?)/>",
                    "<$1$2>");
            xml = xml.replaceAll("(?i)<(link|meta|img|br|hr|input|base|col|area|embed|param|source|track|wbr)([^>]*[^/])>",
                    "<$1$2/>");
            xml = "<root>" + xml + "</root>";
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setExpandEntityReferences(false);
            org.w3c.dom.Document doc = f.newDocumentBuilder().parse(
                    new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            javax.xml.xpath.XPath xp = javax.xml.xpath.XPathFactory.newInstance().newXPath();
            javax.xml.xpath.XPathExpression e = xp.compile(expr);
            org.w3c.dom.NodeList nodes = (org.w3c.dom.NodeList) e.evaluate(doc, javax.xml.xpath.XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                String v = nodes.item(i).getTextContent();
                if (v != null && !v.trim().isEmpty()) out.add(v.trim());
            }
        } catch (Exception e) {
            noteRuleFail("xpath:" + expr, e);
        }
        return out;
    }

    public boolean looksJson(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    public List<org.jsoup.nodes.Element> elemsSingle(org.jsoup.nodes.Element temp, String rule) {
        List<org.jsoup.nodes.Element> elements = new ArrayList<>();
        if (temp == null) return elements;
        IdxSpec spec = findIndexSet(rule);
        String br = spec.beforeRule == null ? "" : spec.beforeRule;
        if (br.isEmpty()) {
            for (org.jsoup.nodes.Element e : temp.children()) elements.add(e);
        } else {
            int dot = br.indexOf('.');
            String t = dot < 0 ? br : br.substring(0, dot);
            String n = dot < 0 ? "" : br.substring(dot + 1);
            int d2 = n.indexOf('.');
            String nm = d2 < 0 ? n : n.substring(0, d2);
            if ("children".equals(t)) { for (org.jsoup.nodes.Element e : temp.children()) elements.add(e); }
            else if ("class".equals(t)) {
                // class.a b：两个类必须同时具备（若初文学 class.float-list fill-block@li 就是这种）
                String[] cns = nm.trim().split("\\s+");
                java.util.List<org.jsoup.nodes.Element> base = new ArrayList<>();
                if (cns.length > 0 && !cns[0].isEmpty()) base.addAll(temp.getElementsByClass(cns[0]));
                for (int ci = 1; ci < cns.length; ci++) {
                    String cn = cns[ci];
                    if (cn.isEmpty()) continue;
                    java.util.Iterator<org.jsoup.nodes.Element> itc = base.iterator();
                    while (itc.hasNext()) if (!itc.next().hasClass(cn)) itc.remove();
                }
                elements.addAll(base);
            }
            else if ("tag".equals(t)) { for (org.jsoup.nodes.Element e : temp.getElementsByTag(nm)) elements.add(e); }
            else if ("id".equals(t)) {
                for (org.jsoup.nodes.Element e : temp.getAllElements()) if (nm.equals(e.id())) elements.add(e);
            } else if ("text".equals(t)) {
                for (org.jsoup.nodes.Element e : temp.getAllElements()) if (e.ownText().contains(nm)) elements.add(e);
            } else {
                try { for (org.jsoup.nodes.Element e : temp.select(br)) elements.add(e); } catch (Exception ignored) {}
            }
        }
        int len = elements.size();
        if (len == 0) return elements;
        int lastIndexes = spec.indexDefault.size() - 1;
        if (lastIndexes == -1) lastIndexes = spec.indexes.size() - 1;
        java.util.LinkedHashSet<Integer> indexSet = new java.util.LinkedHashSet<>();
        for (int ix = lastIndexes; ix >= 0; ix--) {
            if (spec.indexDefault.isEmpty()) {
                Object o = spec.indexes.get(ix);
                if (o instanceof Integer[]) {
                    Integer[] a3 = (Integer[]) o;
                    int start = a3[0] == null ? 0 : a3[0];
                    if (start < 0) start += len;
                    int end = a3[1] == null ? len - 1 : a3[1];
                    if (end < 0) end += len;
                    if ((start < 0 && end < 0) || (start >= len && end >= len)) continue;
                    if (start >= len) start = len - 1; else if (start < 0) start = 0;
                    if (end >= len) end = len - 1; else if (end < 0) end = 0;
                    int stepX = a3[2] == null ? 1 : a3[2];
                    if (start == end || stepX >= len) { indexSet.add(start); continue; }
                    int step = stepX > 0 ? stepX : (-stepX < len ? stepX + len : 1);
                    if (end > start) { for (int k = start; k <= end; k += step) indexSet.add(k); }
                    else { for (int k = start; k >= end; k -= step) indexSet.add(k); }
                } else {
                    int v = (Integer) o;
                    if (v >= 0 && v < len) indexSet.add(v);
                    else if (v < 0 && len >= -v) indexSet.add(v + len);
                }
            } else {
                int v = spec.indexDefault.get(ix);
                if (v >= 0 && v < len) indexSet.add(v);
                else if (v < 0 && len >= -v) indexSet.add(v + len);
            }
        }
        if (spec.split != '!' && spec.split != '.') return elements;
        if (spec.split == '!' && !indexSet.isEmpty()) {
            List<org.jsoup.nodes.Element> keep = new ArrayList<>();
            int k = 0;
            for (org.jsoup.nodes.Element e : elements) { if (!indexSet.contains(k)) keep.add(e); k++; }
            return keep;
        }
        List<org.jsoup.nodes.Element> sel = new ArrayList<>();
        for (int k : indexSet) if (k >= 0 && k < len) sel.add(elements.get(k));
        return sel;
    }

    public List<String> splitTop(String rule, String sep) {
        List<String> out = new ArrayList<>();
        if (rule == null) return out;
        StringBuilder sb = new StringBuilder();
        int brace = 0, bracket = 0, paren = 0, hash = 0;
        boolean inS = false, inD = false;
        int i = 0;
        while (i < rule.length()) {
            char c = rule.charAt(i);
            if (c == '\'' && !inD) inS = !inS;
            else if (c == '"' && !inS) inD = !inD;
            if (!inS && !inD) {
                if (c == '{') brace++;
                else if (c == '}') brace = Math.max(0, brace - 1);
                else if (c == '[') bracket++;
                else if (c == ']') bracket = Math.max(0, bracket - 1);
                else if (c == '(') paren++;
                else if (c == ')') paren = Math.max(0, paren - 1);
                else if (c == '#' && i + 1 < rule.length() && rule.charAt(i + 1) == '#') { hash++; i += 2; sb.append("##"); continue; }
                if (brace == 0 && bracket == 0 && paren == 0 && hash % 2 == 0 && rule.startsWith(sep, i)) {
                    out.add(sb.toString());
                    sb.setLength(0);
                    i += sep.length();
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        out.add(sb.toString());
        return out;
    }

    public List<org.jsoup.nodes.Element> defaultChain(String rule, String base, String html) {
        List<org.jsoup.nodes.Element> cur = new ArrayList<>();
        String body = rule == null ? "" : rule.trim();
        if (body.startsWith("@css:")) body = body.substring(5);
        while (body.startsWith("-")) body = body.substring(1);
        if (body.isEmpty()) return cur;
        List<String> segs = splitTop(body, "@");
        cur.add(org.jsoup.Jsoup.parse(html == null ? "" : html, base == null ? "" : base));
        for (int i = 0; i < segs.size(); i++) {
            String raw = segs.get(i).trim();
            if (raw.isEmpty()) continue;
            if (i == segs.size() - 1 && (isLeafToken(raw) || "all".equals(raw))) break;
            List<org.jsoup.nodes.Element> next = new ArrayList<>();
            for (org.jsoup.nodes.Element e : cur) next.addAll(elemsSingle(e, raw));
            cur = next;
            if (cur.isEmpty()) break;
        }
        return cur;
    }

    public List<String> defaultRuleAll(String rule, String base, String html) {
        List<String> out = new ArrayList<>();
        String body = rule == null ? "" : rule.trim();
        boolean rev = false;
        while (body.startsWith("-")) { rev = true; body = body.substring(1); }
        if (body.startsWith("@css:")) body = body.substring(5);
        if (body.isEmpty()) return out;
        List<String> segs = splitTop(body, "@");
        List<org.jsoup.nodes.Element> cur = new ArrayList<>();
        cur.add(org.jsoup.Jsoup.parse(html == null ? "" : html, base == null ? "" : base));
        String last = "text";
        int n = segs.size();
        for (int i = 0; i < n; i++) {
            String raw = segs.get(i).trim();
            if (raw.isEmpty()) continue;
            if (i == n - 1) { if (!raw.isEmpty()) last = raw; break; }
            List<org.jsoup.nodes.Element> next = new ArrayList<>();
            for (org.jsoup.nodes.Element e : cur) next.addAll(elemsSingle(e, raw));
            cur = next;
            if (cur.isEmpty()) break;
        }
        for (org.jsoup.nodes.Element e : cur) {
            String v = valueOf(e, last);
            if (v != null && !v.trim().isEmpty()) out.add(v.trim());
        }
        if (rev) java.util.Collections.reverse(out);
        return out;
    }

    public List<String> evalAnd(String rule, String base, String html) {
        List<String> out = new ArrayList<>();
        if (rule == null) return out;
        if (rule.contains("%%")) {
            List<String> parts = splitTop(rule, "%%");
            List<List<String>> lists = new ArrayList<>();
            int max = 0;
            for (String p : parts) {
                List<String> one = ruleSimple(p, base, html);
                lists.add(one);
                max = Math.max(max, one.size());
            }
            for (int i = 0; i < max; i++) for (List<String> one : lists) if (i < one.size()) out.add(one.get(i));
            return out;
        }
        for (String p : splitTop(rule, "&&")) out.addAll(ruleSimple(p, base, html));
        return out;
    }

    public List<String> ruleWithConnectors(String rule, String base, String html) {
        List<String> out = new ArrayList<>();
        if (rule == null) return out;
        for (String p : splitTop(rule, "||")) {
            List<String> one = evalAnd(p, base, html);
            if (!one.isEmpty()) return one;
        }
        return out;
    }

    public List<org.jsoup.nodes.Element> chainElements(String rule, String base, String html) {
        List<org.jsoup.nodes.Element> cur = new ArrayList<>();
        String[] segs = rule.split("@");
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html, base);
        String first = segs.length == 0 ? "" : segs[0].trim();
        String sel = selectorOf(first);
        if (sel.isEmpty()) return cur;
        org.jsoup.select.Elements els = doc.select(sel);
        for (org.jsoup.nodes.Element e : els) cur.add(e);
        for (int i = 1; i < segs.length; i++) {
            String s = segs[i].trim();
            if (isLeafToken(s)) break;          // 末尾是取值指令，不是选择器
            String childSel = selectorOf(s);
            if (childSel.isEmpty()) continue;
            List<org.jsoup.nodes.Element> next = new ArrayList<>();
            for (org.jsoup.nodes.Element e : cur) {
                org.jsoup.select.Elements kids = e.select(childSel);
                for (org.jsoup.nodes.Element k : kids) next.add(k);
            }
            cur = next;
        }
        return cur;
    }

    public String selectorOf(String seg) {
        if (seg == null) return "";
        String s = seg.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("id.")) return "#" + s.substring(3);
        if (s.startsWith("class.")) {
            // class.float-list fill-block → .float-list.fill-block
            // 以前只给第一个加 "."，第二个名字原样留着，jsoup 把它当标签名，选择器必然选不到
            String rest = s.substring(6).trim();
            StringBuilder sb = new StringBuilder();
            for (String part : rest.split("\\s+")) {
                if (part.isEmpty()) continue;
                sb.append(part.startsWith(".") ? part : "." + part);
            }
            return sb.length() == 0 ? s : sb.toString();
        }
        if (s.startsWith("tag.")) return s.substring(4);
        return quoteAttr(s);
    }

    public String quoteAttr(String s) {
        if (s == null || !s.contains("=") || !s.contains("[")) return s;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '[') {
                int close = s.indexOf(']', i);
                if (close > i) {
                    String inner = s.substring(i + 1, close);
                    int eq = inner.indexOf('=');
                    if (eq > 0) {
                        String key = inner.substring(0, eq);
                        String val = inner.substring(eq + 1).trim();
                        if (!(val.startsWith("\"") || val.startsWith("'")) &&
                                (val.contains(":") || val.contains(" ") || val.contains("/"))) {
                            val = "\"" + val + "\"";
                        }
                        sb.append("[").append(key).append("=").append(val).append("]");
                        i = close + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    public boolean isLeafToken(String s) {
        if (s == null) return false;
        return s.equals("text") || s.equals("href") || s.equals("src") || s.equals("html")
                || s.equals("content") || s.equals("title") || s.equals("textNodes")
                || s.equals("data-src");
    }

    public String valueOf(org.jsoup.nodes.Element e, String token) {
        if (e == null) return "";
        if ("ownText".equals(token)) return e.ownText();
        if ("all".equals(token)) return e.outerHtml();
        if ("textNodes".equals(token)) {
            StringBuilder sb = new StringBuilder();
            for (org.jsoup.nodes.TextNode t : e.textNodes()) sb.append(t.text().trim()).append("\n");
            return sb.toString().trim();
        }
        if ("text".equals(token)) return e.text();
        if ("html".equals(token)) {
            org.jsoup.nodes.Element c = e.clone();
            c.select("script").remove();
            c.select("style").remove();
            return c.outerHtml();
        }
        return e.attr(token);
    }

    /** 段内 [?()] 过滤表达式（只支持一层比较） */
    private static final Pattern F_SEG = Pattern.compile("^([^\\[\\]]*)\\[\\?\\((.+)\\)\\](.*)$");
    private static final Pattern F_IDX = Pattern.compile("\\[(\\*|-?\\d+)\\]");

    public List<Object> jsonObjects(Object root, String path) {
        String p = path.trim();
        if (p.startsWith("$")) p = p.substring(1);
        boolean deepFirst = false;
        if (p.startsWith("..")) { deepFirst = true; p = p.substring(2); }
        while (p.startsWith(".")) p = p.substring(1);
        List<Object> cur = new ArrayList<>();
        cur.add(root);
        // 旧版 collectDeep 只在"下一段不是索引"时生效，\$..bookList[*] 这种带 [*] 的
        // 递归就被 pickRaw 直接按当前层找，找不到就 0 条。1.1.25 起：递归段一律走 collectDeepRaw。
        java.util.List<String> segs = new ArrayList<String>();
        for (String seg0 : splitPathSegs(p)) {
            String seg = seg0.trim();
            if (seg.isEmpty()) continue;
            java.util.regex.Matcher fm = F_SEG.matcher(seg);
            if (fm.matches()) {
                String head = fm.group(1).trim();
                String cond = fm.group(2).trim();
                String rest = fm.group(3).trim();
                if (!head.isEmpty()) segs.add(head);
                segs.add("?" + cond);
                java.util.regex.Matcher ri = F_IDX.matcher(rest);
                while (ri.find()) segs.add("@" + ri.group(1));
                continue;
            }
            if ("*".equals(seg)) { segs.add("*"); continue; }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^([^\\[\\]]*)((?:\\[\\*\\]|\\[-?\\d+\\])+)$").matcher(seg);
            if (m.matches()) {
                String head = m.group(1).trim();
                if (!head.isEmpty()) segs.add(head);
                java.util.regex.Matcher im = java.util.regex.Pattern
                        .compile("\\[(\\*|-?\\d+)\\]").matcher(m.group(2));
                while (im.find()) segs.add("@" + im.group(1));
            } else {
                segs.add(seg);
            }
        }
        int used = 0;
        for (int si = 0; si < segs.size(); si++) {
            String seg = segs.get(si);
            List<Object> next = new ArrayList<>();
            if (seg.startsWith("?")) {
                String cond = seg.substring(1);
                for (Object o : cur) {
                    if (o instanceof org.json.JSONArray) {
                        org.json.JSONArray a = (org.json.JSONArray) o;
                        for (int i = 0; i < a.length(); i++) {
                            Object e = a.opt(i);
                            if (filterMatch(e, cond)) next.add(e);
                        }
                    } else if (filterMatch(o, cond)) next.add(o);
                }
                cur = next;
                used++;
                continue;
            }
            if ("*".equals(seg)) {
                for (Object o : cur) {
                    if (o instanceof JSONObject) {
                        JSONObject j = (JSONObject) o;
                        java.util.Iterator<String> it = j.keys();
                        while (it.hasNext()) {
                            Object v = j.opt(it.next());
                            if (v instanceof org.json.JSONArray) {
                                org.json.JSONArray a = (org.json.JSONArray) v;
                                for (int i = 0; i < a.length(); i++) next.add(a.opt(i));
                            } else if (v != null) next.add(v);
                        }
                    } else if (o instanceof org.json.JSONArray) {
                        org.json.JSONArray a = (org.json.JSONArray) o;
                        for (int i = 0; i < a.length(); i++) next.add(a.opt(i));
                    } else if (o != null) next.add(o);
                }
                cur = next;
                used++;
                continue;
            }
            if (seg.startsWith("@")) {
                String body = seg.substring(1);
                for (Object o : cur) {
                    if (!(o instanceof org.json.JSONArray)) { next.add(o); continue; }
                    org.json.JSONArray a = (org.json.JSONArray) o;
                    if ("*".equals(body)) { for (int i = 0; i < a.length(); i++) next.add(a.opt(i)); }
                    else {
                        int k;
                        try { int idx = Integer.parseInt(body); k = idx < 0 ? a.length() + idx : idx; }
                        catch (Exception e) { k = -1; }
                        if (k >= 0 && k < a.length()) next.add(a.opt(k));
                    }
                }
                cur = next;
                used++;
                continue;
            }
            boolean nextIsIdx = si + 1 < segs.size() && (segs.get(si + 1).startsWith("@") || segs.get(si + 1).startsWith("?"));
            for (Object o : cur) {
                if (used == 0 && deepFirst) collectDeepRaw(o, seg, next);
                else if (nextIsIdx) pickRaw(o, seg, next);
                else pickOne(o, seg, false, next);
            }
            cur = next;
            used++;
        }
        return cur;
    }

    /** 按顶层 "." 切 JSON 路径：[] 与 () 里的点不切（Legado splitRule 同样避开括号） */
    static java.util.List<String> splitPathSegs(String p) {
        java.util.List<String> out = new ArrayList<String>();
        if (p == null) return out;
        StringBuilder sb = new StringBuilder();
        int sq = 0, pa = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '[') sq++;
            else if (c == ']') sq = Math.max(0, sq - 1);
            else if (c == '(') pa++;
            else if (c == ')') pa = Math.max(0, pa - 1);
            if (c == '.' && sq == 0 && pa == 0) {
                out.add(sb.toString());
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        out.add(sb.toString());
        return out;
    }

    /** \$..name：深度优先把所有同名键的「原值」收上来（不摊平数组，留给后面的 [*] 展开） */
    public void collectDeepRaw(Object o, String name, List<Object> out) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (j.has(name)) {
                Object v = j.opt(name);
                if (v != null) out.add(v);
            }
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (name.equals(k)) continue;
                Object child = j.opt(k);
                if (child instanceof JSONObject || child instanceof org.json.JSONArray) collectDeepRaw(child, name, out);
            }
        } else if (o instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                Object child = a.opt(i);
                if (child instanceof JSONObject || child instanceof org.json.JSONArray) collectDeepRaw(child, name, out);
            }
        }
    }

    /** [?(@.key == 'x')] 的单条判断，只认 == / != / 包含 */
    public boolean filterMatch(Object item, String cond) {
        if (cond == null) return true;
        String c = cond.trim();
        if (c.startsWith("@.")) c = c.substring(2);
        if (c.startsWith("$.")) c = c.substring(2);
        int op = -1;
        String opStr = "";
        int i1 = c.indexOf("==");
        int i2 = c.indexOf("!=");
        if (i1 >= 0) { op = i1; opStr = "=="; }
        if (i2 >= 0 && (op < 0 || i2 < op)) { op = i2; opStr = "!="; }
        if (op < 0) {
            if (!(item instanceof JSONObject)) return false;
            Object v = ((JSONObject) item).opt(c);
            return v != null && !"".equals(v.toString());
        }
        String key = c.substring(0, op).trim();
        String val = c.substring(op + 2).trim();
        if ((val.startsWith("'") && val.endsWith("'")) || (val.startsWith("\"") && val.endsWith("\""))) {
            val = val.substring(1, val.length() - 1);
        }
        Object got = null;
        if (item instanceof JSONObject) got = ((JSONObject) item).opt(key);
        else if (item instanceof org.json.JSONArray && key.matches("-?\\d+")) {
            got = ((org.json.JSONArray) item).opt(Integer.parseInt(key));
        }
        String g = got == null ? "" : got.toString();
        return "==".equals(opStr) ? g.equals(val) : !g.equals(val);
    }

    /** 取「值本身」不展开：后面紧跟 [0] / [*] 时用，否则数组会被提前摊平、索引就没处落 */
    public void pickRaw(Object o, String name, List<Object> out) {
        if (o instanceof JSONObject) {
            Object v = name.isEmpty() ? o : ((JSONObject) o).opt(name);
            if (v != null) out.add(v);
        } else if (o instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                Object e = a.opt(i);
                if (e instanceof JSONObject) {
                    Object v = name.isEmpty() ? e : ((JSONObject) e).opt(name);
                    if (v != null) out.add(v);
                }
            }
        } else if (o != null && name.isEmpty()) {
            out.add(o);
        }
    }

    public void pickOne(Object o, String name, boolean star, List<Object> next) {
        if (o instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) o;
            if (star) { for (int i = 0; i < a.length(); i++) next.add(a.opt(i)); }
            else if (name.isEmpty()) next.add(a);
            else { for (int i = 0; i < a.length(); i++) collectDeep(a.opt(i), name, star, next); }
            return;
        }
        if (!(o instanceof JSONObject)) return;
        JSONObject j = (JSONObject) o;
        Object v = name.isEmpty() ? o : j.opt(name);
        if (v == null) return;
        if (v instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) v;
            for (int i = 0; i < a.length(); i++) next.add(a.opt(i));
        } else next.add(v);
    }

    public void collectDeep(Object o, String name, boolean star, List<Object> out) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (!name.isEmpty() && j.has(name)) {
                Object v = j.opt(name);
                if (v instanceof org.json.JSONArray) {
                    org.json.JSONArray a = (org.json.JSONArray) v;
                    for (int i = 0; i < a.length(); i++) out.add(a.opt(i));
                } else if (v != null) out.add(v);
            }
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                Object child = j.opt(it.next());
                if (child instanceof JSONObject || child instanceof org.json.JSONArray) collectDeep(child, name, star, out);
            }
        } else if (o instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                Object child = a.opt(i);
                if (child instanceof JSONObject || child instanceof org.json.JSONArray) collectDeep(child, name, star, out);
                else if (name.isEmpty()) out.add(child);
            }
        }
    }

    /**
     * 取一条 bookList 规则命中的「列表条目」。对齐 Legado 的 BookList.analyzeBookList：
     * 同一条规则可能是 CSS、$ JSONPath、@json:、<js>（结果再当 JSON 或 HTML 使），
     * 也可能是用 || 拼的多个候选（第一个非空生效）。条目类型由这里决定，上层不再自己猜。
     */
    public List<Object> listItems(String rule, String base, String html) {
        List<Object> out = new ArrayList<>();
        if (rule == null || rule.trim().isEmpty() || html == null) return out;
        for (String cand : splitTop(rule.trim(), "||")) {
            if (cand == null) continue;
            String c = cand.trim();
            if (c.isEmpty()) continue;
            List<Object> one = listItemsOne(c, base, html);
            if (!one.isEmpty()) return one;
        }
        return out;
    }

    private List<Object> listItemsOne(String r, String base, String html) {
        List<Object> out = new ArrayList<>();
        String body = r;
        // 1) 显式 JSON
        String p = jsonPathOf(body);
        if (p != null) {
            List<Object> j = jsonObjectsOf(html, p);
            if (!j.isEmpty()) return j;
        }
        // 1.5) 响应本身是 JSON、规则却写成裸路径（data.state[*]），对齐 Legado 的
        //      isJSON 判定（AnalyzeRule.kt setContent 里 content.toString().isJson()）：
        //      内容优先于规则前缀，裸路径照样走 JSONPath。
        if (jsonPathOf(body) == null && !isHtmlRuleShape(body) && html != null && looksJson(html)) {
            List<Object> j2 = jsonObjectsOf(html, "$." + body.trim());
            if (!j2.isEmpty()) return j2;
        }
        // 2) <js> / @js:：结果可能是 JSON，也可能是 HTML 片段
        if (body.startsWith("@js:") || body.contains("<js>")) {
            String code = body.startsWith("@js:") ? body.substring(4) : body;
            String s = body.startsWith("@js:") ? host.evalJs(code, html, base)
                    : evalJsBlocks(code, base, html);
            // 把 @js 的原始返回打出来：这类规则出 0 条时，光看"出书=0"分不清是
            // 脚本报错、返回空数组、还是返回了东西但结构不对
            if (s == null) {
                host.log("书源", "@js 列表求值失败（脚本报错，看上一行 JS 失败）");
            } else {
                String tt = s.trim();
                host.log("书源", "@js 列表返回 " + tt.length() + " 字节｜"
                        + (tt.length() > 80 ? tt.substring(0, 80) + "…" : tt));
            }
            if (s != null) {
                String t = s.trim();
                if (t.startsWith("[") || t.startsWith("{")) {
                    List<Object> j = jsonObjectsOf(t, "$");
                    if (!j.isEmpty()) return j;
                }
                if (!t.isEmpty()) {
                    String inner = stripLeaf(body.startsWith("@js:") ? "body" : "body");
                    for (org.jsoup.nodes.Element e : defaultChain(inner, base, t)) out.add(e);
                    if (!out.isEmpty()) return out;
                }
            }
        }
        // 3) CSS
        String leaf = stripLeaf(body);
        if (!leaf.isEmpty() && !leaf.contains("<js>") && !leaf.startsWith("@js:")) {
            for (org.jsoup.nodes.Element e : defaultChain(leaf, base, html)) out.add(e);
        }
        return out;
    }

    /**
     * 裸属性：形如 name / author / lastVolName（只含字母数字下划线）。
     * CSS 叶子词（text / href / html 这些）要排除，否则会被当键去取 $.text。
     */
    private static boolean isBareKey(String s) {
        if (s == null || s.isEmpty() || s.length() > 40) return false;
        String k = s;
        // 允许尾部一个索引：cat[*] / cat[0] —— 裸键带索引也按 $.cat[*] 取
        java.util.regex.Matcher im = java.util.regex.Pattern
                .compile("^([A-Za-z_][A-Za-z0-9_]*)(\\[\\*\\]|\\[-?\\d+\\])$").matcher(k);
        if (im.matches()) k = im.group(1);
        if (!(Character.isLetter(k.charAt(0)) || k.charAt(0) == '_')) return false;
        for (int i = 1; i < k.length(); i++) {
            char c = k.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        String low = k.toLowerCase(java.util.Locale.ROOT);
        if (low.equals("text") || low.equals("textnode") || low.equals("owntext")
                || low.equals("html") || low.equals("all") || low.equals("href")
                || low.equals("src") || low.equals("content")) return false;
        return true;
    }

    /** 看起来是 HTML/CSS 形态的规则（含 @ 链、class./tag. 关键字、XPath、正则），不走 JSON 裸路径兜底 */
    private static boolean isHtmlRuleShape(String b) {
        if (b == null) return true;
        String t = b.trim();
        if (t.isEmpty()) return true;
        if (t.startsWith("@css:") || t.startsWith("@CSS:")) return true;
        if (t.startsWith("//") || t.startsWith("/html")) return true;
        if (t.startsWith("class.") || t.startsWith("tag.") || t.startsWith("id.") || t.startsWith("text.")) return true;
        if (t.contains("<js>") || t.startsWith("@js:")) return true;
        if (t.contains("@")) return true;
        if (t.contains("##")) return true;
        return false;
    }

    /** 把 @json: / $ / {{$x}} 归一成 JSONPath；不是 JSON 规则返回 null */
    private String jsonPathOf(String body) {
        if (body == null) return null;
        String b = body.trim();
        if (b.startsWith("@json:")) return b.substring(6).trim();
        if (b.startsWith("@JSON:")) return b.substring(6).trim();
        if (b.startsWith("{{") && b.endsWith("}}")) {
            String in = b.substring(2, b.length() - 2).trim();
            if (in.startsWith("$")) return in;
            return null;
        }
        if (b.startsWith("$")) return b;
        return null;
    }

    /** 从一个文本（JSON 或 JSON 数组）按路径取条目；取不到时把整体当一个条目 */
    private List<Object> jsonObjectsOf(String text, String path) {
        List<Object> out = new ArrayList<>();
        if (text == null) return out;
        String t = text.trim();
        Object root;
        try {
            root = t.startsWith("[") ? (Object) new org.json.JSONArray(t) : (Object) new JSONObject(t);
        } catch (Exception e) {
            return out;
        }
        String p = path == null ? "" : path.trim();
        if (p.isEmpty() || p.equals("$")) {
            if (root instanceof org.json.JSONArray) {
                org.json.JSONArray a = (org.json.JSONArray) root;
                for (int i = 0; i < a.length(); i++) out.add(unwrapJsonString(a.opt(i)));
            } else out.add(unwrapJsonString(root));
            return out;
        }
        return jsonObjects(root, p);
    }

    /**
     * @js 常把条目 map 成 JSON.stringify 后的字符串（如
     * (JSON.parse(result).data.list||[]).map(function(o){return lkBook(o)})）。
     * 整串当条目用会导致字段规则全都取不到（条目是 String 而不是对象），这里先解回对象。
     */
    private static Object unwrapJsonString(Object e) {
        if (!(e instanceof String)) return e;
        String t = ((String) e).trim();
        if (t.isEmpty()) return e;
        boolean obj = t.startsWith("{") && t.endsWith("}");
        boolean arr = t.startsWith("[") && t.endsWith("]");
        if (!obj && !arr) return e;
        try {
            return arr ? (Object) new org.json.JSONArray(t) : (Object) new JSONObject(t);
        } catch (Exception ig) {
            return e;
        }
    }

    /**
     * 从一条列表条目里取一个字段。对齐 Legado 的 AnalyzeRule：同一条规则既能用在
     * HTML 元素上，也能用在 JSON 对象上；规则可用 || 给多个候选。
     */
    public String fieldOf(String rule, Object item, String base) {
        if (rule == null || rule.trim().isEmpty() || item == null) return null;
        for (String cand : splitTop(rule.trim(), "||")) {
            if (cand == null) continue;
            String v = fieldOfOne(cand.trim(), item, base);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    private String fieldOfOne(String r, Object item, String base) {
        if (r == null || r.isEmpty()) return null;
        /*
         * 「前段取值 + @js: 段」：$.id@js:"https://www.ruochu.com/book/"+result、
         * @onclick@js:result.match(...)[1]、$.url@js:"http://.../"+result.split('/')[2]。
         * 对齐 Legado：规则按 @ 分段，遇到 @js: 段时把前面已取到的值作为 result 注入 JS。
         * 之前只认整条以 @js: 开头的写法，这类中段的全部取空（精选里 6 条卡在这）。
         */
        int jsAt = r.indexOf("@js:");
        if (jsAt > 0) {
            String left = r.substring(0, jsAt).trim();
            if (left.endsWith("@")) left = left.substring(0, left.length() - 1).trim();
            String code = r.substring(jsAt + 4).trim();
            if (!left.isEmpty() && !code.isEmpty()) {
                String res = fieldOfOne(left, item, base);
                String out = host.evalJs(code, res == null ? "" : res, base);
                if (out != null && !out.trim().isEmpty()) return out.trim();
            }
        }
        /*
         * 「前段取值 + <js>…</js>」和 @js: 是一回事，只是标签写法不同。
         * 优谁小说的 bookUrl 就是 $.bid<js>java.put('bid',result);'http://…/bid/'+result</js>：
         * 以前只认 @js:，这条整段当 JSONPath 去解析，取空。猫耳这类也多用 <js>。
         */
        int jsTag = r.indexOf("<js>");
        if (jsTag > 0) {
            String left = r.substring(0, jsTag).trim();
            if (left.endsWith("@")) left = left.substring(0, left.length() - 1).trim();
            java.util.List<String[]> steps = splitJsSteps(r.substring(jsTag));
            if (!left.isEmpty() && !steps.isEmpty() && "js".equals(steps.get(0)[0])) {
                String res = fieldOfOne(left, item, base);
                String out = host.evalJs(steps.get(0)[1], res == null ? "" : res, base);
                if (out != null && !out.trim().isEmpty()) return out.trim();
            }
        }
        // 整条是 {{...}} 模板：内层可以是规则（@ / $. / $[ / //）或 JS 表达式，
        // 之前只认 JSON 条目上的 $.，导致 188 处 {{@.book-cats@text}} 原样留在简介里。
        if (r.indexOf("{{") >= 0) {
            String tpl = expandInlineTpl(r, item, base);
            if (tpl != null) return tpl;
        }
        boolean isJson = (item instanceof JSONObject) || (item instanceof org.json.JSONArray);
        String body = r;
        String tail = null;
        int h = hashHashOutsideBraces(body);
        if (h > 0) { tail = body.substring(h); body = body.substring(0, h).trim(); }
        String v = null;
        if (isJson) {
            // 模板形态：https://x/book/{$.id}.html、...id={{$.id}}（Legado 里 JSON 条目都用这种写法）
            if (body.indexOf('{') >= 0 && body.indexOf('$') >= 0) {
                String filled = fillJsonTemplate(body, item);
                if (!filled.equals(body)) return filled;
            }
            String p = jsonPathOf(body);
            if (p != null) {
                List<Object> ls = jsonObjects(item, p);
                if (!ls.isEmpty() && ls.get(0) != null) v = cleanJsonVal(String.valueOf(ls.get(0)));
            } else if (isBareKey(body)) {
                /*
                 * 裸属性：name / author / bookUrl 这种不带 $. 前缀的写法。
                 * 真机上「轻之文库」同地址一次出 10 本、一次出 0 本就是这么来的：
                 * 两个同名源，一个写 $.name（出书），一个写 name（JSON 条目上取不到 → 全被判成无名书跳过）。
                 * 对齐 Legado：JSON 条目上的裸键按 $.key 取。
                 */
                List<Object> ls = jsonObjects(item, "$." + body);
                if (!ls.isEmpty() && ls.get(0) != null) v = cleanJsonVal(String.valueOf(ls.get(0)));
            } else if (body.startsWith("@js:") || body.contains("<js>")) {
                String code = body.startsWith("@js:") ? body.substring(4) : body;
                String json = item instanceof JSONObject ? ((JSONObject) item).toString() : item.toString();
                String sv = body.startsWith("@js:") ? host.evalJs(code, json, base)
                        : evalJsBlocks(code, base, json);
                if (sv != null && !sv.isEmpty()) v = sv;
            }
        } else if (item instanceof org.jsoup.nodes.Element) {
            v = pickElem(body, (org.jsoup.nodes.Element) item, base);
        } else if (item instanceof String) {
            String sv = String.valueOf(item).trim();
            if (!sv.isEmpty()) v = sv;
        }
        if (v != null && tail != null) v = applyReplace(tail, v);
        return v;
    }

    /**
     * {{...}} 内层求值。内层是规则（@ / $. / $[ / //）就按规则取，
     * 否则当 JS 表达式——对齐 Legado AnalyzeRule.kt:733 的 isRule 判定顺序。
     */
    private String inlineValue(String inner, Object item, String base) {
        if (inner == null || inner.isEmpty()) return "";
        // {{book.kind}} 这类：模板里的 book 前缀指当前条目对象（Legado 把条目绑在 book 上），
        // 映射成 $.kind 去取。企鹅浏览 / 松鹤阅读的 bookUrl 就是这么拼 resourceId 的。
        String inT = inner.trim();
        if (inT.startsWith("book.") && inT.length() > 5) inner = "$." + inT.substring(5).trim();
        if (isRuleToken(inner)) {
            if (item instanceof org.jsoup.nodes.Element) {
                String v = pickElem(inner, (org.jsoup.nodes.Element) item, base);
                return v == null ? "" : v;
            }
            if (item instanceof JSONObject || item instanceof org.json.JSONArray) {
                String v = fieldOfOne(inner, item, base);
                return v == null ? "" : v;
            }
            /*
             * 数据源是一段文本（详情页响应原文）时，内层规则要在「内容」上求值，不能拿去当 JS 跑。
             * 对齐 Legado 的内容优先判定（AnalyzeRule 里 isJSON = content.isJson()）。
             * 之前这里直接返回空串，于是 tocUrl 里的 {{$..resourceID}} / {{$.bookId}} 一律取空，
             * 目录地址被拼成 ?bookId= 空值、服务端回 422 —— 精选源里 15 条卡在这。
             */
            String txt = item == null ? "" : String.valueOf(item);
            String in = inner.trim();
            if (in.startsWith("$") || in.startsWith("@json:") || in.startsWith("@JSON:")) {
                List<Object> ls = jsonObjectsOf(txt, in);
                if (!ls.isEmpty() && ls.get(0) != null) return cleanJsonVal(String.valueOf(ls.get(0)));
                return "";
            }
            if (in.startsWith("@") || in.startsWith("//")) {
                String v2 = pickElem(in, org.jsoup.Jsoup.parse(txt, base == null ? "" : base), base);
                return v2 == null ? "" : v2;
            }
            return "";
        }
        String data = item instanceof org.jsoup.nodes.Element
                ? ((org.jsoup.nodes.Element) item).outerHtml()
                : (item == null ? "" : item.toString());
        String v = host.evalJs(inner, data, base);
        return v == null ? "" : v;
    }

    /** {{}} 之外第一个 "##"（要跳过 {{}} 内部的 ##，如 {{@@[name$=x]@content##T##🔸}}） */
    private int hashHashOutsideBraces(String s) {
        int depth = 0;
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.startsWith("{{", i)) { depth++; i++; continue; }
            if (s.startsWith("}}", i)) { depth = Math.max(0, depth - 1); i++; continue; }
            if (depth == 0 && s.charAt(i) == '#' && s.charAt(i + 1) == '#') return i;
        }
        return -1;
    }

    /**
     * 整条规则就是个 {{...}} 模板时展开它（可以带 ## 替换尾巴）。
     * 模板外面还有真选择器/文本时返回 null，交给原规则链——与 Legado 的 SourceRule 切分一致。
     */
    private String expandInlineTpl(String rule, Object item, String base) {
        if (rule == null || rule.indexOf("{{") < 0) return null;
        int h = hashHashOutsideBraces(rule);
        String body = h < 0 ? rule : rule.substring(0, h);
        String tail = h < 0 ? null : rule.substring(h);
        StringBuilder out = new StringBuilder();
        boolean pure = true;      // 除 {{}} 外全是空白 → 这就是个模板
        int i = 0;
        while (true) {
            int a = body.indexOf("{{", i);
            if (a < 0) {
                String rest = body.substring(i);
                if (!rest.trim().isEmpty()) pure = false;
                out.append(rest);
                break;
            }
            int b = body.indexOf("}}", a + 2);
            if (b < 0) {
                String rest = body.substring(i);
                if (!rest.trim().isEmpty()) pure = false;
                out.append(rest);
                break;
            }
            String gap = body.substring(i, a);
            if (!gap.trim().isEmpty()) pure = false;
            out.append(gap);
            out.append(inlineValue(body.substring(a + 2, b).trim(), item, base));
            i = b + 2;
        }
        if (!pure) return null;
        String v = out.toString().trim();
        if (tail != null) v = applyReplace(tail, v);
        return v;
    }

    /** 把规则里的 {{$.x}} / {$.x} 用当前 JSON 条目的值填掉；没模板就原样返回 */
    private String fillJsonTemplate(String tpl, Object item) {
        String out = fillTemplateOnce(tpl, "\\{\\{(\\$[^}]*)\\}\\}", item);
        out = fillTemplateOnce(out, "\\{(\\$[^}]*)\\}", item);
        return out;
    }

    private String fillTemplateOnce(String tpl, String regex, Object item) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(tpl);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String p = m.group(1).trim();
            String rep = "";
            try {
                /*
                 * 模板内层也允许带 ## 替换尾巴：{{$.docId##.*_}}（起点、企鹅、南极这一批都用它把
                 * docId 里的 "90000001_" 前缀去掉）。之前整串当 JSONPath 去取，必然取空。
                 */
                String tail = null;
                int hh = p.indexOf("##");
                if (hh > 0) { tail = p.substring(hh); p = p.substring(0, hh).trim(); }
                List<Object> ls = jsonObjects(item, p);
                rep = ls.isEmpty() || ls.get(0) == null ? "" : cleanJsonVal(String.valueOf(ls.get(0)));
                if (tail != null && !rep.isEmpty()) rep = applyReplace(tail, rep);
            } catch (Exception ignored) {}
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public String jsonItemValue(String rule, Object item) {
        String r = rule == null ? "" : rule.trim();
        if (r.startsWith("{{") && r.endsWith("}}")) {
            String inner = r.substring(2, r.length() - 2).trim();
            if (inner.startsWith("$")) {
                List<Object> v = jsonObjects(item, inner);
                return v.isEmpty() || v.get(0) == null ? "" : expandTemplate(cleanJsonVal(String.valueOf(v.get(0))));
            }
            return expandTemplate(r);
        }
        if (r.startsWith("$")) {
            List<Object> v = jsonObjects(item, r);
            return v.isEmpty() || v.get(0) == null ? "" : cleanJsonVal(String.valueOf(v.get(0)));
        }
        return null;   // 不是 JSON 规则
    }

    public String cleanJsonVal(String v) {
        if (v == null) return "";
        if (v.equals("null")) return "";
        return v;
    }

    public String expandTemplate(String t) {
        return t;
    }

    public List<String> splitJsonObjectsLite(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inS = false, inD = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && !inS) inD = !inD;
            else if (c == '\'' && !inD) inS = !inS;
            if (inS || inD) continue;
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) { out.add(text.substring(start, i + 1)); start = -1; } }
        }
        return out;
    }

    /**
     * 分页模板 <a,b,c>：按页码取第 N 项，page 从 1 开始，超出长度取最后一项
     * （对齐 Legado 的 RuleAnalyzer.replaceKeyPageJs / 规范里的 <a,b,c> 口径）。
     * 旧写法第 1 页整个删掉、后续页把 "a,b,c" 原样塞回去，179 条带分页模板的源翻页必挂。
     */
    public String pageAwareUrl(String u, int page) {
        String r = u;
        int guard = 0;
        int from = 0;
        while (from < r.length() && guard++ < 8) {
            int a = r.indexOf('<', from);
            if (a < 0) break;
            int b = r.indexOf('>', a);
            if (b < 0) break;
            String inside = r.substring(a + 1, b);
            // 含 {{}}/@js/空白/尖括号的不当分页模板（内嵌表达式里也可能有小于号），跳过继续找
            if (inside.isEmpty() || inside.contains("{") || inside.contains("}") || inside.contains("@")
                    || inside.contains("<") || inside.contains(" ") || inside.contains("\t")) {
                from = a + 1;
                continue;
            }
            String pick;
            String[] segs = inside.split("\\s*,\\s*");
            if (segs.length <= 1) {
                pick = inside;
            } else {
                int idx = page - 1;
                if (idx < 0) idx = 0;
                if (idx > segs.length - 1) idx = segs.length - 1;
                pick = segs[idx];
            }
            r = r.substring(0, a) + pick + r.substring(b + 1);
            from = a + pick.length();
        }
        return r.replace("{{page}}", String.valueOf(page));
    }

    public String stripLeaf(String rule) {
        String r = rule.trim();
        String[] segs = r.split("@");
        StringBuilder sb = new StringBuilder();
        for (String seg : segs) {
            if (isLeafToken(seg.trim())) break;
            if (sb.length() > 0) sb.append('@');
            sb.append(seg);
        }
        return sb.toString();
    }

    public int num(String k, int from) {
        try { return Integer.parseInt(k.substring(from).trim()); }
        catch (Exception e) { host.log("听书", "键解析失败 " + k); return -1; }
    }

    public IdxSpec findIndexSet(String rule) {
        IdxSpec s = new IdxSpec();
        String rus = rule == null ? "" : rule.trim();
        int len = rus.length();
        boolean curMinus = false;
        List<Integer> curList = new ArrayList<>();
        StringBuilder l = new StringBuilder();
        boolean head = len > 0 && rus.charAt(len - 1) == ']';
        if (head) {
            len--;
            while (len-- >= 0) {
                char rl = rus.charAt(len);
                if (rl == ' ') continue;
                if (rl >= '0' && rl <= '9') l.insert(0, rl);
                else if (rl == '-') curMinus = true;
                else {
                    Integer curInt = l.length() == 0 ? null
                            : (curMinus ? -Integer.parseInt(l.toString()) : Integer.parseInt(l.toString()));
                    if (rl == ':') {
                        curList.add(curInt);
                    } else {
                        if (curList.isEmpty()) {
                            if (curInt == null) break;
                            s.indexes.add(curInt);
                        } else {
                            int stepV = curList.size() >= 2 ? curList.get(0) : 1;
                            s.indexes.add(new Integer[]{curInt, curList.get(curList.size() - 1), stepV});
                            curList.clear();
                        }
                        if (rl == '!') {
                            s.split = '!';
                            do { rl = rus.charAt(--len); } while (len > 0 && rl == ' ');
                        }
                        if (rl == '[') { s.beforeRule = rus.substring(0, len); return s; }
                        if (rl != ',') break;
                    }
                    l.setLength(0);
                    curMinus = false;
                }
            }
        } else {
            while (len-- >= 0) {
                char rl = rus.charAt(len);
                if (rl == ' ') continue;
                if (rl >= '0' && rl <= '9') l.insert(0, rl);
                else if (rl == '-') curMinus = true;
                else {
                    if (rl == '!' || rl == '.' || rl == ':') {
                        s.indexDefault.add(curMinus ? -Integer.parseInt(l.toString()) : Integer.parseInt(l.toString()));
                        if (rl != ':') { s.split = rl; s.beforeRule = rus.substring(0, len); return s; }
                    } else break;
                    l.setLength(0);
                    curMinus = false;
                }
            }
        }
        s.split = ' ';
        s.beforeRule = rus;
        return s;
    }
}
