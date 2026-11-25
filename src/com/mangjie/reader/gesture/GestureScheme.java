package com.mangjie.reader.gesture;

import android.content.SharedPreferences;

/**
 * 手势方案表：手势 ID → App 内动作。
 * 六档：盲界手势（默认，现状）、天坦读屏、保益悦听、TalkBack、华为屏幕朗读、自定义。
 * 所有动作都只在 App 内部生效，不调 Home、通知栏、最近任务这些系统动作。
 */
public final class GestureScheme {

    // 方案
    public static final String MANGJIE = "mangjie";
    public static final String TATANS = "tatans";
    public static final String BAOYI = "baoyi";
    public static final String TALKBACK = "talkback";
    public static final String HUAWEI = "huawei";
    public static final String CUSTOM = "custom";

    public static final String[] KEYS = {MANGJIE, TATANS, BAOYI, TALKBACK, HUAWEI, CUSTOM};
    public static final String[] NAMES = {"盲界手势（默认）", "天坦读屏", "保益悦听", "TalkBack", "华为屏幕朗读", "自定义"};

    // 动作
    public static final String NONE = "none";
    public static final String NAV_NEXT = "nav_next";         // 焦点下一项
    public static final String NAV_PREV = "nav_prev";         // 焦点上一项
    public static final String SCROLL_FWD = "scroll_fwd";     // 往后滚一屏
    public static final String SCROLL_BACK = "scroll_back";   // 往回滚一屏
    public static final String ACTIVATE = "activate";         // 执行当前项
    public static final String TTS_TOGGLE = "tts_toggle";     // 暂停/继续朗读
    public static final String TTS_NEXT = "tts_next";         // 从下一句起连续读
    public static final String BACK = "back";                 // 返回上一层
    public static final String HOME = "home";                 // 回 App 主菜单
    public static final String PAGE_NEXT = "page_next";       // 大单位往前：书架下一组、阅读下一页
    public static final String PAGE_PREV = "page_prev";       // 大单位往后
    public static final String CHAPTER_NEXT = "chapter_next"; // 阅读下一章
    public static final String CHAPTER_PREV = "chapter_prev"; // 阅读上一章

    public static final String[] ACTIONS = {
            NONE, NAV_NEXT, NAV_PREV, SCROLL_FWD, SCROLL_BACK, ACTIVATE,
            TTS_TOGGLE, TTS_NEXT, BACK, HOME,
            PAGE_NEXT, PAGE_PREV, CHAPTER_NEXT, CHAPTER_PREV
    };

    public static final String[] ACTION_NAMES = {
            "不做事", "焦点下一项", "焦点上一项", "向后滚一屏", "向前滚一屏", "执行当前项",
            "暂停或继续朗读", "从下一句连续读", "返回上一层", "回主菜单",
            "下一组／下一屏", "上一组／上一屏", "下一章", "上一章"
    };

    /** 全部手势 ID（说明页与自定义页都按这个顺序列） */
    public static final String[] GESTURES = {
            TouchGestures.TWO_TAP, TouchGestures.TWO_DOUBLE_TAP, TouchGestures.TWO_TRIPLE_TAP,
            TouchGestures.TWO_SWIPE_LEFT, TouchGestures.TWO_SWIPE_RIGHT,
            TouchGestures.TWO_SWIPE_UP, TouchGestures.TWO_SWIPE_DOWN,
            TouchGestures.THREE_SWIPE_LEFT, TouchGestures.THREE_SWIPE_RIGHT,
            TouchGestures.THREE_SWIPE_UP, TouchGestures.THREE_SWIPE_DOWN
    };

    public static String gestureName(String id) {
        switch (id) {
            case TouchGestures.TWO_TAP: return "双指单击";
            case TouchGestures.TWO_DOUBLE_TAP: return "双指双击";
            case TouchGestures.TWO_TRIPLE_TAP: return "双指三击";
            case TouchGestures.TWO_SWIPE_LEFT: return "双指左滑";
            case TouchGestures.TWO_SWIPE_RIGHT: return "双指右滑";
            case TouchGestures.TWO_SWIPE_UP: return "双指上滑";
            case TouchGestures.TWO_SWIPE_DOWN: return "双指下滑";
            case TouchGestures.THREE_SWIPE_LEFT: return "三指左滑";
            case TouchGestures.THREE_SWIPE_RIGHT: return "三指右滑";
            case TouchGestures.THREE_SWIPE_UP: return "三指上滑";
            case TouchGestures.THREE_SWIPE_DOWN: return "三指下滑";
            default: return id;
        }
    }

