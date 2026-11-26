/*
 * 本文件的部分实现参照「开源阅读 Legado」（https://github.com/LegadoTeam/legado）。
 * Legado：Copyright (C) 2019-2023 gedoor，GNU General Public License v3.0。
 * 本工程对其的使用方式与修改说明见仓库根目录 NOTICE，完整条款见 LICENSE。
 */
package com.mangjie.reader.reader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 转纯文本：书源规则里的 java.htmlFormat(str) 用的就是它。
 *
 * 规则照开源阅读（Legado）的 HtmlFormatter：
 *   &nbsp; 连续 → 空格；&ensp;/&emsp; → 空格；&thinsp;/&zwnj;/&zwj/零宽类 → 删掉
 *   块级标签（div/p/br/hr/h1-h9/article/dd/dl）→ 换行；其余标签全删；HTML 注释删掉
 *   多余空行压成一个，段首补两个全角空格（跟正文版式一致）
 *
 * formatKeepImg 会保留图片，改成 <img src="绝对地址">；相对地址按传进来的 base 补全。
 */
public final class HtmlFormat {

    private static final Pattern ENTITY_NBSP = Pattern.compile("(&nbsp;)+");
    private static final Pattern ENTITY_SPACE = Pattern.compile("(&ensp;|&emsp;)");
    private static final Pattern ENTITY_ZERO = Pattern.compile("(&thinsp;|&zwnj;|&zwj;|\u2009|\u200C|\u200D)");
    private static final Pattern BLOCK_TAG = Pattern.compile("</?(?:div|p|br|hr|h\\d|article|dd|dl)[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = Pattern.compile("<!--[^>]*-->");
    private static final Pattern OTHER_TAG = Pattern.compile("</?[a-zA-Z]+(?=[ >])[^<>]*>");
    private static final Pattern IMG_TAG = Pattern.compile("<img[^>]*>", Pattern.CASE_INSENSITIVE);
    /** src=，前面不能是 data- 里的 src（否则 data-src 会被当成 src 取到） */
    private static final Pattern IMG_SRC = Pattern.compile("(?<![\\w-])src\\s*=\\s*['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE);
    /** 懒加载图：data-src / data-original / data-srcset / data-srcset */
    private static final Pattern IMG_LAZY = Pattern.compile("data-(?:src|original|srcset)\\s*=\\s*['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INDENT = Pattern.compile("\\s*\\n+\\s*");
    private static final Pattern LEAD = Pattern.compile("^[\\n\\s\\u3000]+");
    private static final Pattern TAIL = Pattern.compile("[\\n\\s\\u3000]+$");
    /** 占位符，避免图片标签被当普通标签删掉 */
    private static final String HOLD = "[[[IMG:";

    private HtmlFormat() {}

    public static String format(String html) {
        return formatText(html, "　　");
    }

    /** 简介专用：段落不缩进（对齐 Legado 的 formatIntro） */
    public static String formatIntro(String html) {
        return formatText(html, "");
    }

    private static String formatText(String html, String indent) {
        if (html == null || html.isEmpty()) return "";
        String s = normalizeEscapedLineBreaks(html);
        s = ENTITY_NBSP.matcher(s).replaceAll(" ");
        s = ENTITY_SPACE.matcher(s).replaceAll(" ");
        s = ENTITY_ZERO.matcher(s).replaceAll("");
        s = BLOCK_TAG.matcher(s).replaceAll("\n");
        s = COMMENT.matcher(s).replaceAll("");
        s = OTHER_TAG.matcher(s).replaceAll("");
        s = INDENT.matcher(s).replaceAll(indent.isEmpty() ? "\n" : "\n　　");
        s = LEAD.matcher(s).replaceAll(indent);
        s = TAIL.matcher(s).replaceAll("");
        return s;
    }

    /**
     * 有些源把换行转义成字面 \r\n / \n / \r 返回；不转回来的话整段会被当成一行，
     * 朗读时一句念到底。顺序要对：先 \r\n，再 \n，最后 \r（Legado 的做法）。
     */
    private static String normalizeEscapedLineBreaks(String s) {
        if (s.indexOf('\\') < 0) return s;
        return s.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n");
    }

    /** 保留图片版；base 为空时图片地址原样保留 */
    public static String formatKeepImg(String html, String base) {
        if (html == null || html.isEmpty()) return "";
        List<String> imgSrcs = new ArrayList<String>();
        StringBuilder held = new StringBuilder();
        Matcher m = IMG_TAG.matcher(html);
        int last = 0;
        while (m.find()) {
            held.append(html, last, m.start());
            String src = imgSrcOf(m.group());
            if (src.startsWith("data:")) {
                held.append("");   // data: 图片太长，直接丢掉
            } else if (src.isEmpty()) {
                held.append("");   // 取不到地址的图不留空壳
            } else {
                imgSrcs.add(absUrl(base, src));
                held.append(HOLD).append(imgSrcs.size() - 1).append("]]]");
            }
            last = m.end();
        }
        held.append(html.substring(last));
        String out = format(held.toString());
        for (int i = 0; i < imgSrcs.size(); i++) {
            out = out.replace(HOLD + i + "]]]", "<img src=\"" + imgSrcs.get(i) + "\">");
        }
        return out;
    }

    public static String formatKeepImg(String html) {
        return formatKeepImg(html, "");
    }

    /**
     * 取图片地址：先看真 src，取不到再看 data-src / data-original / srcset 第一段。
     * 懒加载站的 <img> 往往只有 data-src，只认 src 的话配图全丢。
     */
    private static String imgSrcOf(String tag) {
        Matcher s = IMG_SRC.matcher(tag);
        if (s.find()) {
            String v = s.group(1).trim();
            if (!v.isEmpty() && !v.startsWith("data:")) return v;
        }
        Matcher d = IMG_LAZY.matcher(tag);
        while (d.find()) {
            String v = d.group(1).trim();
            if (v.isEmpty() || v.startsWith("data:")) continue;
            int comma = v.indexOf(',');           // srcset: "a.jpg 1x, b.jpg 2x"
            if (comma > 0) v = v.substring(0, comma).trim();
            int space = v.indexOf(' ');
            if (space > 0) v = v.substring(0, space).trim();
            return v;
        }
        return "";
    }

    private static String absUrl(String base, String src) {
        if (src == null || src.isEmpty()) return "";
        if (src.startsWith("http://") || src.startsWith("https://")) return src;
        if (src.startsWith("//")) return "https:" + src;
        if (base == null || base.isEmpty()) return src;
        if (src.startsWith("/")) {
            try {
                java.net.URL u = new java.net.URL(base);
                return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + src;
            } catch (Exception e) { return src; }
        }
        try {
            return new java.net.URL(new java.net.URL(base), src).toString();
        } catch (Exception e) { return src; }
    }
}
