/*
 * 本文件的部分实现参照「开源阅读 Legado」（https://github.com/LegadoTeam/legado）。
 * Legado：Copyright (C) 2019-2023 gedoor，GNU General Public License v3.0。
 * 本工程对其的使用方式与修改说明见仓库根目录 NOTICE，完整条款见 LICENSE。
 */
package com.mangjie.reader.reader;

import com.mangjie.reader.model.Item;
import com.mangjie.reader.model.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节索引：从正文里认章标题，目录页、阅读进度旁的章位置都靠它。
 *
 * 规则选择来自开源阅读（Legado）：内置 26 条 TXT 目录规则里挑命中最优的一条，
 * 挑不出（或规则资源缺失）才退回下面这条兜底正则。
 */
public final class ChapterIndex {

    /** 兜底规则：章/节/回/卷/篇/部/集/幕 ＋ 序章/楔子/前言… ＋ Chapter */
    public static final Pattern PATTERN = Pattern.compile(
            "^[ \t\u3000]{0,4}(第[0-9零一二三四五六七八九十百千万]+[章节回卷篇部集幕]"
                    + "|序章|序言|楔子|引子|前言|后记|尾声|附录|番外"
                    + "|[Cc]hapter\\s+[0-9ivxlcdmIVXLCDM]+"
                    + "|序(?=[\\s:：、.．·\\-—]|$))", Pattern.MULTILINE);

    /** 一行标题最多这么多字，超了不当章标题 */
    private static final int MAX_TITLE_LEN = 40;

    private static String cachedKey = null;
    private static Pattern cachedPattern = null;

    /** 上一次算出来的章标题句子号；同一份句子表直接复用，别每次点目录都全量扫一遍 */
    private static java.util.List<String> cachedSent = null;
    private static int cachedSentSize = -1;
    private static List<Integer> cachedIndices = null;

    private ChapterIndex() {}

    /** 换书或正文变化后调用，清掉规则缓存 */
    public static void reset() {
        cachedKey = null;
        cachedPattern = null;
        cachedSent = null;
        cachedSentSize = -1;
        cachedIndices = null;
    }

