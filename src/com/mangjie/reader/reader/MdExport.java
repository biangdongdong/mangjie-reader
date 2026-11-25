package com.mangjie.reader.reader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 摘抄导出：把 摘抄.txt 里的行按书归类，拼成一份 Markdown。
 *
 * 摘抄行格式（excerptSentence 写的）：《书名》第N句：正文
 */
public final class MdExport {

    private MdExport() {}

    /** 解析成 {书名, 句号, 正文} 三元组；格式不对的行跳过，不硬猜 */
    public static List<String[]> parseExcerpts(String raw) {
        List<String[]> out = new ArrayList<String[]>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (!t.startsWith("《")) continue;
            int r = t.indexOf('》');
            if (r <= 1) continue;
            String book = t.substring(1, r);
            String rest = t.substring(r + 1);
            if (!rest.startsWith("第")) continue;
            int c = rest.indexOf('：');
            if (c < 0) continue;
            String idx = rest.substring(1, c);
            String body = rest.substring(c + 1);
            out.add(new String[]{book, idx, body});
        }
        return out;
    }

    public static Map<String, List<String[]>> groupByBook(List<String[]> entries) {
        Map<String, List<String[]>> map = new LinkedHashMap<String, List<String[]>>();
        for (String[] e : entries) {
            List<String[]> one = map.get(e[0]);
            if (one == null) { one = new ArrayList<String[]>(); map.put(e[0], one); }
            one.add(e);
        }
        return map;
    }

    /**
     * 生成 Markdown：一本书一节，条目按原顺序。
     * @param nbFav 网上阅读收藏（可为空），元素是「书名　来源」
     */
    public static String buildMd(List<String[]> entries, String stamp, List<String> nbFav) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 盲界阅读 · 摘抄\n\n");
        sb.append("导出时间：").append(stamp == null ? "" : stamp).append("\n\n");
        sb.append("共 ").append(entries == null ? 0 : entries.size()).append(" 条\n\n");
        if (entries != null && !entries.isEmpty()) {
            for (Map.Entry<String, List<String[]>> en : groupByBook(entries).entrySet()) {
                sb.append("## 《").append(en.getKey()).append("》\n\n");
                for (String[] e : en.getValue()) {
                    sb.append("- 第").append(e[1]).append("：").append(e[2]).append("\n");
                }
                sb.append("\n");
            }
        }
        if (nbFav != null && !nbFav.isEmpty()) {
            sb.append("## 网上阅读收藏\n\n");
            for (String one : nbFav) {
                if (one == null || one.trim().isEmpty()) continue;
                sb.append("- ").append(one.trim()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
