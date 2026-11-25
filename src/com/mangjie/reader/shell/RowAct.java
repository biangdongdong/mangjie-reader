package com.mangjie.reader.shell;

import com.mangjie.reader.model.Item;
import com.mangjie.reader.model.Screen;

/**
 * 行激活的前两段分派：先「主题 / 颜色 / 滑杆 / 菜单前缀」这类就地调整，再「页面导航」这类跳转。
 *
 * 外壳第三刀（2026-11-18）。只搬这两段，书架、书源、阅读页、设置四段仍留在主类，
 * 因为它们各自牵着几十个页面构造与网络动作，一次搬等于把动作层整个搬走。
 */
public class RowAct {

    public interface Host {
        boolean menuOn();
        /** 单击配色行＝往前一档 */
        void adjustColorRow(String k, int sign);
        void adjustFocusedRow(int step);
        void handleThemeKey(String k);
        void doFileMenuAction(String rest);
        void doReaderMenuAction(String rest);
        void hideMenuOverlay();
        void closeFloatMenu();
        void speak(String s);
        void goBack();
        /** 压一屏并整页重绘（不走 pushScreen 的手势清理，与原来逐处 stack.add + render 一致） */
        void showScreen(Screen sc);
        /** 走 pushScreen：清手势待判后压栈 */
        void push(Screen sc);
        Screen aboutScreen();
        void tapVersion();
        boolean devMode();
        void exitApp();
        Screen settingsScreen();
        Screen recordsScreen();
        Screen bookmarkOverviewScreen();
        Screen netBookScreen();
        boolean localMode();
        /** 菜单浮层是否开着（menuOn 或临时菜单页存在） */
        boolean floatMenuOpen();
        /** 去掉栈顶那个非阅读页（本地章节跳转前用） */
        void dropNonReaderTop();
        void openLocalChapter(int n);
    }

    private final Host h;

    public RowAct(Host h) {
        this.h = h;
    }

    /** 第一段：就地调整类，处理了返回 true */
    public boolean pre(String k, Screen sc, Item it) {
        if (k.startsWith("bg:") || k.startsWith("fg:") || "hl:shape".equals(k) || "hl:alpha".equals(k)
                || "hl:grad".equals(k) || "hl:gloss".equals(k) || "hl:shadow".equals(k)
                || "hl:r".equals(k) || "hl:g".equals(k) || "hl:b".equals(k)) {
            h.adjustColorRow(k, 1);          // 单击＝往前一档，到头环回
            return true;
        }
        if ("rv:rate".equals(k) || "rv:pitch".equals(k) || "sp:rate".equals(k) || "sp:pitch".equals(k)
                || "rv:double".equals(k) || "sp:double".equals(k) || "sfx:vol".equals(k) || "hap:level".equals(k)) {
            h.adjustFocusedRow(5);           // 单击＝往前一格，到头环回
            return true;
        }
        if (k.startsWith("hl:") || k.startsWith("tc:")) {
            h.handleThemeKey(k);
            return true;
        }
        if (k.startsWith("fmenu:")) { h.doFileMenuAction(k.substring(6)); return true; }
        if (k.startsWith("rmenu:")) { h.doReaderMenuAction(k.substring(6)); return true; }
        if (h.menuOn()) {      // 菜单里点了个普通动作：先收浮层，再照原逻辑执行
            boolean isBack = "nav:back".equals(k);
            h.hideMenuOverlay();
            if (isBack) { h.closeFloatMenu(); return true; }
        }
        return false;
    }

    /** 第二段：导航类跳转，处理了返回 true */
    public boolean nav(String k, Screen sc, Item it) {
        if ("sec".equals(k)) { h.speak(it.label); return true; }   // 分组标题行：只报段名，不当选项
        if ("about".equals(k)) { h.showScreen(h.aboutScreen()); return true; }
        if (k.startsWith("about:")) {
            if ("ver".equals(k.substring(6))) { h.tapVersion(); return true; }
            h.speak("未知操作"); return true;
        }
        if ("go:dev".equals(k) && !h.devMode()) { h.speak("开发者选项未开启"); return true; }
        if ("task:now".equals(k)) { h.speak(com.mangjie.reader.tools.TaskProgress.describe()); return true; }
        if ("exit".equals(k)) { h.exitApp(); return true; }
        if ("settings".equals(k)) { h.showScreen(h.settingsScreen()); return true; }
        if ("nav:back".equals(k)) {
            if (h.floatMenuOpen()) { h.closeFloatMenu(); return true; }
            h.goBack();
            return true;
        }
        if ("records".equals(k)) { h.push(h.recordsScreen()); return true; }
        if ("rec:bms".equals(k)) { h.showScreen(h.bookmarkOverviewScreen()); return true; }
        if ("netbook".equals(k)) { h.push(h.netBookScreen()); return true; }
        if (k.startsWith("lc:go:")) {
            int n = -1;
            try { n = Integer.parseInt(k.substring(6)); } catch (Exception ignored) {}
            if (n >= 0 && h.localMode()) {
                h.dropNonReaderTop();
                h.openLocalChapter(n);
            }
            return true;
        }
        return false;
    }
}
