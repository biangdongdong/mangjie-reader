package com.mangjie.reader.speech;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.dianming.phoneapp.ISpeakCallback;
import com.dianming.phoneapp.ISpeakService;

import java.util.ArrayList;
import java.util.List;

/**
 * 点明语音服务对接（配套「点明安卓」读屏，包名 com.dianming.phoneapp）。
 *
 * <p>为什么值得接：点明把这个包里的 {@code ISpeakService} 当作自家语音总线，
 * 读屏播报与正文朗读是两条独立通道（{@code speakIndependent} / {@code stopIndependent}），
 * 一句念完由它自己的服务回调（{@code onFinished(utteranceId, finishType)}），
 * 不依赖系统 TTS 那个在部分机型（荣耀 PTP-AN70 / Android 16 实测 onDone 一次都不回）
 * 上不回来的 onDone。接进来之后，界面播报和正文朗读不会再互相 stop。</p>
 *
 * <p>保守原则：默认关闭；没装、绑定失败、调用报错、返回负值，一律返回 false 让调用方
 * 回落到系统 TTS，绝不影响没装点明的用户。</p>
 *
 * <p>接口里的 which / prefixCn / prefixEn 语义厂商没有公开，这里按候选策略链逐条试：
 * speakIndependent → speak(which=0) → SpeakNow，每条的结果都写进日志，
 * 真机上跑一次就能看出哪条是真的可用。</p>
 */
public final class DianmingVoice {

    public static final String PKG = "com.dianming.phoneapp";
    public static final String IFACE = "com.dianming.phoneapp.ISpeakService";

    /** 回调宿主：日志与「一句念完」通知，都在主线程 */
    public interface Host {
        void log(String tag, String msg);
        void onSentenceDone(int idx);
    }

    private static Context appCtx;
    private static Host host;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile ISpeakService svc;
    private static volatile boolean probing = false;
    private static volatile boolean connected = false;
    private static volatile String compName = "";
    private static volatile String version = "";
    private static volatile int versionCode = -1;
    private static volatile String lastFail = "";
    /** 上一次探测失败的时刻：失败后 60 秒内不再重试，免得每句朗读都去 bind 一遍 */
    private static volatile long lastFailAt = 0;

    /** 正在念的句子号，-1 表示空闲 */
    private static volatile int current = -1;
    private static volatile int reqSeq = 0;

    private static final List<ComponentName> queue = new ArrayList<ComponentName>();
    private static volatile int tryIndex = 0;

    private DianmingVoice() {}

    public static boolean isConnected() { return connected && svc != null; }
    public static boolean isSpeaking() { return current >= 0; }
    public static int currentSentence() { return current; }
    public static String status() {
        if (connected) return "已连接 " + compName + (version.isEmpty() ? "" : " v" + version);
        if (probing) return "探测中";
        return lastFail.isEmpty() ? "未探测" : lastFail;
    }

