package com.mangjie.reader.gesture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * 多指手势识别，参数照搬 TalkBack（utils/gestures/MultiFingerSwipe.java）：
 *
 * 1. 第二指必须早于「tapTimeout × 手指数」（双指约 200ms）落下，慢了这一整轮就不认多指；
 * 2. 起步门槛 = 1 × touchSlop（双指约 8dp，和 TalkBack MultiFingerSwipe 一样，不随手指数放大）；
 * 3. 每段按 |dx| > |dy| 判横竖，方向一错整轮作废，斜着滑不会误触发；
 * 4. 每 2.5mm 采一点防抖，松手才结算，路径少于两点判失败；
 * 5. 全程用 pointerId 找指头，不用下标（两指抬起顺序不定，下标会错位）；
 * 6. 多指点击要等 tapTimeout × 手指数 落定（双指 200ms），才决定是一击、双击还是三击；
 *    点击允许的位移是 手指数 × touchSlop（TalkBack MultiFingerMultiTap 同口径）。
 *
 * 只认多指；单指手势一律交回调用方原有逻辑，不在这里插手。
 */
public final class TouchGestures implements GestureEngine {

    public interface Listener {
        void onGesture(String id);
        /** 两指上下拖动时的实时位移（像素，手指向上为负）。用于跟手滚动，抬手不补惯性 */
        default void onTwoFingerScroll(float dy) {}
    }

    /** 2 指点击 / 双击 / 三击 */
    public static final String TWO_TAP = "2F_TAP";
    public static final String TWO_DOUBLE_TAP = "2F_DOUBLE_TAP";
    public static final String TWO_TRIPLE_TAP = "2F_TRIPLE_TAP";
    public static final String TWO_SWIPE_LEFT = "2F_SWIPE_LEFT";
    public static final String TWO_SWIPE_RIGHT = "2F_SWIPE_RIGHT";
    public static final String TWO_SWIPE_UP = "2F_SWIPE_UP";
    public static final String TWO_SWIPE_DOWN = "2F_SWIPE_DOWN";
    public static final String THREE_SWIPE_LEFT = "3F_SWIPE_LEFT";
    public static final String THREE_SWIPE_RIGHT = "3F_SWIPE_RIGHT";
    public static final String THREE_SWIPE_UP = "3F_SWIPE_UP";
    public static final String THREE_SWIPE_DOWN = "3F_SWIPE_DOWN";

    private static final int DIR_NONE = 0;
    private static final int DIR_LEFT = 1;
    private static final int DIR_RIGHT = 2;
    private static final int DIR_UP = 3;
    private static final int DIR_DOWN = 4;

    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float slop;
    private final int tapTimeout;
    /** 本轮所有指头里离按下点最远的位移，多指点击靠它判「按稳没按稳」 */
    private float maxDelta;
    private final float sampleX;
    private final float sampleY;

    private final int[] ids = new int[]{-1, -1, -1, -1};
    private final float[] baseX = new float[4];
    private final float[] baseY = new float[4];
    private final float[] lastX = new float[4];
    private final float[] lastY = new float[4];
    private final int[] points = new int[4];

    private long downTime = -1;
    private int fingers = 0;        // 本轮认定的手指数，0 表示还没进多指
    private boolean multi = false;  // 是否已经按多指在跟踪
    private boolean killed = false; // 本轮作废（超时、方向乱、指头数不对）
    private boolean moved = false;  // 有指头超过了起步门槛
    private boolean scrolled = false; // 本轮已跟手滚动过，抬手不再判「滚一屏」
    private int dir = DIR_NONE;

    private long lastTapTime = 0;
    private int tapCount = 0;
    private int tapFingers = 0;
    private Runnable pendingTap;

    public TouchGestures(Context c, Listener l) {
        this.listener = l;
        ViewConfiguration vc = ViewConfiguration.get(c);
        this.slop = vc.getScaledTouchSlop();
        this.tapTimeout = ViewConfiguration.getTapTimeout();
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        // 2.5 毫米一个采样点
        this.sampleX = dm.xdpi / 2.54f * 0.25f;
        this.sampleY = dm.ydpi / 2.54f * 0.25f;
    }

