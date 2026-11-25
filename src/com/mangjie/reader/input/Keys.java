package com.mangjie.reader.input;

import android.view.KeyEvent;

/** 按键归类：确认键、方向键的 -1/+1 */
public final class Keys {

    private Keys() {}

    public static boolean isEnter(KeyEvent e) {
        int c = e.getKeyCode();
        return c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER;
    }

    public static boolean isEnter(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
    }

    /** 滑块的按键加减：左/减号 -5，右/加号/等号/星号 +5，其余 0 */
    public static int sliderDelta(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MINUS) return -5;
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_PLUS
                || code == KeyEvent.KEYCODE_EQUALS || code == KeyEvent.KEYCODE_STAR) return 5;
        return 0;
    }

    /** 上/左为 -1，下/右为 +1，其余为 0 */
    public static int dir(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT) return -1;
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT) return 1;
        return 0;
    }
}
