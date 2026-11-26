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
    private static final Pattern IMG_SRC = Pattern.compile("src\\s*=\\s*['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INDENT = Pattern.compile("\\s*\\n+\\s*");
    private static final Pattern LEAD = Pattern.compile("^[\\n\\s\\u3000]+");
    private static final Pattern TAIL = Pattern.compile("[\\n\\s\\u3000]+$");
    /** 占位符，避免图片标签被当普通标签删掉 */
    private static final String HOLD = "[[[IMG:";

    private HtmlFormat() {}

    public static String format(String html) {
        if (html == null || html.isEmpty()) return "";
        String s = html;
        s = ENTITY_NBSP.matcher(s).replaceAll(" ");
        s = ENTITY_SPACE.matcher(s).replaceAll(" ");
        s = ENTITY_ZERO.matcher(s).replaceAll("");
        s = BLOCK_TAG.matcher(s).replaceAll("\n");
        s = COMMENT.matcher(s).replaceAll("");
        s = OTHER_TAG.matcher(s).replaceAll("");
        s = INDENT.matcher(s).replaceAll("\n　　");
        s = LEAD.matcher(s).replaceAll("　　");
        s = TAIL.matcher(s).replaceAll("");
        return s;
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
            Matcher s = IMG_SRC.matcher(m.group());
            String src = s.find() ? s.group(1).trim() : "";
            if (src.startsWith("data:")) {
                held.append("");   // data: 图片太长，直接丢掉
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
