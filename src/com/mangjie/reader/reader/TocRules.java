/*
 * 本文件的部分实现参照「开源阅读 Legado」（https://github.com/LegadoTeam/legado）。
 * Legado：Copyright (C) 2019-2023 gedoor，GNU General Public License v3.0。
 * 本工程对其的使用方式与修改说明见仓库根目录 NOTICE，完整条款见 LICENSE。
 */
package com.mangjie.reader.reader;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * TXT 目录规则：规则表来自开源阅读（Legado）的 txtTocRule.json，26 条、默认启用 12 条。
 * 选规则不取第一条命中，而是逐条统计章数后打分选最优，避免「目录」「卷」被误认成章。
 */
public final class TocRules {

    private static final String TAG = "TocRules";
    /** 两条匹配之间正文不足这么多字，判为卷而不是章 */
    private static final int VOLUME_GAP = 100;
    /** 后面的规则要领先当前最好这么多章才替换 */
    private static final int OVER_RULE_COUNT = 2;
    /** 章数超过这个数就直接收工 */
    private static final int ENOUGH_CHAPTERS = 70;

    /** 内置规则表之外的两条补充规则：符号分隔的篇/卷体、中文数字篇卷体 */
    private static final String[][] EXTRA_RULES = {
            {"补充 符号 分隔", "^[ \\t　]{0,4}[◇◆■□●○▲△※☆★✦✧][^。，,；;]{1,30}$"},
            {"补充 篇/卷 中文数字", "^[ \\t　]{0,4}[一二三四五六七八九十百千]{1,4}[篇卷部].{0,20}$"},
    };

    private static String lastRuleName = "（未选）";
    private static int lastRuleCount = 0;

    private static boolean loaded = false;
    private static java.io.File userDir = null;
    private static final List<Rule> RULES = new ArrayList<Rule>();

    private TocRules() {}

    /** 应用启动时调用一次；读不到资源就退化为内置单条规则 */
    public static synchronized void init(Context ctx) {
        if (loaded) return;
        loaded = true;
        try {
            InputStream in = ctx.getResources().openRawResource(
                    ctx.getResources().getIdentifier("txt_toc_rule", "raw", ctx.getPackageName()));
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !o.optBoolean("enable", false)) continue;
                String rule = o.optString("rule", "");
                if (rule.isEmpty()) continue;
                try {
                    RULES.add(new Rule(o.optString("name", "规则" + i), Pattern.compile(rule, Pattern.MULTILINE)));
                } catch (PatternSyntaxException e) {
                    Log.w(TAG, "规则语法错误：" + o.optString("name", "") + " " + e.getMessage());
                }
            }
            for (String[] r : EXTRA_RULES) {
                RULES.add(new Rule(r[0], Pattern.compile(r[1], Pattern.MULTILINE)));
            }
            addUserRules();
            Log.i(TAG, "已载入目录规则 " + RULES.size() + " 条");
        } catch (Throwable e) {
            Log.w(TAG, "载入目录规则失败 " + e);
        }
    }

    /** 指定用户自定义规则目录（`目录规则/自定义.json` 放这儿） */
    public static void setUserDir(java.io.File dir) {
        userDir = dir;
    }

    /** 读用户自定义规则追加到表里 */
    private static void addUserRules() {
        if (userDir == null) return;
        java.io.File f = UserRules.fileIn(userDir);
        for (UserRules.Entry e : UserRules.load(f, "rule")) {
            if (!e.enable) continue;
            try {
                RULES.add(new Rule("用户·" + e.name, Pattern.compile(e.pattern, Pattern.MULTILINE)));
            } catch (Throwable t) {
                Log.w(TAG, "用户规则语法错误：" + e.name);
            }
        }
    }

    /** 重新读一遍规则表（设置页「重新载入」用） */
    public static synchronized void reload(android.content.Context ctx) {
        RULES.clear();
        loaded = false;
        init(ctx);
    }

    public static int count() {
        return RULES.size();
    }

    /** 规则名列表：给「规则列表」页逐条念出来用 */
    public static synchronized java.util.List<String> names() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (Rule r : RULES) out.add(r.name);
        return out;
    }

    /**
     * 选出切章最多的那条规则；选不出返回 null，调用方用自己的兜底规则。
     */
    public static Pattern pick(String text) {
        if (text == null || text.isEmpty() || RULES.isEmpty()) return null;
        Pattern best = null;
        String bestName = null;
        int maxNum = -1;
        for (Rule r : RULES) {
            int csNum = 0, numE = 0;
            int lastEnd = 0;
            boolean first = true;
            java.util.regex.Matcher m = r.pattern.matcher(text);
            while (m.find()) {
                int gap = m.start() - lastEnd;
                if (first) {
                    csNum++;
                    first = false;
                } else if (gap < VOLUME_GAP) {
                    numE++;
                } else {
                    csNum++;
                }
                lastEnd = m.end();
            }
            // 有效章数不到卷噪声三倍的规则视为误判
            if (csNum >= numE * 3 && csNum > maxNum + OVER_RULE_COUNT) {
                maxNum = csNum;
                best = r.pattern;
                bestName = r.name;
                if (maxNum > ENOUGH_CHAPTERS) break;
            }
        }
        lastRuleName = bestName == null ? "（未选）" : bestName;
        lastRuleCount = maxNum < 0 ? 0 : maxNum;
        return best;
    }

    /** 最近一次选中的规则名与章数（排查用） */
    public static String lastRuleName() {
        return lastRuleName;
    }

    public static int lastRuleCount() {
        return lastRuleCount;
    }

    /** 规则名（调试/设置页展示用） */
    public static String describeCount() {
        return "规则 " + RULES.size() + " 条";
    }

    private static final class Rule {
        final String name;
        final Pattern pattern;

        Rule(String name, Pattern pattern) {
            this.name = name;
            this.pattern = pattern;
        }
    }
}
