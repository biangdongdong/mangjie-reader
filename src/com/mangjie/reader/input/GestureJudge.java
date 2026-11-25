package com.mangjie.reader.input;

/**
 * 单指手势的纯判定：方向锁、折返、横滑、竖滑、轻点。
 * 只做算术，不碰视图、不播报；阈值由调用方按 dp 算好传进来。
 */
public final class GestureJudge {

    public static final int LOCK_RIGHT = 1;
    public static final int LOCK_LEFT = -1;
    public static final int LOCK_VERTICAL = 100;

    private GestureJudge() {}

    /** 首段方向只定一次：横向偏 20dp 记左右，竖向为主记 100（不参与折返） */
    public static int dirLock(int lock, float dx, float dy, float lockPx) {
        if (lock != 0) return lock;
        if (Math.abs(dx) > lockPx || Math.abs(dy) > lockPx) {
            if (Math.abs(dx) >= Math.abs(dy)) return dx > 0 ? LOCK_RIGHT : LOCK_LEFT;
            return LOCK_VERTICAL;
        }
        return lock;
    }

    /** 先左再右返回 "LR"（打开菜单），先右再左返回 "RL"（返回），否则 null */
    public static String reversed(int lock, float dx, float reversePx) {
        if (lock == LOCK_RIGHT && dx < -reversePx) return "RL";
        if (lock == LOCK_LEFT && dx > reversePx) return "LR";
        return null;
    }

    /** 快速横滑：300ms 内、横向超过 40dp，且明显比纵向大 */
    public static boolean horizontalFlick(float ddx, float ddy, long durMs, long maxDurMs, float minPx) {
        return durMs < maxDurMs && Math.abs(ddx) > minPx && Math.abs(ddx) > Math.abs(ddy) * 1.5f;
    }

    /** 快速竖滑：拖过、没长按、非双指，300ms 内、纵向超过 40dp，且明显比横向大 */
    public static boolean verticalFlick(boolean movedFar, boolean longFired, boolean twoFinger,
                                        float ddy, float ddx, long durMs, long maxDurMs, float minPx) {
        return movedFar && !longFired && !twoFinger
                && durMs < maxDurMs && Math.abs(ddy) > minPx && Math.abs(ddy) > Math.abs(ddx) * 1.2f;
    }

    /** 轻点：没长按、非双指，位移都在 20dp 内，且 1.2 秒内抬手 */
    public static boolean tapLike(boolean longFired, boolean twoFinger,
                                  float dispY, float dispX, long durMs, float maxPx, long maxDurMs) {
        return !longFired && !twoFinger
                && dispY < maxPx && dispX < maxPx && durMs < maxDurMs;
    }
}
