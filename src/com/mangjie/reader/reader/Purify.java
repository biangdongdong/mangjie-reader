package com.mangjie.reader.reader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 替换净化：按「匹配##替换」的正则表清洗正文，去掉站点广告、求票、域名残留这些垃圾行。
 *
 * 规则来源两处：内置默认规则（下面这几条），外加 App 目录 `净化/` 下任意 `*.json`：
 * [
 *   {"name":"去掉广告行","pattern":"(?m)^.*(请记住|本站域名).*$","replacement":"","regex":true,"enable":true}
 * ]
 * replacement 为空即整行删掉；regex 为 false 时按纯字符串替换。
 */
public final class Purify {

    /** 默认规则：只删明显是站点垃圾的整行，不碰正文里的字 */
    private static final String[][] DEFAULT_RULES = {
            // 隐藏字符与残留实体：零宽空格、窄空格、软连字符、HTML 注释
            {"(?i)(?:&nbsp;|&#160;|&#xa0;)+", " "},
            {"(?i)&(?:ensp|emsp|thinsp|zwnj|zwj|shy);", ""},
            {"[\\u200B\\u200C\\u200D\\u200E\\u200F\\u2060\\uFEFF\\u00AD]", ""},
            {"<!--[\\s\\S]*?-->", ""},
            // 站点广告、求票、催更
            {"(?m)^[ \\t　]*(?:.*(?:请记住|记住本站|本站域名|本站地址|手机用户请|最新网址|永久域名|"
             + "求月票|求推荐票|求收藏|求订阅|求打赏|求鲜花|求评价票|本章未完|点击下一页|书友们|"
             + "笔趣阁|笔趣楼|顶点小说|笔下文学|思路客|燃文|新书包网|去读书|零点看书|书趣阁).*)$(?:\\r?\\n)?", ""},
            // 单独一行的网址 / 关注公众号
            {"(?m)^[ \\t　]*(?:https?://|www\\.)[^\\s，。]*[ \\t　]*$(?:\\r?\\n)?", ""},
            {"(?m)^[ \\t　]*(?:关注|搜索)(?:微信)?公众号[^\\n]{0,20}$(?:\\r?\\n)?", ""},
            // 「上一章 目录 下一章」这种导航行
            {"(?m)^[ \\t　]*上一章[ \\t　]+(?:目录|章节目录|返回目录)[ \\t　]+下一章[ \\t　]*$(?:\\r?\\n)?", ""},
            // 三个以上连续空行压成一个
            {"\\n[ \\t　]*\\n[ \\t　]*\\n+", "\n\n"},
    };

    /** 内置规则的人话名字，顺序与 DEFAULT_RULES 一一对应 */
    private static final String[] DEFAULT_NAMES = {
            "连续空格与 &nbsp; 归一成普通空格",
            "&ensp; 一类残留实体删掉",
            "零宽字符与软连字符删掉",
            "HTML 注释整段删掉",
            "广告与求票行整行删掉",
            "单独一行的网址删掉",
            "关注公众号引流行删掉",
            "上一章 目录 下一章 导航行删掉",
            "三个以上连续空行压成一个",
    };

    private static final List<Rule> RULES = new ArrayList<Rule>();
    private static boolean loadedDefaults = false;

    private Purify() {}

    /** 加载内置规则＋App 目录 `净化/` 下的用户规则；读不到用户规则就用内置的 */
    public static void load(File dir) {
        RULES.clear();
        if (!loadedDefaults) loadedDefaults = true;
        for (String[] r : DEFAULT_RULES) add("内置", r[0], r[1], true);
        if (dir == null) return;
        if (!dir.exists()) dir.mkdirs();
        if (!dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".json")) continue;
            try {
                JSONArray arr = new JSONArray(readAll(f));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    if (!o.optBoolean("enable", true)) continue;
                    String p = o.optString("pattern", "");
                    if (p.isEmpty()) continue;
                    add(o.optString("name", f.getName() + "#" + (i + 1)), p,
                            o.optString("replacement", ""), o.optBoolean("regex", true));
                }
            } catch (Throwable ignored) {
                // 一条坏规则不影响别的
            }
        }
    }

    private static void add(String name, String pattern, String replacement, boolean regex) {
        try {
            RULES.add(new Rule(name, Pattern.compile(regex ? pattern : Pattern.quote(pattern)), replacement));
        } catch (Throwable ignored) {
            // 正则写错就跳过这条
        }
    }

    /** 清洗正文；返回命中条数，正文写回 out[0] 用不到，直接读返回值里的文本 */
    public static Result clean(String text) {
        if (text == null || text.isEmpty() || RULES.isEmpty()) return new Result(text, 0, 0);
        String out = text;
        int hits = 0;
        for (Rule r : RULES) {
            try {
                java.util.regex.Matcher m = r.pattern.matcher(out);
                int before = out.length();
                out = m.replaceAll(r.replacement);
                if (out.length() != before) hits++;
            } catch (Throwable ignored) {
                // 替换出问题就保留原文
            }
        }
        return new Result(out, RULES.size(), hits);
    }

    public static int ruleCount() {
        return RULES.size();
    }

    /** 内置规则的名字列表：给「内置规则」页逐条念 */
    public static String[] builtinNames() { return DEFAULT_NAMES.clone(); }

    public static final class Result {
        public final String text;
        public final int ruleCount;
        public final int hitCount;

        Result(String text, int ruleCount, int hitCount) {
            this.text = text;
            this.ruleCount = ruleCount;
            this.hitCount = hitCount;
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

    private static final class Rule {
        final String name;
        final Pattern pattern;
        final String replacement;

        Rule(String name, Pattern pattern, String replacement) {
            this.name = name;
            this.pattern = pattern;
            this.replacement = replacement;
        }
    }
}