    /** 章标题所在句号（同一套「空行不算、超过 40 字不算」的过滤） */
    public static List<Integer> indices(List<String> sentences) {
        // 同一份句子表（同一个 List 引用、长度没变）直接返回上次结果：
        // 开书时后台预跑一遍，用户再点目录就不用等主线程全量扫了。
        if (sentences != null && sentences == cachedSent && sentences.size() == cachedSentSize
                && cachedIndices != null) {
            return cachedIndices;
        }
        List<Integer> out = new ArrayList<Integer>();
        if (sentences == null || sentences.isEmpty()) return out;

        // 每句前面补一个全角空格，让「(?<=[　\s])」这类后向断言也能命中行首
        StringBuilder blob = new StringBuilder(sentences.size() * 8);
        List<int[]> spans = new ArrayList<int[]>(sentences.size());
        for (int i = 0; i < sentences.size(); i++) {
            int start = blob.length();   // 含行首的全角空格，规则常从缩进处开始命中
            blob.append('\u3000').append(sentences.get(i) == null ? "" : sentences.get(i).trim());
            int end = blob.length() - 1;  // 不含行尾换行，否则行首命中会被算到上一句
            spans.add(new int[]{start, end});
            blob.append('\n');
        }

        Pattern p = patternFor(blob.toString(), sentences);
        Matcher m = p.matcher(blob);
        while (m.find()) {
            int at = m.start();
            int lo = 0, hi = spans.size() - 1, found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int[] s = spans.get(mid);
                if (at < s[0]) hi = mid - 1;
                else if (at > s[1]) lo = mid + 1;
                else { found = mid; break; }
            }
            if (found < 0) continue;
            String title = sentences.get(found) == null ? "" : sentences.get(found).trim();
            if (title.isEmpty() || title.length() > MAX_TITLE_LEN) continue;
            if (!out.contains(found)) out.add(found);
        }
        if (sentences != null) { cachedSent = sentences; cachedSentSize = sentences.size(); cachedIndices = out; }
        return out;
    }

    private static Pattern patternFor(String blob, List<String> sentences) {
        String key = sentences.size() + "#" + blob.hashCode() + "#" + blob.length();
        if (cachedKey != null && cachedKey.equals(key) && cachedPattern != null) return cachedPattern;
        Pattern picked = TocRules.pick(blob);
        cachedKey = key;
        cachedPattern = picked != null ? picked : PATTERN;
        return cachedPattern;
    }

    /** 「第3/12章 第三章 出发」；没有可识别的章返回「（无可识别章节）」 */
    public static String posTag(List<String> sentences, int sentIdx) {
        List<Integer> idx = indices(sentences);
        if (idx.isEmpty()) return "（无可识别章节）";
        int cur = 0;
        String name = "";
        for (int k = 0; k < idx.size(); k++) {
            if (idx.get(k) <= sentIdx) {
                cur = k + 1;
                name = sentences.get(idx.get(k)).trim();
            }
        }
        return "第" + cur + "/" + idx.size() + "章 " + name;
    }

    /** 阅读菜单里「目录」后面挂的当前章名（截前 8 字，带括号；没有则空串） */
    public static String currentTag(List<String> sentences, int cur) {
        List<Integer> idx = indices(sentences);
        String name = null;
        for (Integer i : idx) {
            if (i <= cur) name = sentences.get(i).trim();
        }
        if (name == null) return "";
        if (name.length() > 8) name = name.substring(0, 8);
        return "（" + name + "）";
    }

    /** 每句字数（去掉句尾换行），prefix 累加用于算章节字数 */
    private static int[] prefixChars(List<String> sentences) {
        int[] pre = new int[sentences.size() + 1];
        for (int i = 0; i < sentences.size(); i++) {
            String t = sentences.get(i);
            int n = t == null ? 0 : t.trim().length();
            pre[i + 1] = pre[i] + n;
        }
        return pre;
    }

    /** 目录页：顶部两条是上一章/下一章快捷项，条目 key 为 chj:句号 */
    public static Screen screen(List<String> sentences, int curSent) {
        List<Integer> idx = indices(sentences);
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("上一章", "chp:prev"));
        items.add(new Item("下一章", "chp:next"));
        if (idx.isEmpty()) {
            items.add(new Item("（这本书没有可识别的章节标题）"));
            Screen empty = new Screen("目录", items);
            empty.cur = 0;
            return empty;
        }
        int[] pre = prefixChars(sentences);
        int curItem = 0;
        for (int k = 0; k < idx.size(); k++) if (idx.get(k) <= curSent) curItem = k;
        for (int k = 0; k < idx.size(); k++) {
            int at = idx.get(k);
            int end = (k + 1 < idx.size()) ? idx.get(k + 1) : sentences.size();
            int chars = pre[end] - pre[at];
            String label = sentences.get(at).trim() + "（" + chars + "字）";
            if (k == curItem) label = "✔ " + label;
            items.add(new Item(label, "chj:" + at));
        }
        Screen sc = new Screen("目录（第" + (curItem + 1) + "/" + idx.size() + "章 "
                + sentences.get(idx.get(curItem)).trim() + "）", items);
        sc.cur = curItem + 2;   // 前面多了两项快捷项
        return sc;
    }

    /** 当前句所在章序号（0 基），没有章标题返回 -1 */
    public static int chapterAt(List<String> sentences, int sentIdx) {
        List<Integer> idx = indices(sentences);
        if (idx.isEmpty()) return -1;
        int cur = 0;
        for (int k = 0; k < idx.size(); k++) if (idx.get(k) <= sentIdx) cur = k;
        return cur;
    }

    /** 全书字数（按句加总） */
    public static int totalChars(List<String> sentences) {
        if (sentences == null) return 0;
        int[] pre = prefixChars(sentences);
        return pre[sentences.size()];
    }

    /** 跳到上一章/下一章：返回目标句号，没有目标时返回 -1 */
    public static int neighborChapter(List<String> sentences, int sentIdx, boolean next) {
        List<Integer> idx = indices(sentences);
        if (idx.isEmpty()) return -1;
        int cur = chapterAt(sentences, sentIdx);
        int target = next ? cur + 1 : cur - 1;
        if (target < 0 || target >= idx.size()) return -1;
        return idx.get(target);
    }
}
