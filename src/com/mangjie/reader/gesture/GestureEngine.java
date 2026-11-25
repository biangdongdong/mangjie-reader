package com.mangjie.reader.gesture;

import android.view.MotionEvent;

/**
 * 手势引擎统一接口。设置里可以在两套实现之间切换：
 *
 * <p>1. {@link TouchGestures}：本工程自写的多指判定，跟手滚动、判据全在本地；
 * <p>2. {@link TalkBackGestures}：整包移植 TalkBack（Backtalk 分支）的 utils/gestures。
 *
 * <p>两套实现对外行为一致：feed 返回 true 就表示这一批事件归引擎，调用方不要再走单指逻辑；
 * 识别成功通过 {@link TouchGestures.Listener#onGesture} 报回来。
 */
public interface GestureEngine {

    /** 喂一个触摸事件；true 表示这一批事件已被接管 */
    boolean feed(MotionEvent ev);

    /** 作废当前这一轮判定（切页、弹菜单、遮挡时调） */
    void cancelPending();

    /** 这套引擎在当前机器上是否可用 */
    default boolean available() { return true; }
}
