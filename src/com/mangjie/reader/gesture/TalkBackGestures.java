package com.mangjie.reader.gesture;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.gestures.GestureAnalyticsEvent;
import com.google.android.accessibility.utils.gestures.GestureMatcher;
import com.google.android.accessibility.utils.gestures.GestureManifold;

import java.util.Arrays;
import java.util.List;

/**
 * 手势引擎 B：整包移植 TalkBack（Backtalk 分支）的 utils/gestures 手势库。
 *
 * <p>对外接口和 {@link TouchGestures} 完全一样（feed / cancelPending / onGesture），
 * 这样 MainActivity 只按设置项挑一个实现，其它代码一行不用动。
 *
 * <p>只挂多指手势（双指点击/双击/三击、双指四向滑、三指四向滑），单指一律不管：
 * 单指是阅读页阅读、焦点跟随、调值的命根子，交给原有的 readingTouch。
 *
 * <p>「这一轮算不算多指」沿用 TalkBack 的时间窗：第二指必须在 tapTimeout × 手指数
 * 之内落下，慢了整轮不认（MultiFingerSwipe 里同一套判据，这里做前置拦截，
 * 免得单指那一套先出声）。
 *
 * 上游文件保留原有 Google 版权头，本文件只是这门手势引擎的接线壳。
 */
public final class TalkBackGestures implements GestureEngine {

    /** 引擎 key：移植版 */
    public static final String ENGINE = "talkback";
    /** 引擎 key：本工程自写（默认，稳定） */
    public static final String ENGINE_SELF = "self";

    public static String engineName(String key) {
        return ENGINE.equals(key) ? "TalkBack 移植" : "盲界自写";
    }

    /** GestureMatcherFactory 里的枚举名，只挑多指那几条 */
    private static final String[] SUPPORTED = {
            "MAPPER_GESTURE_2_FINGER_SINGLE_TAP",
            "MAPPER_GESTURE_2_FINGER_DOUBLE_TAP",
            "MAPPER_GESTURE_2_FINGER_TRIPLE_TAP",
            "MAPPER_GESTURE_2_FINGER_SWIPE_DOWN",
            "MAPPER_GESTURE_2_FINGER_SWIPE_LEFT",
            "MAPPER_GESTURE_2_FINGER_SWIPE_RIGHT",
            "MAPPER_GESTURE_2_FINGER_SWIPE_UP",
            "MAPPER_GESTURE_3_FINGER_SWIPE_DOWN",
            "MAPPER_GESTURE_3_FINGER_SWIPE_LEFT",
            "MAPPER_GESTURE_3_FINGER_SWIPE_RIGHT",
            "MAPPER_GESTURE_3_FINGER_SWIPE_UP",
    };

    private final TouchGestures.Listener listener;
    private final Handler ui;
    private final long tapTimeout;
    /** 为空表示系统版本不够（Android 11 以下没有 AccessibilityGestureEvent），这个引擎就不工作 */
    private final GestureManifold manifold;

    /** 这一轮手指是否已算作多指（决定要不要吞事件、要不要派发） */
    private boolean multi;
    /** 这一轮作废（第二指来太慢、四指以上、方向不一致） */
    private boolean killed;
    private long downTime;

