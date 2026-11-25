package com.mangjie.reader.shelves;

import com.mangjie.reader.model.Item;
import com.mangjie.reader.model.Screen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 书架上那一本的操作菜单：置顶、移出、去网上找这本。
 *
 * 从 MainActivity 搬出来的（滚动式模块化，2026-11-18）。这份菜单只能从书架点到，
 * 人已经在书架上了，所以不给「加入收藏」，只有「移出」。
 */
public class ShelfMenu {

    public interface Host {
        List<String> readList(String key);
        void saveList(String key, List<String> items);
        void removeFromList(String key, String path);
        void speak(String msg);
        /** 退一层，并把书架那一页按最新数据重建 */
        void backToShelf();
    }

    private final Host h;

    public ShelfMenu(Host h) {
        this.h = h;
    }

    /** 长按书架上的一本书：出一页操作菜单（键盘、手势、读屏都走同一套） */
    public Screen menu(String path) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("置顶到书架最前", "bks:top:" + path));
        // 本地书只能从书架里点到这份菜单，人已经在书架上了，所以不给「加入收藏」，只给「移出」
        items.add(new Item("移出", "bks:unfav:" + path));
        items.add(new Item("在网上找这本书（拿书名去所有书源搜）", "bks:findnet:" + path));
        items.add(new Item("返回上一层", "nav:back"));
        return new Screen(new File(path).getName(), items).k("shelfBookMenu");
    }

    /**
     * 处理 bks: 后面的半截。键格式是 op:/绝对路径 —— op 后面是冒号，不是斜杠；
     * 曾经按斜杠切过，结果 "unfav:" 永远匹配不上 "unfav"，点了没反应（1.0.52 修的就是这个）。
     */
    public void handle(String rest) {
        int colon = rest.indexOf(':');
        if (colon < 0) return;
        String op = rest.substring(0, colon);
        String path = rest.substring(colon + 1);
        if ("top".equals(op) || "fav".equals(op)) {
            List<String> fav = h.readList("favList");
            fav.remove(path);
            fav.add(0, path);
            h.saveList("favList", fav);
            h.speak("已置顶 " + new File(path).getName());
        } else if ("unfav".equals(op)) {
            boolean hadFav = h.readList("favList").contains(path);
            boolean hadRecent = h.readList("recentList").contains(path);
            if (hadFav) h.removeFromList("favList", path);
            if (hadRecent) h.removeFromList("recentList", path);
            h.speak(hadFav || hadRecent ? "已从书架移除" : "这本不在书架上");
        } else {
            return;
        }
        h.backToShelf();
    }
}
