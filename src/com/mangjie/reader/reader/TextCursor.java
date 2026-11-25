package com.mangjie.reader.reader;

import java.util.List;

/**
 * 一个字级光标：把浓前三段的句子按顺序编成一条连续字序列，
 * 支持「全局字序 ⇄ 第几句第几字」双向换算。
 * 只负责本页（本章已载入的那一批句子）的换算，跨章靠外层另处理。
 */
public final class TextCursor {

    private final List<String> sentences;
    private final int[] starts;      // 每句的首字在全局序列里的下标
    private final int total;

    public TextCursor(List<String> sentences) {
        this.sentences = sentences;
        int n = sentences.size();
        starts = new int[n];
        int acc = 0;
        for (int i = 0; i < n; i++) {
            starts[i] = acc;
            String s = sentences.get(i);
            acc += (s == null ? 0 : s.length());
        }
        total = acc;
    }

    public int total() { return total; }

    public int sentenceCount() { return sentences.size(); }

    /** 第 sent 句第 off 字 → 全局字序；off 超出句长时夹到句内 */
    public int indexOf(int sent, int off) {
        if (sent < 0) return 0;
        if (sent >= sentences.size()) return total <= 0 ? 0 : total - 1;
        String s = sentences.get(sent);
        int len = s == null ? 0 : s.length();
        int o = off < 0 ? 0 : (off >= len ? (len == 0 ? 0 : len - 1) : off);
        return starts[sent] + o;
    }

    /** 全局字序 → {句号, 句内偏移}；越界夹到两端 */
    public int[] locate(int idx) {
        if (sentences.isEmpty()) return new int[]{0, 0};
        if (idx <= 0) return new int[]{0, 0};
        if (idx >= total) {
            int last = sentences.size() - 1;
            String s = sentences.get(last);
            return new int[]{last, s == null || s.length() == 0 ? 0 : s.length() - 1};
        }
        int lo = 0, hi = sentences.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            int end = mid == sentences.size() - 1 ? total : starts[mid + 1];
            if (idx < starts[mid]) hi = mid - 1;
            else if (idx >= end) lo = mid + 1;
            else return new int[]{mid, idx - starts[mid]};
        }
        return new int[]{0, 0};
    }

    /** 取该字的字符；越界返回空串 */
    public String charAt(int idx) {
        if (idx < 0 || idx >= total) return "";
        int[] p = locate(idx);
        String s = sentences.get(p[0]);
        if (s == null || p[1] >= s.length()) return "";
        return String.valueOf(s.charAt(p[1]));
    }

    /** 取 [from, to] 之间的原文（首尾都含） */
    public String slice(int from, int to) {
        int a = Math.max(0, Math.min(from, to));
        int b = Math.min(total - 1, Math.max(from, to));
        if (total <= 0 || b < a) return "";
        int[] pa = locate(a);
        int[] pb = locate(b);
        StringBuilder sb = new StringBuilder();
        for (int i = pa[0]; i <= pb[0] && i < sentences.size(); i++) {
            String s = sentences.get(i);
            if (s == null) continue;
            int begin = i == pa[0] ? Math.min(pa[1], s.length()) : 0;
            int end = i == pb[0] ? Math.min(pb[1] + 1, s.length()) : s.length();
            if (end > begin) sb.append(s, begin, end);
        }
        return sb.toString();
    }

    /** 第 sent 句原文 */
    public String sentence(int sent) {
        if (sent < 0 || sent >= sentences.size()) return "";
        String s = sentences.get(sent);
        return s == null ? "" : s;
    }
}