    public TalkBackGestures(Context c, TouchGestures.Listener l) {
        this.listener = l;
        this.ui = new Handler(Looper.getMainLooper());
        this.tapTimeout = ViewConfiguration.get(c).getTapTimeout();

        GestureManifold m = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                List<String> support = Arrays.asList(SUPPORTED);
                m = new GestureManifold(c,
                        new GestureManifold.Listener() {
                            @Override public void onGestureStarted(int gestureId) {}
                            @Override public void onGestureCompleted(AccessibilityGestureEvent e) {
                                onManifoldCompleted(e.getGestureId());
                            }
                            @Override public void onGestureCancelled(int gestureId) {}
                        },
                        new GestureManifold.GestureConfigProvider() {},
                        new GestureMatcherLogger(),
                        android.view.Display.DEFAULT_DISPLAY,
                        support);
                // 不加这一句，双指/三指那些 matcher 根本不在处理列表里
                m.setMultiFingerGesturesEnabled(true);
            } catch (Throwable t) {
                m = null;
            }
        }
        this.manifold = m;
    }

    /** 引擎是否真的可用（系统版本不够时为 false） */
    @Override public boolean available() {
        return manifold != null;
    }

    /**
     * 喂一个触摸事件。
     *
     * @return true 表示这一批事件归我们，调用方不要再走单指那套
     */
    @Override public boolean feed(MotionEvent ev) {
        if (manifold != null) {
            // 无论如何都喂：matcher 要靠完整的 DOWN→UP 序列才能算时序
            manifold.onMotionEvent(Performance.EventId.NONE, ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                reset();
                downTime = ev.getEventTime();
                return false;   // 单指照旧走原有流程

            case MotionEvent.ACTION_POINTER_DOWN: {
                if (killed) return false;
                int count = ev.getPointerCount();
                if (count > 3) {           // 四指以上不归我们管
                    killed = true;
                    return false;
                }
                long gap = ev.getEventTime() - downTime;
                if (gap > tapTimeout * count) {
                    killed = true;         // 慢吞吞补第二指不算多指
                    return false;
                }
                multi = true;
                return true;
            }

            case MotionEvent.ACTION_MOVE:
                return multi;

            case MotionEvent.ACTION_POINTER_UP:
                return multi;

            case MotionEvent.ACTION_UP:
                // 不清 multi：双指连击要等双击超时才判定，那时已经是 UP 之后了
                return multi;

            case MotionEvent.ACTION_CANCEL:
                reset();
                return false;

            default:
                return multi;
        }
    }

    /** 外部要求作废当前判定（切页、弹菜单时调） */
    @Override public void cancelPending() {
        if (manifold != null) manifold.clear();
        reset();
    }

    private void reset() {
        multi = false;
        killed = false;
    }

    private void onManifoldCompleted(int gestureId) {
        if (killed) return;
        if (!multi) {
            // 这一轮我们没当成多指（第二指来太慢），TalkBack 的 matcher 却认了：不派发
            return;
        }
        String id = map(gestureId);
        if (id == null) return;
        final String fid = id;
        ui.post(new Runnable() {
            @Override public void run() { listener.onGesture(fid); }
        });
    }

    private static String map(int gestureId) {
        switch (gestureId) {
            case AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP:
                return TouchGestures.TWO_TAP;
            case AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP:
                return TouchGestures.TWO_DOUBLE_TAP;
            case AccessibilityService.GESTURE_2_FINGER_TRIPLE_TAP:
                return TouchGestures.TWO_TRIPLE_TAP;
            case AccessibilityService.GESTURE_2_FINGER_SWIPE_LEFT:
                return TouchGestures.TWO_SWIPE_LEFT;
            case AccessibilityService.GESTURE_2_FINGER_SWIPE_RIGHT:
                return TouchGestures.TWO_SWIPE_RIGHT;
            case AccessibilityService.GESTURE_2_FINGER_SWIPE_UP:
                return TouchGestures.TWO_SWIPE_UP;
            case AccessibilityService.GESTURE_2_FINGER_SWIPE_DOWN:
                return TouchGestures.TWO_SWIPE_DOWN;
            case AccessibilityService.GESTURE_3_FINGER_SWIPE_LEFT:
                return TouchGestures.THREE_SWIPE_LEFT;
            case AccessibilityService.GESTURE_3_FINGER_SWIPE_RIGHT:
                return TouchGestures.THREE_SWIPE_RIGHT;
            case AccessibilityService.GESTURE_3_FINGER_SWIPE_UP:
                return TouchGestures.THREE_SWIPE_UP;
            case AccessibilityService.GESTURE_3_FINGER_SWIPE_DOWN:
                return TouchGestures.THREE_SWIPE_DOWN;
            default:
                return null;   // 三指各类点击、按住都先不接
        }
    }

    /** 埋点用，全空 */
    private static final class GestureMatcherLogger implements GestureMatcher.AnalyticsEventLogger {
        @Override public void logAnalyticsEvent(GestureAnalyticsEvent e) {}
    }
}
