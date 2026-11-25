package com.mangjie.reader.shell;

import com.mangjie.reader.model.Screen;

/**
 * 光标移动：上下一行、键盘整屏翻页。
 *
 * 外壳第四刀（2026-11-18）。只管「从第几行走第几行」，按键怎么来在输入层，
 * 页面怎么建在各自构造方法里。
 */
public class Cursor {

    public interface Host {
        Screen curScreen();
        void highlightSelection();
        void ensureRowVisible(int idx);
        void log(String tag, String msg);
        /** 每次光标落定后的计时钩子（阅读时长统计用） */
        void tick();
        void speakRow(String label);
        void speak(String s);
        /** 阅读页里手动移光标：先停自动朗读 */
        boolean autoPlaying();
        void stopReading();
        /** 菜单浮层一行占多高、正文列表一行占多高，用来算一屏几行 */
        int menuRowStep();
        int bodyRowStep();
    }

    private final Host h;

    public Cursor(Host h) {
        this.h = h;
    }

    public void moveTo(int idx) {
        Screen sc = h.curScreen();
        if (sc == null || sc.items == null) return;
        if (idx < 0 || idx >= sc.items.size()) return;
        if (sc.reader && h.autoPlaying()) h.stopReading();
        sc.cur = idx;
        h.highlightSelection();
        h.ensureRowVisible(idx);
        h.log("自身光标", "程序主动切到第" + idx + "行　" + sc.items.get(idx).label);
        h.log("播报", "朗读第" + idx + "行");
        h.tick();
        h.speakRow(sc.items.get(idx).label);
    }

    /** 键盘翻一屏：按一屏能放几行算，到边界出声 */
    public void page(int dir) {
        Screen cs = h.curScreen();
        if (cs == null || cs.items == null) return;
        int total = cs.items.size();
        if (total <= 0) { h.speak("这个页面没有内容"); return; }
        int step = step();
        int target = cs.cur + dir * step;
        if (target < 0) target = 0;
        if (target >= total) target = total - 1;
        if (target == cs.cur) { h.speak(dir > 0 ? "已经是最后一项" : "已经是第一项"); return; }
        moveTo(target);
    }

    private int step() {
        int menu = h.menuRowStep();
        if (menu > 0) return menu;
        int body = h.bodyRowStep();
        if (body > 0) return body;
        return 8;
    }
}
