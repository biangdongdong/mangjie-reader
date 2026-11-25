package com.mangjie.reader.speech;

import android.os.Handler;
import android.os.Looper;

/**
 * 朗读看门狗：句子发出去后等引擎出声，超时就判「引擎卡死」。
 *
 * 为什么要它：onError 只能接住「引擎明确报错」。还有一类——接受 speak 之后
 * 既不 onStart 也不 onDone 也不 onError，整条朗读静默停住，用户只感觉「读到这儿没了」。
 * 换引擎、语音包没下全的时候最容易碰上。
 *
 * 1.1.47 两处改造：
 *
 * 一、窗口不再写死。原来只按字数折算（3 秒起、每 100 字加 1 秒、封顶 12 秒），
 * 跟引擎类型和倍速都无关：慢引擎配 2.5 倍速，一句话合成本来就该五六秒，窗口还是三秒；
 * 反过来快引擎卡住了又要拖十几秒才判。现在窗口 = 引擎基准 + 字数 ÷ 倍速，封顶也分引擎。
 *
 * 二、判据收成一套 flags。原来靠 started / lastStartMs / reArms 三个松散变量，
 * started 会被下一次 arm 清掉，只能再靠 lastStartMs 兜，重挂计数和「同一句判死次数」
 * 各算一套，状态互相盖。现在一条 utterance 从挂表到收尾各阶段各占一位，
 * 判死只看 flags 组合，回调在短窗口内重复到达也不再重复改状态。
 *
 * 1.0.60 修掉的误判（保留说明）：原来计时器在「句子全部送完后」才挂，引擎的开口回调先到、
 * 把计时器撤掉，计时器随后又被挂上再没人撤——三秒后必然判成卡死，于是把正在念的引擎
 * shutdown 重建，声音正是被判死这一步掐断的。现在调用方把 arm 挪到 speak 之前，
 * 并用 markStarted 记下开口，到点只要开过口就不判死，只把计时器再挂一轮。
 */
public final class TtsWatchdog {

    /** 状态位：一条 utterance 各阶段各占一位，互不覆盖 */
    private static final int F_ARMED   = 1;        // 已挂表
    private static final int F_STARTED = 1 << 1;   // 收到 onStart（开口）
    private static final int F_RANGED  = 1 << 2;   // 收到 onRangeStart（正在念某段）
    private static final int F_DONE    = 1 << 3;   // 收到 onDone
    private static final int F_ERROR   = 1 << 4;   // 收到 onError

    /** 去抖窗口：同一轮里 onStart 可能连来两次（两个线程抢），200ms 内只认第一次 */
    private static final long DEBOUNCE_MS = 200;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Runnable pending;
    private static int flags;
    /** 本轮已经因为「开过口」而重挂了几次 */
    private static int reArms;
    /** 最近一次任何回调的时刻，用于去抖 */
    private static long lastEventAt;
    /** 最近一次开口的时刻。跨句保留：批内挂表常比开口晚到，只看 STARTED 会被 arm 清掉 */
    private static long lastStartMs;

    /** 本轮引擎标签与倍速，算窗口和宽限都要用 */
    private static String engTag = "";
    private static float rate = 1f;
    private static int reArmBudget = 3;

    private TtsWatchdog() {}

    /** 老调用方式：不带引擎与倍速，按默认参数折算 */
    public static void arm(String text, final Runnable onTimeout) {
        arm(text, "", 1f, onTimeout);
    }

    /**
     * 挂一次看门狗；同一时刻只有一个，重复挂会先撤掉旧的。
     *
     * @param engineTag  引擎标签（点明 / 界面(...) / 系统默认 / 正文(...)），起播速度不一样
     * @param speechRate 当前实际倍速（rdEffRate / effRate 的结果），窗口按它折算
     */
    public static void arm(String text, String engineTag, float speechRate, final Runnable onTimeout) {
        disarm();
        engTag = engineTag == null ? "" : engineTag;
        rate = speechRate <= 0.05f ? 1f : speechRate;
        reArmBudget = budgetFor(engTag, rate);
        flags = F_ARMED;          // 只清本轮状态；lastStartMs 跨句保留，供「刚开口」兜底
        reArms = 0;
        schedule(text, onTimeout, estimateMs(text, engTag, rate));
    }