    public static String actionName(String action) {
        for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i].equals(action)) return ACTION_NAMES[i];
        return "不做事";
    }

    public static String schemeName(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return NAMES[i];
        return NAMES[0];
    }

    /** 取某方案下某个手势对应的动作；自定义没有单独设过的，跟随盲界手势 */
    public static String action(SharedPreferences prefs, String scheme, String id) {
        String s = scheme;
        if (CUSTOM.equals(s)) {
            String v = prefs.getString("gestureCustom_" + id, null);
            if (v != null) return v;
            s = MANGJIE;
        }
        switch (s) {
            case TATANS:
                switch (id) {
                    case TouchGestures.TWO_TAP: return ACTIVATE;
                    case TouchGestures.TWO_DOUBLE_TAP: return ACTIVATE;
                    case TouchGestures.TWO_TRIPLE_TAP: return TTS_NEXT;
                    case TouchGestures.TWO_SWIPE_LEFT: return PAGE_NEXT;
                    case TouchGestures.TWO_SWIPE_RIGHT: return PAGE_PREV;
                    case TouchGestures.TWO_SWIPE_UP: return SCROLL_FWD;
                    case TouchGestures.TWO_SWIPE_DOWN: return SCROLL_BACK;
                    case TouchGestures.THREE_SWIPE_UP: return SCROLL_FWD;
                    case TouchGestures.THREE_SWIPE_DOWN: return SCROLL_BACK;
                    default: return NONE;
                }
            case BAOYI:
                switch (id) {
                    case TouchGestures.TWO_DOUBLE_TAP: return ACTIVATE;
                    case TouchGestures.TWO_SWIPE_LEFT: return PAGE_NEXT;
                    case TouchGestures.TWO_SWIPE_RIGHT: return PAGE_PREV;
                    case TouchGestures.TWO_SWIPE_UP: return SCROLL_FWD;
                    case TouchGestures.TWO_SWIPE_DOWN: return SCROLL_BACK;
                    default: return NONE;
                }
            case TALKBACK:
                switch (id) {
                    case TouchGestures.TWO_TAP: return TTS_TOGGLE;
                    case TouchGestures.TWO_DOUBLE_TAP: return TTS_TOGGLE;
                    case TouchGestures.TWO_TRIPLE_TAP: return TTS_NEXT;
                    case TouchGestures.TWO_SWIPE_LEFT: return PAGE_NEXT;
                    case TouchGestures.TWO_SWIPE_RIGHT: return PAGE_PREV;
                    case TouchGestures.TWO_SWIPE_UP: return SCROLL_FWD;
                    case TouchGestures.TWO_SWIPE_DOWN: return SCROLL_BACK;
                    default: return NONE;
                }
            case HUAWEI:
                switch (id) {
                    case TouchGestures.TWO_SWIPE_LEFT: return PAGE_NEXT;
                    case TouchGestures.TWO_SWIPE_RIGHT: return PAGE_PREV;
                    case TouchGestures.TWO_SWIPE_UP: return SCROLL_FWD;
                    case TouchGestures.TWO_SWIPE_DOWN: return SCROLL_BACK;
                    default: return NONE;
                }
            case MANGJIE:
            default:
                switch (id) {
                    // 盲界手势＝现状：双指上下滚一屏、双指左右切组（书架），双击/三击不额外加功能
                    case TouchGestures.TWO_SWIPE_LEFT: return PAGE_NEXT;
                    case TouchGestures.TWO_SWIPE_RIGHT: return PAGE_PREV;
                    case TouchGestures.TWO_SWIPE_UP: return SCROLL_FWD;
                    case TouchGestures.TWO_SWIPE_DOWN: return SCROLL_BACK;
                    default: return NONE;
                }
        }
    }
}
