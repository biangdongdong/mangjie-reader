package com.mangjie.reader.reader;

import java.util.regex.Pattern;

/**
 * 朗读读法净化：只影响「念出来是什么」，不改正文显示。
 *
 * 抄的是开源阅读「朗读替换」的思路，做了精简版：
 * 1. 括号里的旁白注释（（笑）、（停顿）、(拍桌)）不念
 * 2. 装饰符号串（※☆★◆◇■□●○▲△ 等）换成停顿
 * 3. 破折号、省略号、连续标点压成一次停顿
 * 4. 首尾空白与行内多余空格清掉
 */
public final class SpeakClean {

    private static final Pattern NOTE_CN = Pattern.compile("\\s*[（(][^）)]{0,24}[）)]");
    private static final Pattern NOTE_BRACKET = Pattern.compile("\\s*[【\\[][^】\\]]{0,24}[】\\]]");
    private static final Pattern MARK_RUN = Pattern.compile("[※☆★✦✧◆◇■□●○▲△▽▼♦♠♣♥]+");
    private static final Pattern DASH_RUN = Pattern.compile("[—–]{2,}|-{2,}|…{2,}|\\.{3,}");
    private static final Pattern PUNCT_RUN = Pattern.compile("[，,、]{2,}");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0\\u3000]{2,}");

    private SpeakClean() {}

    /** 清洗一句；洗空了就退回原文，避免整句被吞掉不念 */
    public static String clean(String src) {
        if (src == null) return "";
        String s = TextUtil.stripInvisible(src);
        s = NOTE_CN.matcher(s).replaceAll("，");
        s = NOTE_BRACKET.matcher(s).replaceAll("，");
        s = MARK_RUN.matcher(s).replaceAll("，");
        s = DASH_RUN.matcher(s).replaceAll("，");
        s = PUNCT_RUN.matcher(s).replaceAll("，");
        s = SPACES.matcher(s).replaceAll(" ");
        s = s.replaceAll("([。！？])，", "$1");      // 「。，」这种叠标点只留句末
        s = s.replaceAll("^[，、；：\\s]+", "");       // 句首被替换出来的逗号去掉
        s = s.replaceAll("[，、；：\\s—]+$", "");      // 句尾同理
        s = s.replace("，。", "。").replace("，！", "！").replace("，？", "？");
        // 1.0.67：切句会把一段对话切成只剩半个引号（第491句结尾就是一个孤立的 ”）。
        // 实测这种句子引擎开了口却永远不回 onDone，同批上一句 4.4 秒正常——转发类引擎
        // 很可能把没配对的引号当成「话还没说完」在等后续。朗读本来也听不出引号，
        // 干脆全部去掉（中文引号与英文双引号），别给引擎留这种可挑刺的东西
        s = s.replaceAll("[\u201C\u201D\u2018\u2019\u300C\u300D\u300E\u300F\"]+", "");
        s = s.trim();
        if (s.isEmpty() || s.replaceAll("[，。！？、；：“”‘’\\s]", "").isEmpty()) return src.trim();
        return s;
    }

    /** 是否改动了（日志用） */
    public static boolean changed(String src, String out) {
        return src != null && !src.trim().equals(out);
    }
}