    /** 探测并绑定。可重复调用，已连上就直接返回。 */
    public static void probe(Context ctx, Host h) {
        if (connected) return;
        if (probing) return;
        if (lastFailAt > 0 && System.currentTimeMillis() - lastFailAt < 60000L) return;   // 刚失败过，歇一分钟
        appCtx = ctx.getApplicationContext();
        host = h;
        PackageManager pm = appCtx.getPackageManager();
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(PKG, PackageManager.GET_SERVICES);
        } catch (Throwable e) {
            lastFail = "未安装点明（" + shortErr(e) + "）";
            lastFailAt = System.currentTimeMillis();
            log("点明", lastFail);
            return;
        }
        version = pi.versionName == null ? "" : pi.versionName;
        versionCode = pi.versionCode;
        List<ComponentName> cands = new ArrayList<ComponentName>();
        if (pi.services != null) {
            for (ServiceInfo si : pi.services) {
                String n = si.name == null ? "" : si.name;
                String ln = n.toLowerCase();
                if (ln.contains("speak") || ln.contains("voice") || ln.contains("tts")) {
                    cands.add(new ComponentName(PKG, n));
                }
            }
        }
        if (cands.isEmpty() && pi.services != null) {
            int n = 0;
            for (ServiceInfo si : pi.services) {          // 名字不带关键词：前几个也试，看 binder 描述符
                if (si.name == null) continue;
                cands.add(new ComponentName(PKG, si.name));
                if (++n >= 6) break;
            }
        }
        if (cands.isEmpty()) {
            lastFail = "点明包里没有服务";
            log("点明", lastFail);
            return;
        }
        queue.clear();
        queue.addAll(cands);
        tryIndex = 0;
        probing = true;
        log("点明", "开始探测：v" + version + "（" + versionCode + "），候选服务 " + cands.size() + " 个");
        bindNext();
    }

    private static void bindNext() {
        if (tryIndex >= queue.size()) {
            probing = false;
            connected = false;
            if (lastFail.isEmpty()) lastFail = "候选服务都没对上 ISpeakService";
            lastFailAt = System.currentTimeMillis();
            log("点明", "探测结束，未连上：" + lastFail);
            return;
        }
        ComponentName cn = queue.get(tryIndex++);
        Intent it = new Intent();
        it.setComponent(cn);
        try {
            boolean ok = appCtx.bindService(it, conn, Context.BIND_AUTO_CREATE);
            log("点明", "尝试绑定 " + cn.getClassName() + " → " + (ok ? "已发起" : "被拒"));
            if (!ok) MAIN.postDelayed(new Runnable() { @Override public void run() { bindNext(); } }, 300);
        } catch (Throwable e) {
            log("点明", "绑定异常 " + cn.getClassName() + "：" + shortErr(e));
            MAIN.postDelayed(new Runnable() { @Override public void run() { bindNext(); } }, 300);
        }
    }

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            String desc = "";
            try { desc = binder.getInterfaceDescriptor(); } catch (Throwable ignored) {}
            if (!IFACE.equals(desc)) {
                log("点明", "描述符不符（" + desc + "），换下一个");
                try { appCtx.unbindService(this); } catch (Throwable ignored) {}
                MAIN.postDelayed(new Runnable() { @Override public void run() { bindNext(); } }, 300);
                return;
            }
            svc = ISpeakService.Stub.asInterface(binder);
            connected = true;
            probing = false;
            compName = name.getClassName();
            try {
                version = svc.getBasePackageVersion();
                versionCode = svc.getBasePackageVersionCode();
            } catch (Throwable ignored) {}
            log("点明", "已连上 " + compName + "　版本=" + version + "（" + versionCode + "）");
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            connected = false;
            svc = null;
            current = -1;
            log("点明", "服务断开：" + name.getClassName());
        }

        @Override public void onBindingDied(ComponentName name) {
            connected = false;
            svc = null;
            log("点明", "绑定失效：" + name.getClassName());
        }
    };

    /** 整句交给点明念。返回 true 表示已被它接管（调用方不要再走系统 TTS）。 */
    public static boolean speakSentence(final String text, final int idx) {
        final ISpeakService s = svc;
        if (!connected || s == null || text == null || text.isEmpty()) return false;
        current = idx;
        reqSeq++;
        final int myReq = reqSeq;
        final ISpeakCallback cb = new ISpeakCallback.Stub() {
            @Override public void onFinished(int utteranceId, int finishType) {
                log("点明", "onFinished 句=" + (idx + 1) + "　utteranceId=" + utteranceId + "　finishType=" + finishType);
                if (myReq != reqSeq) { log("点明", "回调作废：请求已换代 " + myReq + "→" + reqSeq); return; }
                if (current != idx) { log("点明", "回调作废：current=" + current + "　idx=" + idx); return; }
                current = -1;
                MAIN.post(new Runnable() { @Override public void run() {
                    if (host != null) host.onSentenceDone(idx);
                } });
            }
        };
        // 策略一（首选）：单语音。AIDL 注释原文就是「指定单语音语音朗读」——不用告诉它中英文
        // 各用哪个语音角色，让它拿当前主语音念完。我们上一版走的是 speakIndependent（注释是
        //「指定单双语音朗读」），要传 prefixCn/prefixEn 两个语音角色，我们传空串，很可能就是
        // 它不吭声的由来。
        try {
            int r = s.speakSingleVoice(text, 0, cb);
            if (r > 0) { devLog("speakSingleVoice 返回=" + r + "　句=" + (idx + 1)); return true; }
            devLog("speakSingleVoice 返回=" + r + "（不算接管，换下一条）");
        } catch (Throwable e) {
            devLog("speakSingleVoice 失败：" + shortErr(e));
        }
        // 策略二：按通道号点名
        try {
            int r = s.speak(0, text, 0, cb);
            if (r > 0) { devLog("speak(which=0) 返回=" + r + "　句=" + (idx + 1)); return true; }
            devLog("speak(which=0) 返回=" + r + "（不算接管，换下一条）");
        } catch (Throwable e) {
            devLog("speak 失败：" + shortErr(e));
        }
        // 策略三：独立通道（点明朗读那条，双语音版，prefix 传空让它用默认）
        try {
            int r = s.speakIndependent("", "", text, 0, cb);
            if (r > 0) { devLog("speakIndependent 返回=" + r + "　句=" + (idx + 1)); return true; }
            devLog("speakIndependent 返回=" + r + "（不算接管）");
        } catch (Throwable e) {
            devLog("speakIndependent 失败：" + shortErr(e));
        }
        // 三条都没接住就老老实实返回 false，让调用方走系统 TTS。
        // 原来这里最后还有一条 SpeakNow（无返回值、无回调）：它一旦发出去就等于宣布接管，
        // 可若点明那边没出声，我们这边也不再念，就变成「完全不读」。宁可重复一次，不能哑掉。
        current = -1;
        return false;
    }

    /** 界面播报用这条：不打断正在念的正文 */
    public static boolean speakAside(String text) {
        ISpeakService s = svc;
        if (!connected || s == null || text == null || text.isEmpty()) return false;
        try {
            s.speakNowNoInterruptCurrent(text);
            devLog("插播（不打断正文）：" + shortText(text));
            return true;
        } catch (Throwable e) {
            devLog("插播失败：" + shortErr(e));
            return false;
        }
    }

    /** 暂停：两条独立停止都打一遍，哪条有效看日志 */
    public static void stopReading() {
        current = -1;
        reqSeq++;
        ISpeakService s = svc;
        if (!connected || s == null) return;
        try { s.stopIndependent(0, 0); devLog("已调 stopIndependent(0,0)"); } catch (Throwable e) { devLog("stopIndependent 失败：" + shortErr(e)); }
        try { s.stopCurrentReading(); devLog("已调 stopCurrentReading()"); } catch (Throwable e) { devLog("stopCurrentReading 失败：" + shortErr(e)); }
        try { s.clearRemainSpeakItems(); } catch (Throwable ignored) {}
    }

    /** 试一下配置读取，确认服务真的活着（结果只进日志） */
    public static void dumpConfig() {
        ISpeakService s = svc;
        if (!connected || s == null) return;
        try { devLog("getRemainSpeakItemCount=" + s.getRemainSpeakItemCount()); } catch (Throwable ignored) {}
        try { devLog("IsEvalVersion=" + s.IsEvalVersion()); } catch (Throwable ignored) {}
        try { devLog("GInt(speed)=" + s.GInt("speed", -1)); } catch (Throwable ignored) {}
    }

    private static void log(String tag, String msg) {
        final Host h = host;
        if (h == null) return;
        final String t = tag, m = msg;
        MAIN.post(new Runnable() { @Override public void run() { h.log(t, m); } });
    }

    private static void devLog(String msg) { log("点明", msg); }

    private static String shortErr(Throwable e) {
        String s = e.getClass().getSimpleName();
        String m = e.getMessage();
        if (m != null && !m.isEmpty()) s = s + ":" + (m.length() > 60 ? m.substring(0, 60) : m);
        return s;
    }

    private static String shortText(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }
}
