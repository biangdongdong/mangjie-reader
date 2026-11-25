/*
 * 本工程自带的极简替代实现，用来顶替 Backtalk（Apache-2.0）手势库里对
 * com.google.android.accessibility.utils.Performance 的依赖。
 * 原类只承担性能埋点，手势判定完全用不到这些数据，这里全部留空。
 */
package com.google.android.accessibility.utils;

public final class Performance {

    /** 原库的性能事件 id，手势库里只做透传 */
    public static final class EventId {
        public static final EventId NONE = new EventId();
        public EventId() {}
    }

    private static final Performance INSTANCE = new Performance();

    private Performance() {}

    public static Performance getInstance() { return INSTANCE; }

    /** 记录一次手势动作的时间戳：埋点用，这里什么都不做 */
    public void onGestureLastMotionEventTime(EventId eventId, long eventTime) {}
}
