package com.mangjie.reader.a11y;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 单指落点判定：按 View 树命中，不再只靠行坐标算。
 *
 * 落点先找到最深的可见 View，再往上认：
 *  - 它若挂在列表容器（rowsBox）下 → 判为列表行，交给原有的行语义（播书名、双击执行）；
 *  - 否则找出带文字或描述的可点控件（书架顶部那个分组按钮条、页内按钮）→ 判为控件，
 *    念它的描述，抬手就 performClick。
 *
 * 这样不在列表里的东西也不会再被"贴到第一行"顶掉。
 */
public final class TouchExplorer {

    public static final int NONE = 0;
    public static final int ROW = 1;
    public static final int CONTROL = 2;

    /** 列表行数据由调用方提供：行数、行文字、行容器 */
    public interface Rows {
        int count();
        String label(int index);
        ViewGroup group();
    }

    public static final class Hit {
        public int type = NONE;
        public int row = -1;
        public View view;
        public String label = "";
    }

    private final Activity act;
    private final Rows rows;

    public TouchExplorer(Activity a, Rows r) {
        this.act = a;
        this.rows = r;
    }

    public Hit hit(float x, float y) {
        Hit h = new Hit();
        if (act == null) return h;
        View root = act.findViewById(android.R.id.content);
        if (root == null) return h;
        View deepest = deepest(root, x, y);
        if (deepest == null) return h;

        ViewGroup group = rows == null ? null : rows.group();
        if (group != null) {
            View v = deepest;
            while (v != null) {
                if (v.getParent() == group) break;
                if (v == root) { v = null; break; }
                v = (View) v.getParent();
            }
            if (v != null) {
                int idx = group.indexOfChild(v);
                if (idx >= 0 && idx < rows.count()) {
                    h.type = ROW;
                    h.row = idx;
                    h.view = v;
                    h.label = rows.label(idx);
                    return h;
                }
            }
        }

        View c = clickable(deepest, root);
        if (c != null) {
            h.type = CONTROL;
            h.view = c;
            h.label = describe(c);
        }
        return h;
    }

    /** 该 View 的读法：优先 contentDescription，其次文字 */
    public String describe(View v) {
        if (v == null) return "";
        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) return cd.toString().trim();
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) return t.toString().trim();
        }
        return "";
    }

    public void click(View v) {
        if (v == null) return;
        v.performClick();
    }

    private View deepest(View v, float x, float y) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        float vx = x - loc[0];
        float vy = y - loc[1];
        if (vx < 0 || vy < 0 || vx > v.getWidth() || vy > v.getHeight()) return null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View found = deepest(g.getChildAt(i), x, y);
                if (found != null) return found;
            }
        }
        return v;
    }

    private View clickable(View v, View root) {
        View cur = v;
        while (cur != null && cur != root) {
            if (cur.isClickable() && cur.getVisibility() == View.VISIBLE) {
                String d = describe(cur);
                if (!d.isEmpty()) return cur;
            }
            cur = (View) cur.getParent();
        }
        return null;
    }
}