    /** 返回 true：这个事件属于多指手势，调用方不要再当单指处理 */
    public boolean feed(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                reset();
                downTime = ev.getEventTime();
                fingers = 1;
                multi = false;
                killed = false;
                moved = false;
                dir = DIR_NONE;
                recordAt(0, ev, 0);
                return false;   // 单指照旧走原有流程

            case MotionEvent.ACTION_POINTER_DOWN: {
                if (killed || fingers == 0) return false;
                int count = ev.getPointerCount();
                if (count > 3) {          // 四指以上不归我们管
                    killed = true;
                    return false;
                }
                long gap = ev.getEventTime() - downTime;
                // TalkBack 的窗口：每多一指，允许的时间就多一个 tapTimeout
                if (gap > (long) tapTimeout * count) {
                    killed = true;        // 慢吞吞补第二指不算双指
                    return false;
                }
                fingers = count;
                multi = true;
                recordAt(fingers - 1, ev, ev.getActionIndex());
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (!multi) return false;
                if (killed) return true;
                if (ev.getPointerCount() < 2) return false;
                boolean anyOver = false;
                int commonDir = DIR_NONE;
                for (int i = 0; i < fingers; i++) {
                    int idx = ev.findPointerIndex(ids[i]);
                    if (idx < 0) continue;
                    float x = ev.getX(idx), y = ev.getY(idx);
                    float dx = x - baseX[i], dy = y - baseY[i];
                    float delta = (float) Math.hypot(dx, dy);
                    if (delta > maxDelta) maxDelta = delta;
                    // 起步门槛对齐 TalkBack：1 倍 slop，不随手指数放大
                    if (delta < slop) continue;             // 还在摸着，不算滑
                    anyOver = true;
                    int d = toDirection(dx, dy);
                    if (d == DIR_NONE) continue;
                    if (commonDir == DIR_NONE) {
                        commonDir = d;            // 第一段定调
                    } else if (d != commonDir) {
                        killed = true;            // 两指方向不一致
                        return true;
                    }
                }
                if (anyOver && !killed) {
                    if (!moved) {
                        moved = true;
                        dir = commonDir;
                    }
                    // 已经开滑：逐段校验，方向一错立刻作废
                    float sumDy = 0f;
                    int counted = 0;
                    for (int i = 0; i < fingers; i++) {
                        int idx = ev.findPointerIndex(ids[i]);
                        if (idx < 0) continue;
                        float x = ev.getX(idx), y = ev.getY(idx);
                        float dx = x - lastX[i], dy = y - lastY[i];
                        if (Math.abs(dx) < sampleX && Math.abs(dy) < sampleY) continue;
                        int d = toDirection(dx, dy);
                        if (d != DIR_NONE && d != dir) {
                            killed = true;
                            break;
                        }
                        sumDy += dy;
                        counted++;
                        lastX[i] = x;
                        lastY[i] = y;
                        points[i]++;
                    }
                    if (!killed && counted > 0 && (dir == DIR_UP || dir == DIR_DOWN)) {
                        float avg = sumDy / counted;
                        if (avg != 0f) {
                            scrolled = true;
                            listener.onTwoFingerScroll(avg);
                        }
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                if (!multi) return false;
                // 三指里抬掉一指就不算三指手势了
                if (fingers == 3) killed = true;
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!multi) return false;
                boolean ok = !killed && ev.getActionMasked() == MotionEvent.ACTION_UP;
                if (ok && scrolled) {
                    reset();
                    return true;      // 跟手滚过：抬手就停，不再补一屏
                }
                if (ok && moved && dir != DIR_NONE) {
                    fire(swipeId(fingers, dir));
                    reset();
                    return true;
                }
                if (ok && !moved && maxDelta < slop * fingers) {
                    scheduleTap(fingers);
                    reset();
                    return true;
                }
                reset();
                return true;
            }
        }
        return false;
    }

    private void reset() {
        fingers = 0;
        maxDelta = 0f;
        scrolled = false;
        multi = false;
        killed = false;
        moved = false;
        dir = DIR_NONE;
        downTime = -1;
        for (int i = 0; i < 4; i++) {
            ids[i] = -1;
            points[i] = 0;
            baseX[i] = baseY[i] = lastX[i] = lastY[i] = 0f;
        }
    }

    private void recordAt(int slot, MotionEvent ev, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= ev.getPointerCount()) return;
        ids[slot] = ev.getPointerId(pointerIndex);
        baseX[slot] = lastX[slot] = ev.getX(pointerIndex);
        baseY[slot] = lastY[slot] = ev.getY(pointerIndex);
        points[slot] = 0;
    }

    private static int toDirection(float dx, float dy) {
        if (dx == 0 && dy == 0) return DIR_NONE;
        if (Math.abs(dx) > Math.abs(dy)) return dx < 0 ? DIR_LEFT : DIR_RIGHT;
        return dy < 0 ? DIR_UP : DIR_DOWN;
    }

    private static String swipeId(int fingers, int dir) {
        String prefix = fingers >= 3 ? "3F_SWIPE_" : "2F_SWIPE_";
        switch (dir) {
            case DIR_LEFT: return prefix + "LEFT";
            case DIR_RIGHT: return prefix + "RIGHT";
            case DIR_UP: return prefix + "UP";
            default: return prefix + "DOWN";
        }
    }

    /** 多指点击：等一个「tapTimeout × 手指数」的窗口落定再报，避免一次双击同时触发单击和双击两个动作。
     *  窗口照 TalkBack（MultiFingerMultiTap）：双指 200ms、三指 300ms，比系统的 300ms 单指双击窗口短。 */
    private void scheduleTap(int f) {
        long now = SystemClock.uptimeMillis();
        final long win = (long) tapTimeout * Math.max(1, f);
        if (now - lastTapTime <= win && f == tapFingers) tapCount++;
        else { tapCount = 1; tapFingers = f; }
        lastTapTime = now;
        if (pendingTap != null) handler.removeCallbacks(pendingTap);
        final int fc = f, tc = tapCount;
        pendingTap = new Runnable() {
            @Override public void run() {
                pendingTap = null;
                tapCount = 0;
                String id;
                if (fc >= 2 && tc >= 3) id = TWO_TRIPLE_TAP;
                else if (fc >= 2 && tc == 2) id = TWO_DOUBLE_TAP;
                else id = TWO_TAP;
                listener.onGesture(id);
            }
        };
        handler.postDelayed(pendingTap, win);
    }

    private void fire(String id) {
        if (listener != null) listener.onGesture(id);
    }

    /** 页面切走时清掉待结算的点击，免得在别的页面上突然触发 */
    public void cancelPending() {
        if (pendingTap != null) {
            handler.removeCallbacks(pendingTap);
            pendingTap = null;
        }
        tapCount = 0;
    }
}
