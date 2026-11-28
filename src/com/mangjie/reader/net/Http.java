/*
 * 本文件的部分实现参照「开源阅读 Legado」（https://github.com/LegadoTeam/legado）。
 * Legado：Copyright (C) 2019-2023 gedoor，GNU General Public License v3.0。
 * 本工程对其的使用方式与修改说明见仓库根目录 NOTICE，完整条款见 LICENSE。
 */
package com.mangjie.reader.net;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 网络层：URL 拼接、GET/POST、超时、重定向、Cookie 罐子。不碰界面。 */
public final class Http {

    /** 宿主只提供三件事：日志、主机名解析、版本号 */
    public interface Host {
        void log(String tag, String msg);
        String hostOf(String url);
        String appVersion();

        /** URL 模板里的 @js / {{...}} 求值；宿主没接这份能力就退化成空串 */
        default String evalJs(String code, String result) { return ""; }
    }

    private final Host host;
    private final Map<String, Map<String, String>> jarMap = new HashMap<>();

    /** 当前书源的 header（书源 JSON 里的 "header" 字段）：UA / Referer / Cookie 等，对齐 Legado 的 source.getHeaderMap() */
    private Map<String, String> sourceHeaders = new HashMap<>();

    public void setSourceHeader(Map<String, String> h) {
        sourceHeaders = h == null ? new HashMap<String, String>() : h;
    }

    public Map<String, String> getSourceHeader() { return sourceHeaders; }

