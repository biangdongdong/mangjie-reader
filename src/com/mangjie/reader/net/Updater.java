package com.mangjie.reader.net;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/** 升级通道：读 version.txt 判版本、下载安装包、changelog 拉取解析、旧包清理。装包动作留给宿主。 */
public final class Updater {

    public interface Host {
        void log(String tag, String msg);
        void speak(String msg);

        /** 不打断正文的播报：进度类提示走这条，默认退化成普通播报 */
        default void speakQueued(String msg) { speak(msg); }
        void toast(String msg);
        android.os.Handler ui();
        String davGet(String name);
        String davLastErr();
        String davDir();
        String davBase();
        String davUser();
        String davPass();
        File apkDir();
        String dataDir();
        String changelogPath();
        String changelogPathOld();
        String davLastUrl();
        String changelogCache();
        void refreshChangelogPage();
        String appVersion();
        String localApkFor(String ver);
        void installApk(String path);
    }

    private final Host host;

    public Updater(Host host) { this.host = host; }

    /** 版本号比较沿用 Version 的那套，避免两处各写一份 */
    static int compareVer(String a, String b) { return Version.compare(a, b); }

    public void checkUpdate(final boolean silent) {
        if (!silent) {
            host.toast("正在检查更新");
            host.speak("正在检查更新");
        } else {
            host.log("更新", "启动自动检查更新");
        }
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                String ver = null, apkHref = null, err = null;
                try {
                    // 直接读 version.txt（比 PROPFIND 稳，服务器对 PROPFIND 的写法比较挑）
                    String txt = host.davGet("version.txt");
                    if (txt == null) {
                        err = host.davLastErr() == null ? "读不到 version.txt" : host.davLastErr();
                    } else {
                        txt = txt.trim();
                        if (!Version.looksLikeVersion(txt)) err = "version.txt 内容异常：" + txt;
                        else {
                            ver = txt;
                            apkHref = host.davDir() + java.net.URLEncoder.encode("盲界阅读-demo-" + ver + ".apk", "UTF-8");
                        }
                    }
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + "：" + e.getMessage();
                }
                final String fVer = ver, fHref = apkHref, fErr = err;
                final boolean quiet = silent;
                host.ui().post(new Runnable() { @Override public void run() {
                    if (fErr != null) {
                        if (!quiet) {
                            host.toast("检查更新失败：" + fErr);
                            host.speak("检查更新失败，" + fErr);
                        }
                        host.log("更新", "失败　" + fErr);
                        return;
                    }
                    String cur = host.appVersion();
                    host.log("更新", "服务器版本 " + fVer + "，本机 " + cur);
                    if (compareVer(fVer, cur) <= 0) {
                        if (!quiet) {
                            host.toast("已是最新版本 v" + cur);
                            host.speak("已是最新版本 " + cur);
                        }
                        host.log("更新", "已是最新　本机 v" + cur);
                    } else {
                        String local = host.localApkFor(fVer);
                        if (local != null) {
                            host.toast("发现新版本 v" + fVer + "，本机已下载过，直接安装");
                            host.speak("发现新版本 " + fVer + "，已下载过，直接安装");
                            host.log("更新", "复用本地安装包 " + local);
                            host.installApk(local);
                        } else {
                            host.toast("发现新版本 v" + fVer + "，开始下载");
                            host.speak("发现新版本 " + fVer + "，开始下载");
                            host.log("更新", "自动下载 v" + fVer);
                            downloadApk(fHref, fVer, "demo-" + fVer);
                        }
                    }
                } });
            }
        });
    }

    public void downloadApk(final String href, final String ver, final String name) {
        if (href == null) return;
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                String savedPath = null, err = null;
                try {
                    // 新目录优先，再退旧根目录（服务器搬家过渡期两边都有包）
                    java.util.List<String> candList = new java.util.ArrayList<>();
                    for (String one : com.mangjie.reader.net.Version.apkCandidates(null, DavClient.APP_DIR_NEW, ver))
                        if (one != null) candList.add(one);
                    for (String one : com.mangjie.reader.net.Version.apkCandidates(href, host.davDir(), ver))
                        if (one != null && !candList.contains(one)) candList.add(one);
                    for (String one : com.mangjie.reader.net.Version.apkCandidates(null, DavClient.APP_DIR_OLD, ver))
                        if (one != null && !candList.contains(one)) candList.add(one);
                    String[] candidates = candList.toArray(new String[0]);
                    java.net.HttpURLConnection c = null;
                    int code = 0;
                    for (String cand : candidates) {
                        if (cand == null) continue;
                        try {
                            java.net.URL u = new java.net.URL(host.davBase() + cand);
                            c = (java.net.HttpURLConnection) u.openConnection();
                            c.setConnectTimeout(15000);
                            c.setReadTimeout(60000);
                            c.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString(
                                    (host.davUser() + ":" + host.davPass()).getBytes("UTF-8"), android.util.Base64.NO_WRAP));
                            c.setRequestProperty("User-Agent", "MangjieReader/1.0");
                            c.connect();
                            code = c.getResponseCode();
                            if (code == 200) break;
                        } catch (Exception ignore) { code = 0; }
                    }
                    if (code != 200 || c == null) throw new java.io.IOException("HTTP " + code);
                    // 优先写应用自己的外部目录（不需要「所有文件访问」权限，一定能写）
                    File dir = host.apkDir();
                    if (dir == null) dir = new File(host.dataDir());
                    if (!dir.exists()) dir.mkdirs();
                    if (!dir.canWrite()) throw new java.io.IOException("目录不可写：" + dir.getAbsolutePath());
                    File out = new File(dir, "update-v" + ver + ".apk");

            java.io.InputStream is = c.getInputStream();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int n;
                    long total = c.getContentLength();
                    long got = 0;
                    int lastPct = -1;
                    while ((n = is.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        got += n;
                        if (total > 0) {                       // 每过 25% 报一声，别刷屏
                            int pct = (int) (got * 100 / total);
                            if (pct >= lastPct + 25) {
                                lastPct = pct;
                                com.mangjie.reader.tools.TaskProgress.update(pct);
                                final int ps = pct;
                                host.ui().post(new Runnable() { @Override public void run() {
                                    host.speakQueued("下载 " + ps + " 百分比");
                                } });
                            }
                        }
                    }
                    fos.close();
                    is.close();
                    com.mangjie.reader.tools.TaskProgress.finish();
                    savedPath = out.getAbsolutePath();
                } catch (Exception e) { err = e.getClass().getSimpleName() + "：" + e.getMessage(); }
                final String p = savedPath, e2 = err;
                host.ui().post(new Runnable() { @Override public void run() {
                    if (e2 != null || p == null) {
                        host.toast("下载失败：" + e2);
                        host.speak("下载失败，" + e2);
                        host.log("更新", "下载失败　" + e2);
                        return;
                    }
                    host.toast("下载完成，准备安装");
                    host.speak("下载完成，准备安装");
                    host.installApk(p);
                } });
            }
        });
    }

    public void fetchChangelog(final boolean interactive) {
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                String b = host.davGet(host.changelogPath());
                if (b == null || b.trim().isEmpty()) b = host.davGet(host.changelogPathOld());   // 旧路径兜底
                final String body = b;
                host.ui().post(new Runnable() { @Override public void run() {
                    if (body == null || body.trim().isEmpty()) {
                        host.log("更新日志", "拉取失败：" + host.davLastErr() + "  请求地址：" + host.davLastUrl());
                        if (interactive) host.speak("更新日志拉取失败，先显示本地那份");
                        return;
                    }
                    try {
                        FileWriter w = new FileWriter(host.changelogCache());
                        w.write(body);
                        w.close();
                        host.log("更新日志", "已更新本地缓存，长度 " + body.length());
                    } catch (Exception e) {
                        host.log("更新日志", "写缓存失败：" + e);
                    }
                    if (interactive) {
                        host.refreshChangelogPage();
                    }
                } });
            }
        });
    }

    public List<String[]> parseChangelog(String text) {
        List<String[]> out = new ArrayList<>();
        if (text == null) return out;
        String ver = null;
        StringBuilder body = new StringBuilder();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.matches("^v?[0-9]+\\.[0-9]+\\.[0-9]+$")) {
                if (ver != null) out.add(new String[]{ver, body.toString().trim()});
                ver = line.startsWith("v") ? line.substring(1) : line;
                body.setLength(0);
            } else if (ver != null) {
                String t = line.startsWith("-") || line.startsWith("•") ? line.substring(1).trim() : line;
                if (body.length() > 0) body.append("\n");
                body.append(t);
            }
        }
        if (ver != null) out.add(new String[]{ver, body.toString().trim()});
        return out;
    }

    public List<String[]> loadChangelog() {
        try {
            File f = new File(host.changelogCache());
            if (f.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                StringBuilder t = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) t.append(line).append("\n");
                r.close();
                List<String[]> remote = parseChangelog(t.toString());
                if (!remote.isEmpty()) return remote;
            }
        } catch (Exception e) {
            host.log("更新日志", "读缓存失败：" + e);
        }
        return new ArrayList<>();
    }

    public void cleanupOldApks() {
        try {
            File dir = host.apkDir();
            if (dir == null) dir = new File(host.dataDir());
            File[] fs = dir.listFiles();
            if (fs == null) return;
            String cur = host.appVersion();
            for (File f : fs) {
                String n = f.getName();
                if (!n.startsWith("update-v") || !n.endsWith(".apk")) continue;
                String v = n.substring("update-v".length(), n.length() - 4);
                if (compareVer(v, cur) <= 0) {
                    if (f.delete()) host.log("更新", "清理旧安装包 " + n);
                }
            }
        } catch (Throwable ignored) {}
    }
}
