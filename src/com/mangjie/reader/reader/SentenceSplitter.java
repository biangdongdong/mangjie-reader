package com.mangjie.reader.reader;

import java.util.ArrayList;
import java.util.List;

/**
 * 正文切句。
 *
 * 两级：
 *  一级＝句末标点（。！？…；!?;）切一句，紧跟的引号/括号并入上一句；单个换行当空格
 *         （mobi 硬换行不能把一句拆开），空行才切段。
 *  二级＝一级切出来的句子如果太长（44 字以上且有两个以上句中标点），再按句内标点
 *         （，、：,）切成几片。中文小说一整段常常只有一个句号、中间全是逗号，
 *         不细分的话 TalkBack 摸到的是整段、复制出来也是整段。
 *
 * parents 记住每一片属于第几个一级句子，切句规则改了以后旧进度和书签还能按
 * 「第 N 个一级句子」映射回去，不至于整本跳位置。
 */
public final class SentenceSplitter {

    private static final String ENDERS = "。！？…；!?;";
    private static final String TAILS = "”’\"'）)》」』】";
    /** 二级切分用的标点（句末标点已在上一级切过，这里只管句中） */
    private static final String SUB_PUNCT = "，、：,";
    /** 一级句子超过这个长度才做二级切分；章标题一般很短，正好不受影响 */
    private static final int SUB_MAX_LEN = 44;
    /** 二级切出来的片段短于这个长度就并回上一片，免得满屏「是的，」这种碎片 */
    private static final int SUB_MIN_LEN = 8;

    /** 单句上限：再长就硬切，免得超了 TTS 单次输入上限后整段念不出来 */
    private static final int HARD_MAX_LEN = 400;
    /** 硬切时每片的目标长度 */
    private static final int HARD_CHUNK = 180;

    private SentenceSplitter() {}

    /**
     * 超长句硬切：有的源正文去掉标点，一整段成一「句」，几千字丢给 TTS 会被截断，
     * 后半段静默丢掉。这里按长度切，尽量落在标点上。
     */
    private static List<String> hardCut(String s) {
        List<String> one = new ArrayList<String>();
        if (s == null || s.length() <= HARD_MAX_LEN) { if (s != null) one.add(s); return one; }
        List<String> out = new ArrayList<String>();
        int from = 0;
        while (from < s.length()) {
            int to = Math.min(s.length(), from + HARD_CHUNK);
            if (to < s.length()) {
                int dot = -1;
                for (int i = to - 1; i > from + HARD_CHUNK / 2; i--) {
                    char c = s.charAt(i);
                    if (ENDERS.indexOf(c) >= 0 || SUB_PUNCT.indexOf(c) >= 0) { dot = i + 1; break; }
                }
                if (dot > from) to = dot;
            }
            String t = s.substring(from, to).trim();
            if (!t.isEmpty()) out.add(t);
            from = to;
        }
        return out;
    }

    public static List<String> split(String text) {
        return split(text, null);
    }

    /**
     * 切句，同时把每片所属的一级句子序号写进 parents（可为 null）。
     * 返回的列表顺序就是正文顺序，parents 与之一一对应。
     */
    public static List<String> split(String text, List<Integer> parents) {
        text = TextUtil.stripInvisible(text);   // 零宽字符、残留实体先清掉，否则会把句子从中间切断
        List<String> base = splitBase(text);
        List<String> out = new ArrayList<String>();
        if (parents != null) parents.clear();
        for (int pi = 0; pi < base.size(); pi++) {
            String s = base.get(pi);
            List<String> parts;
            if (s.length() > SUB_MAX_LEN && hasSubPunct(s)) {
                parts = splitSub(s);
            } else {
                parts = new ArrayList<String>();
                parts.add(s);
            }
            for (String p : parts) {
                if (p.isEmpty()) continue;
                for (String q : hardCut(p)) {      // 没有标点的超长段在这儿兜底切开
                    out.add(q);
                    if (parents != null) parents.add(pi);
                }
            }
        }
        return out;
    }

    private static boolean hasSubPunct(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (SUB_PUNCT.indexOf(s.charAt(i)) >= 0) n++;
        }
        return n >= 2;      // 只有一个逗号的长句切了没意义
    }

    /** 把一句长句按句中标点切成几片，标点留在这一片的末尾 */
    private static List<String> splitSub(String s) {
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c);
            if (SUB_PUNCT.indexOf(c) >= 0 && sb.length() >= SUB_MIN_LEN && i + 1 < s.length()) {
                String t = sb.toString().trim();
                if (!t.isEmpty()) out.add(t);
                sb.setLength(0);
            }
        }
        String t = sb.toString().trim();
        if (!t.isEmpty()) {
            if (out.size() > 0 && t.length() < SUB_MIN_LEN) {
                // 尾巴太短，并回上一片，读起来不至于断在半句
                out.set(out.size() - 1, out.get(out.size() - 1) + t);
            } else {
                out.add(t);
            }
        }
        return out;
    }

    /** 这一行像不像章标题：与目录识别用同一套正则，长度上限也一致 */
    private static boolean looksLikeChapter(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.isEmpty() || s.length() > 40) return false;
        try {
            return ChapterIndex.PATTERN.matcher(s).find();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从 from 开始取下一行（最多 60 字），用来判断下一行是不是章标题 */
    private static String nextLine(String text, int from) {
        int end = from;
        int limit = Math.min(text.length(), from + 60);
        while (end < limit && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
        return text.substring(from, end);
    }

    /** 一级切分：原来的规则，只是在章标题边界上多断开一次 */
    private static List<String> splitBase(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        StringBuilder sb = new StringBuilder();
        int newlines = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') continue;
            if (c == '\n') {
                newlines++;
                if (newlines >= 2) {
                    String t = sb.toString().trim();
                    if (!t.isEmpty()) out.add(t);
                    sb.setLength(0);
                    newlines = 0;
                } else {
                    // 单个换行：普通情况当作空格（排版换行不能把一句拆开），
                    // 但如果这一行的上下正好是章标题，就必须断开——
                    // 否则「第二篇」会被并进上一句，目录里那一章直接消失。
                    String cur = sb.toString().trim();
                    boolean curIsTitle = looksLikeChapter(cur);
                    boolean nextIsTitle = looksLikeChapter(nextLine(text, i + 1));
                    if (curIsTitle || nextIsTitle) {
                        if (!cur.isEmpty()) out.add(cur);
                        sb.setLength(0);
                        newlines = 0;
                    }
                }
                continue;
            }
            if (newlines > 0) {
                if (sb.length() > 0) {
                    char prev = sb.charAt(sb.length() - 1);
                    if (ENDERS.indexOf(prev) < 0 && "，,、：:".indexOf(prev) < 0
                            && c != '，' && c != '。' && c != '！' && c != '？'
                            && c != '；' && c != '、' && c != '：') {
                        sb.append(' ');
                    }
                }
                newlines = 0;
            }
            sb.append(c);
            if (ENDERS.indexOf(c) >= 0) {
                int j = i + 1;
                while (j < text.length() && TAILS.indexOf(text.charAt(j)) >= 0) {
                    sb.append(text.charAt(j));
                    j++;
                    i = j - 1;
                }
                String t = sb.toString().trim();
                if (!t.isEmpty()) out.add(t);
                sb.setLength(0);
            }
        }
        String t = sb.toString().trim();
        if (!t.isEmpty()) out.add(t);
        return out;
    }
}
