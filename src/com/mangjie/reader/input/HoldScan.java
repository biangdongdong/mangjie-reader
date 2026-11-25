package com.mangjie.reader.input;

/**
 * 长按扫字：把「按下多久才进扫字」「拖多远算走一个字」定在这里，
 * 触摸状态机仍在 Activity 里（要和 View 一起算），但换算规则集中在这。
 */
public final class HoldScan {

    /** 按下后多久进入扫字模式（这期间不出声，避免摸一下就念） */
    public static final long HOLD_MS = 500;

    /** 拖动多少 dp 算走过一个字 */
    public static final int STEP_DP = 12;

    /** 长按期间允许的手指抖动；超过这个位移就当用户在滑动，取消扫字 */
    public static final int CANCEL_MOVE_DP = 14;

    private HoldScan() {}

    /** 手指横向位移换算成走过的字数（正数为往后） */
    public static int deltaChars(float dx, float density) {
        int step = Math.max(1, Math.round(STEP_DP * density));
        return (int) Math.floor(dx / step);
    }

    /** 这次位移是否已经超过「不许再当长按」的阈值 */
    public static boolean movedTooFar(float dy, float density) {
        return Math.abs(dy) > Math.round(CANCEL_MOVE_DP * density);
    }
}
