package com.mangjie.reader.net;

import android.app.ActivityManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 无障碍日志：设备信息拼装、上传到服务器、按档位自动上传。写日志本身仍在宿主。
 *
 *  三档（宿主用 logMode 表示，模块通过 Host 的 logNetOn / devModeOn 读取）：
 *    0 关闭     —— 不自动传，只在用户手动点「上传日志」时传
 *    1 正式档   —— 只在出现没传过的异常时补传，且只在 WiFi 下传（默认）
 *    2 开发档   —— 每 5 分钟检查一次，日志有新增就传（原来的行为）
 */
public final class LogUploader {

    public interface Host {
        void log(String tag, String msg);
        void speak(String msg);
        void toast(String msg);
        android.os.Handler ui();
        String dataDir();
        String deviceShort();
        String appVersion();
        android.util.DisplayMetrics metrics();
        String extRoot();
        ActivityManager.MemoryInfo memInfo();
        String davBase();
        String logDir();
        String davLastErr();
        void davMkcol(String dir);
        boolean davPut(String url, byte[] data);
        void refreshTop(String msg);
        boolean autoUploadOn();
        /** 自动上传是否开着（档位 > 0） */
        boolean logNetOn();
        /** 是否开发档（档位 == 2，允许周期上传） */
        boolean devModeOn();
        /** 是否连在 WiFi 上，正式档只在 WiFi 下传 */
        boolean onWifi();
        /** 上次成功上传时日志文件的字符数，跨启动保存在 prefs */
        long savedUploadedChars();
        /** 记下这次上传的字符数，跨启动保存 */
        void saveUploadedChars(long chars);
        /** 日志头里的无障碍环境：读屏、朗读引擎、界面引擎等，多行，每行带换行符 */
        String a11yInfo();
        /** 设备短标识，只含 ASCII 字母数字下划线与横线，用于日志文件名 */
        String deviceTag();
    }

    private final Host host;
    private Handler autoUploadHandler = null;
    /** 与日志文件内容同口径（字符数），不是字节数 */
    private long lastUploadedChars = 0;

    public LogUploader(Host host) {
        this.host = host;
        try { this.lastUploadedChars = host.savedUploadedChars(); } catch (Throwable ignored) {}
    }

    private byte[] readFile(File f) throws java.io.IOException {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    public String deviceInfoText() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 设备信息 =====\n");
        sb.append("手机型号：").append(host.deviceShort()).append("\n");
        sb.append("品牌：").append(Build.MANUFACTURER).append("\n");
        sb.append("型号：").append(Build.MODEL).append("\n");
        sb.append("系统：Android ").append(Build.VERSION.RELEASE)
          .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
        sb.append("应用版本：").append(host.appVersion()).append("\n");
        try {
            android.util.DisplayMetrics dm = host.metrics();
            sb.append("屏幕：").append(dm.widthPixels).append("×").append(dm.heightPixels)
              .append("，密度 ").append(dm.density).append("\n");
        } catch (Exception ignored) {}
        try {
            long total = 0, avail = 0;
            if (Build.VERSION.SDK_INT >= 18) {
                android.os.StatFs sf = new android.os.StatFs(host.extRoot());
                total = sf.getTotalBytes();
                avail = sf.getAvailableBytes();
            }
            sb.append("存储（内部存储）：总 ").append(com.mangjie.reader.reader.TextUtil.sizeText(total))
              .append("，可用 ").append(com.mangjie.reader.reader.TextUtil.sizeText(avail)).append("\n");
        } catch (Exception ignored) {}
        try {
            ActivityManager.MemoryInfo mi = host.memInfo();
            sb.append("运行内存：总 ").append(com.mangjie.reader.reader.TextUtil.sizeText(mi.totalMem))
              .append("，可用 ").append(com.mangjie.reader.reader.TextUtil.sizeText(mi.availMem)).append("\n");
        } catch (Exception ignored) {}
        try {
            // 多台设备批量测的时候，能不能分组全靠这几行：同一句「读不了」，
            // 是读屏在抢、是引擎不吃队列、还是没装中文语音包，看这里就能分开
            String a11y = host.a11yInfo();
            if (a11y != null && a11y.length() > 0) sb.append(a11y);
        } catch (Throwable ignored) {}
        sb.append("====================\n");
        return sb.toString();
    }

    public String logSizeText() {
        File f = new File(host.dataDir(), "无障碍日志.txt");
        if (!f.exists()) return "无";
        long len = f.length();
        return len < 1024 ? (len + " 字节") : (String.format(Locale.US, "%.1f KB", len / 1024f));
    }

