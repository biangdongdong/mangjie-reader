package com.mangjie.reader.reader;

import com.mangjie.reader.model.Item;
import com.mangjie.reader.model.Screen;

import java.util.ArrayList;
import java.util.List;

/** 书签：逗号分隔的句子序号，配上正文预览生成列表页 */
public final class Bookmarks {

    private Bookmarks() {}

    public static List<Integer> parse(String raw) {
        List<Integer> out = new ArrayList<Integer>();
        if (raw == null || raw.isEmpty()) return out;
        for (String t : raw.split(",")) {
            try {
                out.add(Integer.parseInt(t.trim()));
            } catch (Exception ignored) {}
        }
        return out;
    }

    public static String join(List<Integer> bms) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bms.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(bms.get(i));
        }
        return sb.toString();
    }

    /**
     * 书签列表页：没有书签时只有一行提示；有书签时第一行是「清空全部」。
     * 正文预览截前 18 个字，避免一行撑破 320 宽屏。
     */
    public static Screen screen(List<Integer> bms, List<String> sentences) {
        List<Item> items = new ArrayList<Item>();
        if (bms.isEmpty()) {
            items.add(new Item("（还没有书签）"));
        } else {
            items.add(new Item("清空全部书签", "bmclr"));
        }
        for (Integer idx : bms) {
            String body = idx != null && idx >= 0 && sentences != null && idx < sentences.size()
                    ? sentences.get(idx) : "";
            if (body.length() > 18) body = body.substring(0, 18) + "…";
            items.add(new Item("第" + ((idx == null ? 0 : idx) + 1) + "句：" + body, "bmj:" + idx));
        }
        return new Screen("书签", items);
    }
}
