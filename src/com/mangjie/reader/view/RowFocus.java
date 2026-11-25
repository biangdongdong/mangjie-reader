package com.mangjie.reader.view;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mangjie.reader.model.Screen;

/**
 * 行焦点与可视区：红条落在哪一行、怎么滚进屏幕、整屏翻页、同一行文字不重复念。
 *
 * 外壳第二刀（2026-11-18）。只搬「焦点表现」这一块，按键怎么来、页面怎么建都还在主类。
 * 主类里 lastHighlightIdx 仍留着（建行时要初始化），这里通过 Host 读写同一个值。
 */
public class RowFocus {

    public interface Host {
        Screen curScreen();
        LinearLayout rowsBox();
        ScrollView scroll();
        LinearLayout menuBox();
        ScrollView menuScroll();
        boolean menuOn();
        int rowOffset();
        int lastHighlightIdx();
        void lastHighlightIdx(int v);
        void paintSelected(View v);
        int palBg();
        void focusRow(TextView v);
        int dp(float v);
        /** 列表对不上号时整页重建 */
        void render(boolean reset);
        void speak(String s);
    }

    private final Host h;

    /** 上一次念的行文字与时间：450 毫秒内同一句不再念第二遍 */
    private String lastSpokenLabel = "";
    private long lastSpokenAt = 0;

    public RowFocus(Host h) {
        this.h = h;
    }

    /** 焦点行涂红，只改上一行和这一行，不遍历全列表 */
    public void highlight() {
        Screen sc = h.curScreen();
        if (sc == null) return;
        LinearLayout box = h.rowsBox();
        if (box == null) return;
        int cur = sc.cur;
        int off = h.rowOffset();
        int child = cur - off;
        if (child < 0 || child >= box.getChildCount()) { h.render(false); return; }
        int last = h.lastHighlightIdx();
        int prev = last - off;
        if (last >= 0 && last != cur && prev >= 0 && prev < box.getChildCount()) {
            View p = box.getChildAt(prev);
            p.setBackgroundColor(h.palBg());
            p.invalidate();
        }
        View c = box.getChildAt(child);
        if (c == null) return;
        h.paintSelected(c);
        c.invalidate();
        if (c instanceof TextView) h.focusRow((TextView) c);
        h.lastHighlightIdx(cur);
    }

    /** 把第 idx 行滚进可视区；菜单开着时滚菜单那个列表 */
    public void ensure(int idx) {
        boolean inMenu = h.menuOn() && h.menuScroll() != null;
        ScrollView sv = inMenu ? h.menuScroll() : h.scroll();
        LinearLayout box = inMenu ? h.menuBox() : h.rowsBox();
        int off = inMenu ? 0 : h.rowOffset();
        if (sv == null || box == null) return;
        if (idx < off || idx >= off + box.getChildCount()) { h.render(false); return; }
        View v = box.getChildAt(idx - off);
        if (v == null) return;
        int top = v.getTop();
        int bottom = v.getBottom();
        int sy = sv.getScrollY();
        int height = sv.getHeight();
        if (height <= 0) return;
        int m = h.dp(6);
        if (top - sy < m) {
            sv.smoothScrollBy(0, top - sy - m);
        } else if (bottom - sy > height - m) {
            sv.smoothScrollBy(0, bottom - (sy + height - m));
        }
    }

    /** 整屏翻页：方向 1 往下、-1 往上；菜单开着时滚菜单那个列表，不然滚的是背后看不见的正文 */
    public void scrollBy(int dir) {
        boolean inMenu = h.menuOn() && h.menuScroll() != null;
        ScrollView sv = inMenu ? h.menuScroll() : h.scroll();
        if (sv == null) return;
        int height = sv.getHeight();
        if (height <= 0) height = h.dp(200);
        sv.scrollBy(0, dir * (height - h.dp(16)));
    }

    /** 念一行：同一句 450 毫秒内不重复，免得连按翻页时念两遍 */
    public void sayRow(String label) {
        long now = System.currentTimeMillis();
        if (label != null && label.equals(lastSpokenLabel) && now - lastSpokenAt < 450) return;
        lastSpokenLabel = label;
        lastSpokenAt = now;
        h.speak(label);
    }
}
