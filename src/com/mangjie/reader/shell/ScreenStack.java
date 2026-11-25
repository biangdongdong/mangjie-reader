package com.mangjie.reader.shell;

import com.mangjie.reader.model.Screen;

import java.util.List;

/**
 * 屏幕栈：压栈、退栈、取栈顶、发现页重开时的清栈。
 *
 * 从 MainActivity 搬出来的第一刀外壳（滚动式模块化，2026-11-18）。只搬「栈」这一层，
 * 渲染、焦点、按键分派还在主类，等这一刀在真机上稳住再动下一刀。
 *
 * 主类里那 269 处 `stack.` 直接操作没有改，Host 给的就是同一个 List 引用。
 */
public class ScreenStack {

    public interface Host {
        /** 主类那个 stack 本身，不复制 */
        List<Screen> stack();
        void render(boolean reset);
        void speak(String msg);
        void log(String tag, String msg);
        /** 手势识别器里还有没判完的动作，换页前要清掉 */
        void cancelGestures();

        /** 浮层：0 没有，1 对话框，2 输入页，3 阅读菜单 */
        int overlay();
        /** 关掉对应浮层，文案由主类那边出（对话框与输入页要念「已取消」，菜单不念） */
        void closeOverlay(int kind);

        void onLeaveReader();

        /** 退回设置类页面时要按新状态重建一屏，认不出就返回 null */
        Screen rebuildFor(String key, int cur);

        void exitApp();

        /** 发现页重开：重新载书源、重算发现列表 */
        void reloadExplore();
    }

    private final Host h;

    public ScreenStack(Host h) {
        this.h = h;
    }

    public void push(Screen sc) {
        h.cancelGestures();
        h.stack().add(sc);
        h.render(true);
    }

    public Screen top() {
        List<Screen> st = h.stack();
        return st.isEmpty() ? null : st.get(st.size() - 1);
    }

    /**
     * 退一层。浮层优先：弹窗、输入页、阅读菜单开着时只关浮层，不动页面栈。
     */
    public void back() {
        h.cancelGestures();
        int ov = h.overlay();
        if (ov != 0) {
            Screen t = top();
            String title = t == null ? "?" : t.title;
            h.log("返回", (ov == 3 ? "关闭菜单 " : ov == 2 ? "关闭输入页 " : "关闭对话框 ") + title);
            h.closeOverlay(ov);
            return;
        }
        List<Screen> st = h.stack();
        Screen t = top();
        if (t == null) { h.exitApp(); return; }
        h.log("返回", "离开页面 " + t.title
                + (st.size() > 1 ? "，回到 " + st.get(st.size() - 2).title : "，退出程序"));
        if (t.reader) h.onLeaveReader();
        if (st.size() > 1) {
            st.remove(st.size() - 1);
            // 返回设置页/外观页时重建一次：里面的开、关、字号都是拼进文字里的，旧对象会显示成旧状态
            Screen back = st.get(st.size() - 1);
            if (back != null && back.key != null) {
                Screen fresh = h.rebuildFor(back.key, back.cur);
                if (fresh != null) {
                    fresh.cur = Math.max(0, Math.min(back.cur, fresh.items.size() - 1));
                    st.set(st.size() - 1, fresh);
                }
            }
            h.render(true);
        } else {
            h.exitApp();
        }
    }

    /**
     * 发现页重开。发现页上面可能压着「按书源分组筛选」「按源名筛选」这类临时页，
     * 以前只弹栈顶一屏，于是栈里同时有新旧两个发现页，返回要连按好几次。
     */
    public void reopenExplore(String say) {
        List<Screen> st = h.stack();
        int exAt = -1;
        for (int i = st.size() - 1; i >= 0; i--) {
            Screen s = st.get(i);
            if (s != null && "bsrcExplore".equals(s.key)) { exAt = i; break; }
        }
        if (exAt >= 0) {
            int cut = st.size() - exAt;
            while (st.size() > exAt) st.remove(st.size() - 1);
            if (cut > 1) h.log("发现", "重建发现页，弹掉上面 " + (cut - 1) + " 屏");
        }
        h.reloadExplore();
        if (say != null && !say.isEmpty()) h.speak(say);
    }
}
