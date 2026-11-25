package com.mangjie.reader.speech;

import android.os.Handler;
import android.os.Looper;

/**
 * 朗读看门狗：句子发出去后等引擎出声，超时就判定「引擎卡死」。
 *
 * 为什么要它：onError 只能接住「引擎明确报错」。还有一类——接受 speak 之后
 * 既不 onStart 也不 onDone 也不 onError，整条朗读静默停住，用户只感觉「读到这儿没了」。
 * 换引擎、语音包没下全的时候最容易碰上。
 *
 * 时长不写死：按字数折算，短句 3 秒，每 100 字加 1 秒，上限 12 秒，
 * 免得大段落还在合成就被误判成卡死。
 *
 * 1.0.60 修掉的误判：
 * 原来计时器是在「句子全部送完之后」才挂的。引擎的开口回调比它先到，回调把计时器撤掉，
 * 计时器随后又被挂上、再没人撤——三秒后必然判成卡死，于是把正在念的引擎 shutdown 重建，
 * 声音正是被判死这一步掐断的，句子永远推进不了多远。
 *
 * 现在两条一起改：
 * 一、调用方把 arm 挪到 speak 之前（见 speakAhead），开口回调一定来得及撤掉它；
 * 二、{@link #markStarted()}：这一轮只要开口过，到点也不判死，只把计时器再挂一轮。
 */
public final class TtsWatchdog {

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Runnable pending;
    /** 本轮是否已经收到过开口回调 */
    private static boolean started;
    /** 最近一次开口的时刻。批内挂表常比开口晚到（两个线程抢），只看 started 会被清掉，
     *  所以再用它兜一层：五秒内开过口就认为这句还在念，不判死 */
    private static long lastStartMs = 0;
    /** 本轮已经因为「开过口」而重挂了几次。开过口却始终不出下一块，重挂是会无限循环的：
     *  荣耀 PTP-AN70 那台就是开口一次、后面整批不念，原来每 5 秒重挂一次，永远判不出卡死 */
    private static int reArms = 0;

    private TtsWatchdog() {}

    /** 挂一次看门狗；同一时刻只有一个，重复挂会先撤掉旧的 */
    public static void arm(String text, final Runnable onTimeout) {
        disarm();
        started = false;
        reArms = 0;
        final Runnable r = new Runnable() {
            @Override public void run() {
                boolean recent = (lastStartMs > 0) && (System.currentTimeMillis() - lastStartMs < 5000);
                if (started || recent) {
                    // 开过口就不算卡死：把计时器再挂一轮，别去拆引擎。
                    // 长句在 2.5 倍率下也要三四秒才念完，只看 started 会被 arm 清掉、三秒必误判
                    started = false;
                    if (reArms >= 3) {
                        // 重挂三轮（十几秒）还是只有那一次开口，说明引擎是真停住了，再等没意义
                        pending = null;
                        try { onTimeout.run(); } catch (Throwable ignored) {}
                        return;
                    }
                    reArms++;
                    armAgain(text, onTimeout);
                    return;
                }
                pending = null;
                try { onTimeout.run(); } catch (Throwable ignored) {}
            }
        };
        pending = r;
        H.postDelayed(r, estimateMs(text));
    }

    /** 重挂一轮，不清本轮的开口状态与重挂计数 */
    private static void armAgain(String text, final Runnable onTimeout) {
        if (pending != null) {
            H.removeCallbacks(pending);
            pending = null;
        }
        final Runnable r = new Runnable() {
            @Override public void run() {
                boolean recent = (lastStartMs > 0) && (System.currentTimeMillis() - lastStartMs < 5000);
                if (started || recent) {
                    started = false;
                    if (reArms >= 3) {
                        pending = null;
                        try { onTimeout.run(); } catch (Throwable ignored) {}
                        return;
                    }
                    reArms++;
                    armAgain(text, onTimeout);
                    return;
                }
                pending = null;
                try { onTimeout.run(); } catch (Throwable ignored) {}
            }
        };
        pending = r;
        H.postDelayed(r, estimateMs(text));
    }

    /** 收到开口回调时调用：本轮确实出声了，别判死 */
    public static void markStarted() {
        started = true;
        lastStartMs = System.currentTimeMillis();
    }

    public static void disarm() {
        if (pending != null) {
            H.removeCallbacks(pending);
            pending = null;
        }
    }

    /** 引擎出过声就撤看门狗 */
    public static boolean watching() { return pending != null; }

    /** 3 秒起，每 100 字加 1 秒，封顶 12 秒 */
    public static long estimateMs(String text) {
        int len = text == null ? 0 : text.length();
        long ms = 3000 + (len / 100) * 1000L;
        return Math.min(12000, ms);
    }
}