    /** 把书源 header 字段（JSON 对象字符串）解析成 map；不是 JSON 就返回空 */
    public static Map<String, String> parseHeader(String json) {
        Map<String, String> out = new HashMap<>();
        if (json == null) return out;
        String t = json.trim();
        if (!t.startsWith("{")) return out;
        try {
            org.json.JSONObject o = new org.json.JSONObject(t);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.opt(k);
                if (k != null && v != null && !v.toString().trim().isEmpty()) out.put(k, v.toString());
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 最近一次请求拿到的 HTTP 状态码；0 表示还没发过。失败提示要拿它说清原因 */
    public volatile int lastStatus = 0;

    public Http(Host host) { this.host = host; }

    public String requestUrl(String spec, String base) { return requestUrlT(spec, base, 0); }

    /** 当前搜索上下文：{{key}} / {{page}} 靠它求值；翻页前由调用方设，和 Legado 的用法一致 */
    private volatile String ctxKey = "";
    private volatile int ctxPage = 1;

    public void setCtx(String key, int page) {
        this.ctxKey = key == null ? "" : key;
        this.ctxPage = page <= 0 ? 1 : page;
    }

    /** timeoutMs 传 0 用默认（连 15 秒、读 25 秒）；给了就按这个值卡，慢源不再拖死人 */
    public String requestUrlT(String spec, String base, int timeoutMs) {
        Resp r = fetch(spec, base, timeoutMs);
        return r == null ? null : r.body;
    }

    /**
     * 一次请求的结果：状态码、最终地址、耗时、错误类型都随这次调用返回。
     * 以前只有一个全局 lastStatus，并发抓多源时会串号，失败原因张冠李戴。
     */
    public static final class Resp {
        public String body;
        public int code = 0;
        public String url = "";
        public String err = "";
        /** 3xx 时响应头里的 Location（源里写 followRedirects:false 时有意义） */
        public String location = "";
        public long ms = 0;
        public boolean ok() { return code >= 200 && code < 300 && body != null; }
    }

    public Resp fetch(String spec, String base) { return fetch(spec, base, 0); }

    public Resp fetch(String spec, String base, int timeoutMs) {
        // 1.1.52：整条重定向链共享一份预算。以前每跳都重新拿一个完整的 timeout，
        // 跳两次就是两个 8 秒——搜索日志里单源 14020ms 就是这么出来的
        return fetch0(spec, base, timeoutMs, timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0L);
    }

    private Resp fetch0(String spec, String base, int timeoutMs, long deadline) {
        final Resp out = new Resp();
        final long t0 = System.currentTimeMillis();
        UrlResolver.Out ro = UrlResolver.resolve(spec, base, ctxKey, ctxPage, new UrlResolver.Js() {
            @Override public String eval(String code, String result) { return host.evalJs(code, result); }
        });
        String url = ro.url == null ? "" : ro.url.trim();
        out.url = url;
        if (url.isEmpty()) {
            host.log("书源", "URL 模板解析成空地址，跳过：" + spec);
            out.err = "模板解析成空地址";
            out.ms = System.currentTimeMillis() - t0;
            return out;
        }
        if (!ro.notes.isEmpty()) host.log("书源", "URL 解析 " + url + "：" + ro.notes);
        String method = ro.method == null || ro.method.isEmpty() ? "GET" : ro.method.toUpperCase(Locale.ROOT);
        String charset = ro.charset == null ? "" : ro.charset.trim();
        // "escape" 只是"不按字符集编"的标记，不是真编码名，别拿去解码
        if ("escape".equalsIgnoreCase(charset)) charset = "";
        String body = null;
        if ("POST".equals(method) || "PUT".equals(method)) {
            body = ro.encodedForm.isEmpty() ? ro.body : ro.encodedForm;
            if (body == null) body = "";
        }
        int retryTimes = ro.retry;
        if (retryTimes < 0) retryTimes = 0;
        if (retryTimes > 3) retryTimes = 3;
        java.util.Map<String, String> headers = ro.headerMap;

        int attempt = 0;
        // 403/429/503 一次补 Referer 重试的机会（不少站点只缺这个头就放行）
        boolean triedRef = false;
        while (true) {
        attempt++;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            // 源里写了 timeout 就按源里来（夹在 1～60 秒，免得一个坏源把线程卡死）
            int effMs = timeoutMs > 0 ? timeoutMs
                    : (ro.timeout > 0 ? Math.max(1000, Math.min(ro.timeout, 60000)) : 0);
            if (deadline > 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    out.err = "超时（重定向链用完预算）";
                    out.ms = System.currentTimeMillis() - t0;
                    return out;
                }
                effMs = effMs > 0 ? (int) Math.min(effMs, left) : (int) left;
            }
            c.setConnectTimeout(effMs > 0 ? effMs : 15000);
            // 读超时不再翻倍：以前传 30 秒实际会等 60 秒，搜索里一个坏源就卡住两个线程位
            c.setReadTimeout(effMs > 0 ? effMs : 25000);
            c.setInstanceFollowRedirects(ro.followRedirects);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "text/html,application/json,*/*");
            // 第二次尝试补同站 Referer：不少站点的 403 只是缺这个头，源里又没写
            if (triedRef && base != null && base.startsWith("http")) {
                try { c.setRequestProperty("Referer", base); } catch (Exception ignored) {}
            }
            // 先铺书源 header（对齐 Legado：source.getHeaderMap 在最前面，URL 自带的 header 后覆盖）
            java.util.Map<String, String> sh = sourceHeaders;
            if (sh != null && !sh.isEmpty()) {
                for (java.util.Map.Entry<String, String> h : sh.entrySet()) {
                    if (h.getKey() == null) continue;
                    // 不跟服务端协商压缩：源里写的 Accept-Encoding 常带 br，而系统没有 brotli
                    // 解码器，拿到就是一串解不开的字节。统一要明文，省掉一层不确定。
                    if (h.getKey().trim().equalsIgnoreCase("Accept-Encoding")) continue;
                    String v = h.getValue();
                    if (v == null) continue;
                    // header 里写 {{var}} 的先不展开，交给 UrlResolver 的变量表已处理过的就原样用
                    try { c.setRequestProperty(h.getKey(), v); } catch (Exception ignored) {}
                }
            }
            if (headers != null && !headers.isEmpty()) {
                for (java.util.Map.Entry<String, String> h : headers.entrySet()) {
                    if (h.getKey() == null) continue;
                    if (h.getKey().trim().equalsIgnoreCase("Accept-Encoding")) continue;   // 同上，别去要 br
                    try { c.setRequestProperty(h.getKey(), h.getValue() == null ? "" : h.getValue()); } catch (Exception ignored) {}
                }
            }
            // cookie 罐子里的东西要真的送出去（以前只存不送，风控/登录源必挂）
            if (!headersContains(headers, "Cookie") && !headersContains(sh, "Cookie")) {
                java.util.Map<String, String> jar = cookieFor(url);
                if (jar != null && !jar.isEmpty()) {
                    StringBuilder ck = new StringBuilder();
                    for (java.util.Map.Entry<String, String> e : jar.entrySet()) {
                        if (ck.length() > 0) ck.append("; ");
                        ck.append(e.getKey()).append("=").append(e.getValue());
                    }
                    if (ck.length() > 0) c.setRequestProperty("Cookie", ck.toString());
                }
            }
            if ("POST".equals(method) || "PUT".equals(method)) {
                c.setRequestMethod(method);
                c.setDoOutput(true);
                if (body == null) body = "";
                if (!headersContains(headers, "Content-Type")) {
                    // 对齐 Legado 的三分支：表单走 urlencoded，body 本身就是 JSON 的按 JSON 发
                    String t = body.trim();
                    boolean jsonBody = (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
                    c.setRequestProperty("Content-Type", jsonBody
                            ? "application/json" + (charset.isEmpty() ? "" : "; charset=" + charset)
                            : "application/x-www-form-urlencoded" + (charset.isEmpty() ? "" : "; charset=" + charset));
                }
                byte[] bb;
                try { bb = body.getBytes(charset.isEmpty() ? "UTF-8" : charset); }
                catch (Exception e) { bb = body.getBytes("UTF-8"); }
                c.setRequestProperty("Content-Length", String.valueOf(bb.length));
                java.io.OutputStream os = c.getOutputStream();
                os.write(bb);
                os.flush();
                os.close();
            } else if ("HEAD".equals(method)) {
                c.setRequestMethod("HEAD");
            }
            int code = c.getResponseCode();
            lastStatus = code;
            out.code = code;
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                out.location = loc == null ? "" : loc;
                if (loc != null && ro.followRedirects) {
                    // 递归也要带上同一个 deadline：整条链只在总预算里跑
                    Resp rr = fetch0(loc.startsWith("http") ? loc : new java.net.URL(new java.net.URL(url), loc).toString(), base, timeoutMs, deadline);
                    rr.ms = System.currentTimeMillis() - t0;
                    return rr;
                }
            }
            // 2xx 都算拿到内容：202（站点排队处理）、206（分段）曾被杀掉当失败
            if (code < 200 || code >= 300) {
                // 403/429/503 先别判死：多半是缺 Referer 或短暂限流，补一次再试
                if ((code == 403 || code == 429 || code == 503) && !triedRef) {
                    triedRef = true;
                    host.log("听书", "HTTP " + code + " " + url + " 补 Referer 重试");
                    try { Thread.sleep(400L); } catch (Throwable ignored) {}
                    continue;
                }
                host.log("听书", "HTTP " + code + " " + url + " 第" + attempt + "次");
                if (attempt <= retryTimes) { try { Thread.sleep(600L * attempt); } catch (Throwable ignored) {} continue; }
                out.err = "HTTP " + code;
                out.ms = System.currentTimeMillis() - t0;
                return out;
            }
            saveCookies(url, c);
            java.io.InputStream is = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            byte[] data = bos.toByteArray();
            // 服务端压过、但没走 Content-Encoding 透明解压的，先在这一层把字节还原。
            // 不解的话，往下不管按 HTML 还是按 JSON 取，都只能得到一串乱码。
            byte[] plain = BodyCodec.unwrapBytes(data);
            if (plain != data) {
                host.log("听书", "响应体是压缩字节（gzip/deflate），已就地解开：" + url);
                data = plain;
            }
            String cs = charset;
            if (cs == null || cs.isEmpty()) {
                String ct = c.getContentType();
                if (ct != null && ct.toLowerCase(Locale.ROOT).contains("charset=")) {
                    cs = ct.substring(ct.toLowerCase(Locale.ROOT).indexOf("charset=") + 8).split(";")[0].trim();
                }
            }
            out.body = decodeBytes(data, cs);
            // 整串 base64、里层还是 gzip 的（H4sI…），解出来才是正文；
            // N4Ig… 那类是 brotli，本机没有解码器，认出来记一句，别让它伪装成 JSON 去解析。
            String unwrapped = BodyCodec.unwrapText(out.body);
            if (unwrapped != null) {
                host.log("听书", "响应体是 base64 包着的 gzip，已还原：" + url);
                out.body = unwrapped;
            } else if (BodyCodec.looksBrotliBase64(out.body)) {
                host.log("听书", "响应体是 brotli 压缩的 base64，本机无解码器，按不可解析处理：" + url);
            }
            // 对齐 Legado StrResponse.url()：重定向后要拿最后一跳的地址。
            // 这里一直是 out.url = url（请求前那个），相对链接的基准就全错了。
            try {
                java.net.URL fin = c.getURL();
                out.url = fin == null ? url : fin.toString();
            } catch (Throwable t) {
                out.url = url;
            }
            out.ms = System.currentTimeMillis() - t0;
            return out;
        } catch (Exception e) {
            host.log("听书", "请求失败 " + url + "：" + e.getClass().getSimpleName() + " 第" + attempt + "次");
            if (attempt <= retryTimes) { try { Thread.sleep(600L * attempt); } catch (Throwable ignored) {} continue; }
            out.err = errName(e);
            out.ms = System.currentTimeMillis() - t0;
            return out;
        }
        }
    }

    /** 把异常翻成人能看的原因（超时/域名解析/连不上/证书/其它） */
    static String errName(Throwable e) {
        String s = e == null ? "" : (e.getClass().getSimpleName() + " " + (e.getMessage() == null ? "" : e.getMessage()));
        String t = s.toLowerCase(Locale.ROOT);
        if (t.contains("sockettimeout") || t.contains("timed out") || t.contains("timeout")) return "超时";
        if (t.contains("unknownhost") || t.contains("unable to resolve") || t.contains("no address")) return "域名解析失败";
        if (t.contains("ssl") || t.contains("certificate")) return "证书不对";
        if (t.contains("connect") || t.contains("econnrefused") || t.contains("network is unreachable")) return "连不上";
        return "请求失败（" + e.getClass().getSimpleName() + "）";
    }

    private static boolean headersContains(java.util.Map<String, String> m, String name) {
        if (m == null) return false;
        for (String k : m.keySet()) if (k != null && k.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** 按声明编码解；没声明就读页面 meta；再没有就按字节打分猜 */
    String decodeBytes(byte[] data, String declared) {
        String cs = declared;
        if (cs == null || cs.isEmpty()) cs = metaCharset(data);
        if (cs != null && !cs.isEmpty()) {
            try { return new String(data, cs); } catch (Exception ignored) {}
        }
        String guess = com.mangjie.reader.reader.EncodingDetect.decode(data, data.length);
        if (guess != null) return guess;
        try { return new String(data, "UTF-8"); } catch (Exception e) { return ""; }
    }

    /** meta 里声明的编码：<meta charset=gbk> 或 <meta http-equiv=Content-Type content="text/html; charset=gb2312"> */
    private static final java.util.regex.Pattern META_CHARSET = java.util.regex.Pattern.compile(
            "<meta[^>]*?charset\\s*=\\s*['\"]?([A-Za-z0-9_\\-]+)", java.util.regex.Pattern.CASE_INSENSITIVE);
    /** 只看开头这一段，够了；整页扫太慢 */
    private static final int META_SCAN = 4096;

    static String metaCharset(byte[] data) {
        if (data == null || data.length == 0) return null;
        int n = Math.min(data.length, META_SCAN);
        String head;
        try { head = new String(data, 0, n, "ISO-8859-1"); } catch (Exception e) { return null; }
        java.util.regex.Matcher m = META_CHARSET.matcher(head);
        while (m.find()) {
            String name = m.group(1).trim();
            if (name.isEmpty()) continue;
            // utf8 这种写法也认
            if ("utf8".equalsIgnoreCase(name)) return "UTF-8";
            if ("gb2312".equalsIgnoreCase(name) || "gbk".equalsIgnoreCase(name)) return "GB18030";
            return name;
        }
        return null;
    }

    public String httpGetText(String url, int timeoutMs) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs * 2);
        c.setRequestProperty("User-Agent", "MangjieReader/" + host.appVersion());
        c.setRequestProperty("Accept", "application/json");
        c.connect();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
        java.io.InputStream is = c.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return decodeBytes(bos.toByteArray(), null);
    }

    /** 抓原始字节：字体文件、图片这类不能按字符解的东西用 */
    public byte[] httpGetBytes(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                if (loc != null) return httpGetBytes(loc.startsWith("http") ? loc : new java.net.URL(new java.net.URL(url), loc).toString());
            }
            if (code < 200 || code >= 300) return null;
            java.io.InputStream is = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return bos.toByteArray();
        } catch (Exception e) {
            host.log("听书", "抓字体失败 " + url + "：" + e.getClass().getSimpleName());
            return null;
        }
    }

    /** 只要响应头（HEAD 请求）：书源里 java.head(url).header('Last-Modified') 用 */
    public java.util.Map<String, String> httpHead(String url) {
        java.util.Map<String, String> out = new java.util.HashMap<String, String>();
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.connect();
            for (java.util.Map.Entry<String, java.util.List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                out.put(e.getKey(), e.getValue().get(0));
                out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
            }
            out.put(":status", String.valueOf(c.getResponseCode()));
        } catch (Exception e) {
            host.log("听书", "HEAD 失败 " + url + "：" + e.getClass().getSimpleName());
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
        return out;
    }

    public String httpGetText(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "text/html,application/json,*/*");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                if (loc != null) return httpGetText(loc.startsWith("http") ? loc : new java.net.URL(new java.net.URL(url), loc).toString());
            }
            if (code < 200 || code >= 300) return null;
            saveCookies(url, c);
            java.io.InputStream is = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return decodeBytes(bos.toByteArray(), null);
        } catch (Exception e) {
            return null;
        }
    }

    public void saveCookies(String url, java.net.HttpURLConnection c) {
        try {
            String h = host.hostOf(url == null ? "" : url);
            if (h.isEmpty()) return;
            java.util.Map<String, java.util.List<String>> hs = c.getHeaderFields();
            for (String key : hs.keySet()) {
                if (key == null || !"set-cookie".equalsIgnoreCase(key)) continue;
                java.util.Map<String, String> jar = jarMap.get(h);
                if (jar == null) { jar = new java.util.HashMap<>(); jarMap.put(h, jar); }
                for (String v : hs.get(key)) {
                    String pair = v.split(";", 2)[0];
                    int eq = pair.indexOf('=');
                    if (eq > 0) jar.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        } catch (Exception ignored) { }
    }

    public void putCookie(String url, String name, String value) {
        String h = host.hostOf(url == null ? "" : url);
        if (h.isEmpty()) return;
        Map<String, String> jar = jarMap.get(h);
        if (jar == null) { jar = new HashMap<>(); jarMap.put(h, jar); }
        jar.put(name, value);
    }

    public void removeCookie(String url, String name) {
        Map<String, String> jar = cookieFor(url);
        if (jar != null) jar.remove(name);
    }

    public java.util.Map<String, String> cookieFor(String url) {
        String h = host.hostOf(url == null ? "" : url);
        if (h.isEmpty()) return null;
        for (java.util.Map.Entry<String, java.util.Map<String, String>> en : jarMap.entrySet()) {
            String k = en.getKey();
            if (k.isEmpty()) continue;
            if (h.equals(k) || h.endsWith("." + k) || k.endsWith("." + h)) return en.getValue();
        }
        return null;
    }
}