    /** 日志文件当前的字符数；读不到返回 -1 */
    private long logChars(File f) {
        try {
            return new String(readFile(f), "UTF-8").length();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 只在日志比上次传出去的内容更长时才传（跨启动有效，不会每次开机都白传一遍） */
    public void uploadIfNew(boolean announce) {
        // 档位 0 是「关闭」：原来这里不查档位，关了照样每次启动往服务器传一份，等于关不掉
        if (!host.logNetOn()) {
            if (announce) { host.toast("日志上传已关闭"); host.speak("日志上传已关闭"); }
            return;
        }
        File f = new File(host.dataDir(), "无障碍日志.txt");
        if (!f.exists() || f.length() == 0) {
            if (announce) { host.toast("还没有日志可上传"); host.speak("还没有日志可上传"); }
            return;
        }
        long chars = logChars(f);
        if (chars >= 0 && chars <= lastUploadedChars) {
            host.log("更新", "日志无新增，跳过上传　" + chars + " 字");
            return;
        }
        uploadLog(announce);
    }

    public void uploadLog(final boolean announce) {
        final File f = new File(host.dataDir(), "无障碍日志.txt");
        if (!f.exists() || f.length() == 0) {
            if (announce) { host.toast("还没有日志可上传"); host.speak("还没有日志可上传"); }
            return;
        }
        if (announce) { host.toast("正在上传日志"); host.speak("正在上传日志"); }
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                String err = null, url = null;
                long uploadedChars = -1;
                try {
                    // 固定名 + 覆盖：一台设备只占服务器上一个文件。
                    // 原来用时间戳，每次启动多传一份全量快照，二十台机器几天就是几百份重复内容
                    String name = "a11y-" + host.deviceTag() + ".txt";
                    String body = new String(readFile(f), "UTF-8");
                    uploadedChars = body.length();
                    byte[] data = (deviceInfoText() + "\n" + body).getBytes("UTF-8");
                    // 新目录优先；后面三个是搬家前后的老位置，都试一遍再报错
                    String[] dirs = { host.logDir(), "/mangjie/%E6%97%A5%E5%BF%97/", "/mangjie/logs/", "/mangjie/" };
                    boolean ok = false;
                    String lastErr = null;
                    int tries = 0;
                    // 网络刚切换时常常域名解析失败，隔几秒再试，最多三次
                    for (int attempt = 1; attempt <= 3 && !ok; attempt++) {
                        tries = attempt;
                        if (attempt > 1) {
                            try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
                        }
                        for (String d : dirs) {
                            host.davMkcol(d);
                            String tryUrl = d + name;
                            if (host.davPut(tryUrl, data)) { ok = true; url = tryUrl; break; }
                            lastErr = host.davLastErr();
                        }
                    }
                    if (!ok) {
                        err = (lastErr == null ? "上传失败" : lastErr)
                                + (tries > 1 ? "（已重试 " + (tries - 1) + " 次）" : "");
                    }
                    else url = host.davBase() + url;
                } catch (Exception e) { err = e.getClass().getSimpleName() + "：" + e.getMessage(); }
                final String fUrl = url, fErr = err;
                final long fChars = uploadedChars;
                host.ui().post(new Runnable() { @Override public void run() {
                    if (fErr != null) {
                        host.log("更新", "日志上传失败　" + fErr);
                        if (announce) { host.toast("上传失败：" + fErr); host.speak("上传失败，" + fErr); }
                        return;
                    }
                    if (fChars > 0) {
                        lastUploadedChars = fChars;
                        host.saveUploadedChars(fChars);
                    }
                    if (announce) {
                        host.toast("日志已上传：" + fUrl);
                        host.refreshTop("日志已上传");   // refreshTop 自己会播报，不要再说一遍
                    }
                } });
            }
        });
    }

    /** 开发档：开启后 1 分钟先查一次，之后每 5 分钟查一次，有新增才传。正式档不调用这个方法。 */
    public void startAutoUpload() {
        stopAutoUpload();
        autoUploadHandler = new Handler(Looper.getMainLooper());
        autoUploadHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (host.autoUploadOn()) uploadIfNew(false);
                if (host.autoUploadOn() && autoUploadHandler != null) {
                    autoUploadHandler.postDelayed(this, 5 * 60 * 1000L);
                }
            }
        }, 60 * 1000L);
    }

    public void stopAutoUpload() {
        if (autoUploadHandler != null) { autoUploadHandler.removeCallbacksAndMessages(null); autoUploadHandler = null; }
    }

    /** 崩溃后补传：日志里出现上次没传过的 [异常] 才传；正式档还要求 WiFi。
     *  读文件用 TextFile（java.nio.file 在 API 24/25 上不存在），比较用字符下标对字符数，口径一致。 */
    public void uploadCrashIfAny() {
        try {
            if (!host.logNetOn()) return;
            if (!host.devModeOn() && !host.onWifi()) return;
            File f = new File(host.dataDir(), "无障碍日志.txt");
            if (!f.exists()) return;
            String all = com.mangjie.reader.reader.TextFile.read(f, 4 * 1024 * 1024);
            if (all == null) return;
            int at = all.lastIndexOf("[异常]");
            if (at < 0) return;
            if (at < lastUploadedChars) {
                host.log("更新", "异常段已传过，跳过　异常位置 " + at + "　已传 " + lastUploadedChars);
                return;
            }
            host.log("更新", "发现未上传的异常，补传日志　位置 " + at + "　已传 " + lastUploadedChars);
            uploadLog(true);
        } catch (Throwable ignored) {}
    }
}
