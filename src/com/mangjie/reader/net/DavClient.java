package com.mangjie.reader.net;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 自建 WebDAV 的最小客户端：只需要 GET / PUT 两种动作。
 * 两套配置分开：APP_* 是自家分发渠道（版本更新、日志上传），固定地址；
 * 用户备份服务器的地址、账号、密码、目录由设置里填，不填就不上传。
 */
public final class DavClient {

    /** 自家分发渠道：固定，不跟用户备份混用 */
    public static final String APP_BASE = "https://dav.mdmumu.fnos.net:443";
    /** 旧根目录：1.0.80 之前所有东西都堆在 /mangjie/ 下，搬家后留作兜底 */
    public static final String APP_DIR_OLD = "/mangjie/";
    /** 新目录：本 APP 独占，更新包、日志、书源都收到这里 */
    public static final String APP_DIR_NEW = "/mangjie/" + encPath("盲界阅读") + "/";
    /** 兼容旧引用 */
    public static final String APP_DIR = APP_DIR_NEW;
    public static final String APP_USER = "mangjie";
    public static final String APP_PASS = "mangjie1";

    private static String encPath(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    /** 用户备份服务器：运行时按设置填进来 */
    private static String base = "";
    private static String dir = "";
    private static String user = "";
    private static String pass = "";

    private static String lastErr;
    private static String lastUrl;

    private DavClient() {}

    /** 设置里改完就调一次；空值表示没配 */
    public static void configure(String b, String d, String u, String p) {
        base = b == null ? "" : b.trim();
        dir = d == null ? "" : d.trim();
        user = u == null ? "" : u.trim();
        pass = p == null ? "" : p.trim();
    }

    public static boolean configured() {
        return !base.isEmpty() && !user.isEmpty();
    }

    public static String lastError() { return lastErr; }

    public static String lastUrl() { return lastUrl; }

    private static String authHeader(String u, String p) {
        return "Basic " + Base64.encodeToString(
                (u + ":" + p).getBytes(java.nio.charset.Charset.forName("UTF-8")),
                Base64.NO_WRAP);
    }

    /** 读用户备份服务器上的文本；失败返回 null */
    public static String get(String path) {
        if (!configured()) {
            lastErr = "还没填服务器地址";
            return null;
        }
        return get0(base, dir, user, pass, path);
    }

    /** 读自家分发渠道上的文本（版本检查、更新日志） */
    public static String getApp(String path) {
        return get0(APP_BASE, APP_DIR, APP_USER, APP_PASS, path);
    }

    /**
     * 读自家分发渠道，先新目录、读不到再退旧根目录。
     * 服务器搬家的过渡期两边都留着内容，这样无论服务器搬到哪一步，App 都能读到。
     * rel 传相对路径（如 "version.txt"、"日志/更新日志/更新日志.txt"）。
     */
    public static String getAppAny(String rel) {
        if (rel == null) return null;
        if (rel.startsWith("http") || rel.startsWith("/")) return getApp(rel);
        String t = get0(APP_BASE, APP_DIR_NEW, APP_USER, APP_PASS, rel);
        if (t != null) return t;
        return get0(APP_BASE, APP_DIR_OLD, APP_USER, APP_PASS, rel);
    }

    /** 写自家分发渠道，先新目录、新目录写不进再退旧根目录 */
    public static boolean putAppAny(String rel, byte[] data) {
        if (rel == null) return false;
        if (rel.startsWith("http") || rel.startsWith("/")) return putApp(rel, data);
        if (put0(APP_BASE, APP_USER, APP_PASS, APP_DIR_NEW + rel, data)) return true;
        return put0(APP_BASE, APP_USER, APP_PASS, APP_DIR_OLD + rel, data);
    }

    /** 新目录下某个子目录（已编码） */
    public static String newSub(String encName) {
        return APP_DIR_NEW + encName + "/";
    }

    private static String get0(String b, String d, String u, String p, String path) {
        lastErr = null;
        lastUrl = null;
        try {
            String full = path.startsWith("http") ? path
                    : (b + (path.startsWith("/") ? path : (d.isEmpty() ? "/" : d) + path));
            lastUrl = full;
            HttpURLConnection c = (HttpURLConnection) new URL(full).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Authorization", authHeader(u, p));
            c.setRequestProperty("User-Agent", "MangjieReader/1.0");
            int code = c.getResponseCode();
            if (code != 200) {
                String auth = c.getHeaderField("WWW-Authenticate");
                lastErr = "HTTP " + code + "（" + full + "）" + (auth == null ? "" : " 认证头：" + auth);
                return null;
            }
            InputStream is = c.getInputStream();
            String ret = readAll(is);
            is.close();
            return ret;
        } catch (Exception e) {
            lastErr = e.getClass().getSimpleName() + "：" + e.getMessage();
            return null;
        }
    }

    /** 上传到用户备份服务器；成功返回 true */
    public static boolean put(String path, byte[] data) {
        if (!configured()) {
            lastErr = "还没填服务器地址";
            return false;
        }
        return put0(base, user, pass, path, data);
    }

    /** 上传到自家分发渠道（日志） */
    public static boolean putApp(String path, byte[] data) {
        return put0(APP_BASE, APP_USER, APP_PASS, path, data);
    }

    private static boolean put0(String b, String u, String p, String path, byte[] data) {
        lastErr = null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(b + path).openConnection();
            c.setRequestMethod("PUT");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Authorization", authHeader(u, p));
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.close();
            int code = c.getResponseCode();
            if (code != 200 && code != 201 && code != 204) {
                lastErr = "HTTP " + code;
                return false;
            }
            return true;
        } catch (Exception e) {
            lastErr = e.getClass().getSimpleName() + "：" + e.getMessage();
            return false;
        }
    }

    /** 删自家分发渠道上的文件；成功（含本来就不存在）返回 true */
    public static boolean deleteApp(String path) {
        lastErr = null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(APP_BASE + path).openConnection();
            c.setRequestMethod("DELETE");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Authorization", authHeader(APP_USER, APP_PASS));
            int code = c.getResponseCode();
            if (code == 200 || code == 201 || code == 204 || code == 404) return true;
            lastErr = "HTTP " + code;
            return false;
        } catch (Exception e) {
            lastErr = e.getClass().getSimpleName() + "：" + e.getMessage();
            return false;
        }
    }

    /** 把文件传上去（用户备份服务器） */
    public static boolean putFile(String path, File f) {
        try {
            byte[] data = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n <= 0) break;
                read += n;
            }
            in.close();
            return put(path, data);
        } catch (Exception e) {
            lastErr = e.getClass().getSimpleName() + "：" + e.getMessage();
            return false;
        }
    }

    private static String readAll(InputStream is) throws java.io.IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder t = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) t.append(line).append("\n");
        r.close();
        return t.toString();
    }
}