    private static void schedule(final String text, final Runnable onTimeout, long delayMs) {
        final Runnable r = new Runnable() {
            @Override public void run() {
                // 已经收尾（onDone / onError）就不再判死
                if ((flags & (F_DONE | F_ERROR)) != 0) { pending = null; return; }
                if (progressed()) {
                    if (reArms >= reArmBudget) {
                        // 重挂到上限还只有那一次开口，说明引擎是真停住了，再等没意义
                        pending = null;
                        flags |= F_ERROR;
                        fire(onTimeout);
                        return;
                    }
                    reArms++;
                    flags &= ~F_STARTED;      // 重挂一轮，等下一块开口
                    schedule(text, onTimeout, estimateMs(text, engTag, rate));
                    return;
                }
                pending = null;
                flags |= F_ERROR;
                fire(onTimeout);
            }
        };
        pending = r;
        H.postDelayed(r, delayMs);
    }

    /** 这一轮有没有进展：开口过，或者刚刚才开口（批内挂表常比开口晚到） */
    private static boolean progressed() {
        if ((flags & F_STARTED) != 0) return true;
        return lastStartMs > 0 && (System.currentTimeMillis() - lastStartMs) < graceMs();
    }

    /** 收到开口回调时调用：本轮确实出声了，别判死 */
    public static void markStarted() { mark(F_STARTED); }

    /** 引擎报正在念第几段：与开口等价，算有进展 */
    public static void markRanged() { mark(F_RANGED); }

    /** 整句读完 */
    public static void markDone() { mark(F_DONE); }

    /** 引擎明确报错 */
    public static void markError() { mark(F_ERROR); }

    private static void mark(int bit) {
        long now = System.currentTimeMillis();
        if (bit == F_STARTED && (flags & F_STARTED) != 0 && now - lastEventAt < DEBOUNCE_MS) {
            lastEventAt = now;          // 同一轮里的重复开口只刷新时刻，不重复计数
            return;
        }
        flags |= bit;
        lastEventAt = now;
        if (bit == F_STARTED) lastStartMs = now;
        if ((bit & (F_DONE | F_ERROR)) != 0) disarm();
    }

    public static void disarm() {
        if (pending != null) {
            H.removeCallbacks(pending);
            pending = null;
        }
    }

    /** 看门狗是否还挂着 */
    public static boolean watching() { return pending != null; }

    /** 老接口：按默认参数折算 */
    public static long estimateMs(String text) { return estimateMs(text, "", 1f); }

    /** 窗口 = 引擎基准 + 字数 ÷ 倍速，封顶按引擎给 */
    public static long estimateMs(String text, String engineTag, float speechRate) {
        int len = text == null ? 0 : text.length();
        float rt = speechRate <= 0.05f ? 1f : speechRate;
        long window = baseMs(engineTag) + (long) ((len / 100f) * 1000f / rt);
        return Math.min(capMs(engineTag), window);
    }

    /** 起播基准：点明自己管队列，开口快；系统默认与界面引擎起播偏慢 */
    private static long baseMs(String eng) {
        if (eng == null || eng.isEmpty()) return 3000;
        if (eng.startsWith("点明")) return 2200;
        if (eng.startsWith("系统")) return 3200;
        if (eng.startsWith("界面")) return 3200;
        return 3000;
    }

    private static long capMs(String eng) {
        return (eng != null && eng.startsWith("点明")) ? 9000 : 12000;
    }

    /** 重挂预算：点明自己管队列给两轮；其余三轮；慢倍速再放一轮 */
    private static int budgetFor(String eng, float rt) {
        int n = (eng != null && eng.startsWith("点明")) ? 2 : 3;
        if (rt < 0.8f) n++;
        return n;
    }

    /**
     * 「刚开口」的宽限。旧实现固定 5 秒：倍速 2.5 时一句两秒就念完，
     * 五秒宽限反而把真卡死拖了十几秒才判；倍速 0.5 时又不够。按倍速反比折算。
     */
    private static long graceMs() {
        long g = (long) (5000f / (rate <= 0.05f ? 1f : rate));
        return Math.max(3000, Math.min(12000, g));
    }

    private static void fire(Runnable r) {
        try { r.run(); } catch (Throwable ignored) {}
    }
}
