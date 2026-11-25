package com.mangjie.reader.a11y;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * 读屏状态判定。
 *
 * 这里要把两件事分开问，不能混成一个布尔：
 *   1. 服务在不在（有读屏注册）——决定「话归谁念」；
 *   2. 触摸探索开没开（读屏此刻是否接管手指）——决定「触摸要不要交出去」。
 *
 * 点明安卓这类第三方读屏「暂停」时会把触摸探索停掉，但服务仍挂在系统里。
 * 原来两件事共用一个缓存值，结果点明一暂停，缓存还写着「读屏开」，
 * 触摸全被交出去，自绘的光标条连拖都拖不动；语音也一直走 announce，
 * 而点明只念焦点控件、不念 announce，于是更是彻底没声。
 */
public final class ReaderState {

    private ReaderState() {}

    /** 有读屏服务注册（FEEDBACK_SPOKEN / GENERIC）。决定语音归谁念，可以按秒级缓存。 */
    public static boolean serviceOn(AccessibilityManager am) {
        if (am == null) return false;
        try {
            if (!am.isEnabled()) return false;
            List<AccessibilityServiceInfo> svc = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (svc != null) {
                for (AccessibilityServiceInfo i : svc) {
                    int f = i.feedbackType;
                    if ((f & AccessibilityServiceInfo.FEEDBACK_SPOKEN) != 0
                            || (f & AccessibilityServiceInfo.FEEDBACK_GENERIC) != 0) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 读屏此刻是否在接管触摸。必须实时查，一次都不该读缓存。
     * 查询本身失败（个别 ROM 抛异常）就退回上次已知状态，不能反过来把触摸全封掉。
     */
    public static boolean touchExploreOn(AccessibilityManager am, boolean fallback) {
        if (am == null) return fallback;
        try {
            return am.isTouchExplorationEnabled();
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 把当前注册的读屏服务 id 打进日志，便于辨认是哪一家（点明 / TalkBack / 天坦…）。 */
    public static String serviceIds(AccessibilityManager am) {
        if (am == null) return "无";
        try {
            List<AccessibilityServiceInfo> svc = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (svc == null || svc.isEmpty()) return "无";
            StringBuilder sb = new StringBuilder();
            for (AccessibilityServiceInfo i : svc) {
                if (sb.length() > 0) sb.append('、');
                sb.append(i.getId()).append("(feedback=").append(i.feedbackType).append(')');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "读取失败";
        }
    }
}
