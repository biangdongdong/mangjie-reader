/*
 * 本文件的部分实现参照「开源阅读 Legado」（https://github.com/LegadoTeam/legado）。
 * Legado：Copyright (C) 2019-2023 gedoor，GNU General Public License v3.0。
 * 本工程对其的使用方式与修改说明见仓库根目录 NOTICE，完整条款见 LICENSE。
 */
package com.mangjie.reader.net;

import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;

import com.mangjie.reader.model.Item;
import com.mangjie.reader.model.Screen;

import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 网络书：书源加载、聚合搜索、换源、目录、正文、离线缓存、预取、追更、发现、整章连播。
 * 自带 nb* 状态；界面、偏好、网络请求都从宿主取，模块内部不再摸 Activity 字段。
 */
public final class NetBookController {

    /** 宿主能力：只留网络书真正需要的那几件 */
    public interface Host {
        void log(String tag, String msg);
        void toast(String msg);
        Screen top();
        String safeName(String s);
        String dataDir();
        String groupOf(java.util.regex.Matcher m, String rule);
        void pushScreen(Screen s);
        /** 边搜边出：往发起这次搜索的列表尾补一行，不动滚动位置。
         *  带上发起者那一页：人可能已经点进某本书、或退到别的页，结果照样记进那一页，回来还看得见 */
        void appendSearchRow(String label, String key, Screen target);
        /** 搜索页插一行：在 at 这一行插进一本书，只重排插入点之后的序号。
         *  placeholderAt >= 0 时先把「正在搜…」那条占位的 View 拿掉（items 里已经删过了） */
        default void insertSearchRowView(Screen target, int at, int placeholderAt) {}
        /** 拿掉列表里指定的那一行（分类页翻页前先把末尾「继续加载」收起，别让它夹在书中间） */
        default void dropTailRow(String key) {}
        /** 改写某一行的文字（同名同作者合并多个来源时用），不动滚动 */
        void updateSearchRow(int idx, String label);
        void openSubsScreen();
        void subOpenArticles(int i);
        void speak(String msg);

        /** 不打断正文的播报：进度类提示走这条，默认退化成普通播报 */
        default void speakQueued(String msg) { speak(msg); }
        /** 最近一次 JS 求值的失败原文：取正文失败时带出来，别一律说成"被墙" */
        default String lastJsError() { return ""; }
        void render(boolean reset);
        /** 把当前搜索上下文（关键词、页码）告诉宿主，URL 模板里的 {{key}} / {{page}} 才对得上 */
        default void setUrlCtx(String key, int page) {}
        /** 告诉宿主"现在是第几页"，JS 里求 {{(page-1)*10}} 这类表达式要用；不传的话永远是第 1 页 */
        default void setJsPage(int page) {}

        /** 当前书源自带的 jsLib（共享 JS），evalJs 前要先灌进作用域，Legado 就是这么做的 */
        default void setJsLib(String lib) {}

        /** 当前书源本体，JS 里的 source 对象（getKey/getVariable/putVariable 那些） */
        default void setSource(org.json.JSONObject src) {}
        String requestUrl(String url, String base);
        /** 带超时的请求：慢源卡时间用，timeoutMs<=0 走默认 */
        String requestUrlT(String url, String base, int timeoutMs);
        /** 最近一次请求的 HTTP 状态码，0 表示没发过 */
        int lastHttpStatus();
        /**
         * 带状态码的请求：状态/最终地址/耗时/错误都随这次调用回来。
         * 并发抓多个源时不能用 lastHttpStatus()，那个是全局值会被别的请求覆盖。
         */
        default Http.Resp requestResp(String url, String base, int timeoutMs) { return null; }
        String readTextFile(File f);
        Screen bookSourceScreen();
        void speakChapter();
        /** 网上章节正文也走分句阅读页 */
        void openNetChapter(String title, String body, String key, boolean autoRead);
        java.util.List<String> chunkForTts(String text, int size);
        boolean chainSpeak(String chunk);
    }

    private final Host host;
    private final RuleEngine rules;
    private final SharedPreferences prefs;
    private final Handler ui;
    private final List<Screen> stack;
    private final String booksrcDir;
    private String srcDirStamp = null;

    // ---------- 网络书状态 ----------
    /** 切到某个源时把它的 jsLib 交给 evalJs（规则里 JS 依赖源自带的库函数，不灌进来就 ReferenceError） */
    private void bindSrc(JSONObject src) {
        host.setSource(src);
        host.setJsLib(src == null ? "" : src.optString("jsLib", ""));
    }

    public List<JSONObject> nbSources = new ArrayList<>();
    public List<File> nbSourceFiles = new ArrayList<>();
    public List<String[]> nbResults = new ArrayList<>();
    public List<String[]> nbChapters = new ArrayList<>();
    /** 正在批量校验：校验时不受「已禁用」「注释里写了失效」这两条快捷跳过的限制，否则永远判不出它已经好了 */
    public volatile boolean nbChecking = false;
    /** 上一次取正文的失败原因（请求失败 / 规则空 / 被拦），用于分类提示 */
    public String nbLastFetchErr = "";
    /** 当前打开的是不是音频源的书（点章节走播放器，不走路朗读 */
    public boolean nbIsAudioBook = false;
    public String nbBookName = "";
    public String nbBookAuthor = "";
    public String nbBookUrl = "";
    public int nbSourceIdx = -1;
    public String nbReaderText = "";
    public String nbLastChapter = "";
    public String nbJumpChapter = null;
    public final LinkedHashMap<String, String> nbTextCache = new LinkedHashMap<String, String>(4, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) { return size() > 4; }
    };
    public String nbNextPrefetchUrl = "";

    /** 当前这一轮搜索的线程池。每轮一个，新搜索开始时把上一轮的 shutdownNow 掉。
     *  以前是 static 共用一个 4 线程池：连点几十次搜索，无界队列里堆了几千个旧任务，
     *  当前这轮的结果排在它们后面，人看到的就是「只有最开头那几本，后面不出来」。 */
    private volatile java.util.concurrent.ExecutorService nbSearchPool;

    /** 命中区条数：nbResults 前这么多条是命中关键词的，往下才是不命中的。
     *  命中区只往后追加、不重排——已经上屏的那几本位置钉死，读屏不会因为后面来了本书就重复念 */
    private int nbHitCount = 0;

    /** 「正在搜…」那行占位在列表里的下标；搜索过程中就靠改这一行的字回进度 */
    private int nbSecIdx = -1;
    /** 上次改进度文字的时刻，按 2.5 秒节流：太密会一直被打断，太疏又看不出在动 */
    private long nbSecAt = 0L;
    /** 这一轮还在搜（收尾或手动停止就置假）。搜索菜单据此决定要不要露「停止搜索」 */
    private volatile boolean nbSearchBusy = false;

    /** 手动停搜：代次作废、线程池砍掉，已经找到的留着，占位行改成「已停止」 */
    public void stopSearch() {
        int had = nbResults.size();
        nbSearchSeq++;                       // 迟到结果全部作废
        java.util.concurrent.ExecutorService p = nbSearchPool;
        nbSearchPool = null;
        if (p != null) p.shutdownNow();
        nbSearchBusy = false;
        Screen sc = host.top();
        if (sc != null && "nbSearch".equals(sc.key) && nbSecIdx >= 0 && nbSecIdx < sc.items.size()) {
            String done = "已停止搜索，共找到 " + had + " 本";
            sc.items.set(nbSecIdx, new Item(done, "sec"));
            host.render(false);
        }
        host.speak("已停止搜索，找到 " + had + " 本，已经出来的都留着");
        host.log("搜索", "手动停止，已找到 " + had + " 本");
    }

    public boolean searching() { return nbSearchBusy; }

    /** 只作废、不播报：别的流程要接手结果容器时用（换源、重新搜），别跟它抢着说话 */
    private void stopSearchQuiet() {
        nbSearchSeq++;
        java.util.concurrent.ExecutorService p = nbSearchPool;
        nbSearchPool = null;
        if (p != null) p.shutdownNow();
        nbSearchBusy = false;
        nbHitCount = 0;
        nbSecIdx = -1;
    }

    /** 每次搜索自增；回调回来发现代次对不上就整批丢掉 */
    public int nbSearchSeq = 0;

    /** 候选结果集的代次：nbResults 一旦被换掉，屏上老下标就不作数了 */
    public int nbResultsSeq = 0;
    public List<String> nbChainChunks = null;
    public int nbChainAt = 0;
    public int nbChainChapterIdx = -1;
    public String nbChainName = "";
    public List<Object[]> nbGroups = new ArrayList<>();
    public int nbExploreSi = -1;
    public List<String[]> nbCats = new ArrayList<>();
    public String jsKey = "";
    public int jsPage = 1;

    /** 走请求前先把上下文交出去：所有 URL 模板都靠它求值 */
    /**
     * 对齐 Legado NetworkUtils.getAbsoluteURL：交给 java.net.URL 做标准解析。
     * 以前各处手写 startsWith("/") 判断，base 带路径时会拼出 https://x.com/search/book/1 这种错地址。
     */
    public static String absUrl(String base, String rel) {
        String r = rel == null ? "" : rel.trim();
        if (r.isEmpty()) return "";
        if (r.startsWith("javascript")) return "";
        if (r.startsWith("data:")) return r;
        if (base == null || base.isEmpty()) return r;
        String b = base.split(",")[0].trim();
        if (r.startsWith("//")) {
            try { return new java.net.URL(b).getProtocol() + ":" + r; } catch (Throwable t) { return r; }
        }
        try {
            return new java.net.URL(new java.net.URL(b), r).toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private String req(String url, String base) {
        host.setUrlCtx(jsKey, jsPage);
        return host.requestUrl(url, base);
    }

    private String reqT(String url, String base, int t) {
        host.setUrlCtx(jsKey, jsPage);
        return host.requestUrlT(url, base, t);
    }

    /** 要状态码的请求：状态/地址/耗时用于把失败说清楚，不能拿全局 lastHttpStatus（并发会串） */
    private Http.Resp reqR(String url, String base, int t) {
        host.setUrlCtx(jsKey, jsPage);
        Http.Resp r = host.requestResp(url, base, t);
        if (r == null) {
            r = new Http.Resp();
            r.body = host.requestUrl(url, base);
            r.code = host.lastHttpStatus();
            if (r.body == null && r.code == 0) r.err = "请求没发出去";
        }
        return r;
    }

    public NetBookController(Host host, RuleEngine rules, SharedPreferences prefs,
                            Handler ui, List<Screen> stack, String booksrcDir) {
        this.host = host; this.rules = rules; this.prefs = prefs;
        this.ui = ui; this.stack = stack; this.booksrcDir = booksrcDir;
    }

    /**
     * 书源目录的指纹：文件名+大小+修改时间。外部换过书源文件后，
     * 下次进搜索或发现自动重载，不用重启 App。
     */
    private String stampDir(String dirPath) {
        try {
            File dir = new File(dirPath);
            File[] fs = dir.listFiles();
            if (fs == null) return "";
            Arrays.sort(fs, new Comparator<File>() {
                @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
            });
            StringBuilder sb = new StringBuilder();
            for (File f : fs) {
                if (!f.isFile()) continue;
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (!n.endsWith(".json") && !n.endsWith(".txt")) continue;
                sb.append(f.getName()).append('|').append(f.length()).append('|').append(f.lastModified()).append(';');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 书源类型，对齐 Legado 的 BookSourceType：0 文本 1 音频 2 图片 3 下载站 */
    public int srcType(JSONObject o) {
        if (o == null) return 0;
        try { return o.optInt("bookSourceType", 0); } catch (Throwable t) { return 0; }
    }

    public static String srcTypeName(int t) {
        if (t == 1) return "音频";
        if (t == 2) return "图片";
        if (t == 3) return "下载";
        return "文本";
    }

    /** 搜索放不放这个源：文本源和音频源都能搜（音频书搜出来点开就播），图片源和下载站跳过 */
    public boolean srcSearchOk(int si) {
        if (si < 0 || si >= nbSources.size()) return false;
        JSONObject o = nbSources.get(si);
        int t = srcType(o);
        return srcEnabled(o) && (t == 0 || t == 1);
    }

    /** 这个源能不能拿来搜文本：只认启用的文本源，音频/图片/下载站全部跳过 */
    public boolean srcTextOk(int si) {
        if (si < 0 || si >= nbSources.size()) return false;
        JSONObject o = nbSources.get(si);
        return srcEnabled(o) && srcType(o) == 0;
    }

    public void loadBookSources() {
        String stamp = stampDir(booksrcDir);
        if (!nbSources.isEmpty() && stamp.equals(srcDirStamp)) return;   // 目录没动过，用内存里的
        boolean changed = srcDirStamp != null;
        srcDirStamp = stamp;
        nbSources = new ArrayList<>();
        nbSourceFiles = new ArrayList<>();
        try {
            File dir = new File(booksrcDir);
            if (!dir.exists()) dir.mkdirs();
            File[] fs = dir.listFiles();
            if (fs != null) {
                Arrays.sort(fs, new Comparator<File>() {
                    @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
                });
                for (File f : fs) {
                    if (!f.isFile()) continue;
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (!n.endsWith(".json") && !n.endsWith(".txt")) continue;
                    String text = host.readTextFile(f);
                    if (text == null) continue;
                    try {
                        String t = text.trim();
                        if (t.startsWith("[")) {
                            org.json.JSONArray arr = new org.json.JSONArray(t);
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.optJSONObject(i);
                                if (o != null) { nbSources.add(o); nbSourceFiles.add(f); }
                            }
                        } else {
                            JSONObject o = new JSONObject(t);
                            if (o.has("bookSourceName")) { nbSources.add(o); nbSourceFiles.add(f); }
                            else if (o.has("sources")) {
                                org.json.JSONArray arr = o.optJSONArray("sources");
                                for (int i = 0; arr != null && i < arr.length(); i++) {
                                    JSONObject x = arr.optJSONObject(i);
                                    if (x != null) { nbSources.add(x); nbSourceFiles.add(f); }
                                }
                            }
                        }
                    } catch (Exception e) {
                        host.log("听书", "书源解析失败 " + f.getName() + "：" + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            host.log("听书", "读书源异常：" + e);
        }
        dedupBySysPriority();
        host.log("听书", (changed ? "书源目录已变化，重新加载 " : "已载入书源 ") + nbSources.size() + " 个");
    }

    /**
     * 同一个 bookSourceUrl 只留一条，以服务器下发的「系统」书源为准。
     * 系统文件（文件名以「系统」开头）里的条目全部保留，其余文件里同网址的丢掉，
     * 这样服务器改了规则，用户这边下次启动就跟上，不会两个同名源同时在列表里。
     */
    private void dedupBySysPriority() {
        if (nbSources.size() < 2 || nbSourceFiles == null || nbSourceFiles.size() != nbSources.size()) return;
        java.util.Set<String> sysUrls = new java.util.HashSet<>();
        for (int i = 0; i < nbSources.size(); i++) {
            File f = nbSourceFiles.get(i);
            if (f == null || (!f.getName().startsWith(SourceSync.PREFIX)
                    && !SourceSync.BUNDLE.equals(f.getName()))) continue;
            String u = nbSources.get(i).optString("bookSourceUrl", "").trim();
            if (!u.isEmpty()) sysUrls.add(u);
        }
        if (sysUrls.isEmpty()) return;
        java.util.List<org.json.JSONObject> keep = new java.util.ArrayList<>();
        java.util.List<File> keepFiles = new java.util.ArrayList<>();
        for (int i = 0; i < nbSources.size(); i++) {
            org.json.JSONObject o = nbSources.get(i);
            File f = nbSourceFiles.get(i);
            String u = o.optString("bookSourceUrl", "").trim();
            boolean isSys = f != null && (f.getName().startsWith(SourceSync.PREFIX)
                    || SourceSync.BUNDLE.equals(f.getName()));
            if (!isSys && !u.isEmpty() && sysUrls.contains(u)) continue;
            keep.add(o);
            keepFiles.add(f);
        }
        if (keep.size() != nbSources.size()) {
            host.log("听书", "系统书源优先：同网址去掉 " + (nbSources.size() - keep.size()) + " 个用户副本");
            nbSources = keep;
            nbSourceFiles = keepFiles;
        }
    }


    // ---------------- 书源分组（bookSourceGroup，逗号分隔多组，对齐 Legado）

    /** 所有组名，去重；有源没填组就多一个「未分组」 */
    public List<String> sourceGroupNames() {
        List<String> out = new ArrayList<>();
        boolean hasEmpty = false;
        for (int i = 0; i < nbSources.size(); i++) {
            String g = nbSources.get(i).optString("bookSourceGroup", "").trim();
            if (g.isEmpty()) { hasEmpty = true; continue; }
            for (String one : g.split("[,，]")) {
                String t = one.trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        if (hasEmpty) out.add("未分组");
        return out;
    }

    /** 某个组里的书源下标 */
    public List<Integer> sourceGroupIdx(String group) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < nbSources.size(); i++) {
            String g = nbSources.get(i).optString("bookSourceGroup", "").trim();
            if ("未分组".equals(group)) { if (g.isEmpty()) out.add(i); continue; }
            for (String one : g.split("[,，]")) if (one.trim().equals(group)) { out.add(i); break; }
        }
        return out;
    }

    /** 改分组并写回书源文件（单对象、数组两种格式都认） */
    /** 读一个规则字段；path 形如 "searchUrl" 或 "ruleSearch.bookList" */
    public String getSourceField(int i, String path) {
        if (i < 0 || i >= nbSources.size()) return "";
        JSONObject o = nbSources.get(i);
        int dot = path.indexOf('.');
        if (dot < 0) return o.optString(path, "");
        JSONObject sub = rules.sub(o, path.substring(0, dot));
        if (sub == null) return "";
        return sub.optString(path.substring(dot + 1), "");
    }

    /** 写一个规则字段并落回它所在的 JSON 文件 */
    public boolean setSourceField(int i, String path, String value) {
        if (i < 0 || i >= nbSources.size() || i >= nbSourceFiles.size()) return false;
        try {
            JSONObject mem = nbSources.get(i);
            File f = nbSourceFiles.get(i);
            String text = host.readTextFile(f);
            if (text == null) return false;
            String url = mem.optString("bookSourceUrl", "");
            String nm = mem.optString("bookSourceName", "");
            int dot = path.indexOf('.');
            String topKey = dot < 0 ? path : path.substring(0, dot);
            String subKey = dot < 0 ? "" : path.substring(dot + 1);
            String t = text.trim();
            org.json.JSONArray arr;
            if (t.startsWith("[")) arr = new org.json.JSONArray(t);
            else {
                arr = new org.json.JSONArray();
                arr.put(new JSONObject(t));
            }
            for (int k = 0; k < arr.length(); k++) {
                JSONObject o = arr.optJSONObject(k);
                if (o == null) continue;
                if (!o.optString("bookSourceUrl", "").equals(url)
                        || !o.optString("bookSourceName", "").equals(nm)) continue;
                if (dot < 0) {
                    if ("enabled".equals(path) || "enabledExplore".equals(path)) o.put(path, "true".equals(value));
                    else if ("weight".equals(path) || "respondTime".equals(path)
                            || "lastUpdateTime".equals(path)) {
                        try { o.put(path, Long.parseLong(value.trim())); } catch (Exception e) { o.put(path, 0L); }
                    }
                    else o.put(path, value == null ? "" : value);
                } else {
                    JSONObject sub = o.optJSONObject(topKey);
                    if (sub == null) { sub = new JSONObject(); o.put(topKey, sub); }
                    sub.put(subKey, value == null ? "" : value);
                }
                break;
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(arr.toString(2).getBytes("UTF-8"));
            fo.close();
            loadBookSources();
            return true;
        } catch (Exception e) {
            host.log("书源", "写规则失败：" + e.getMessage());
            return false;
        }
    }

    /**
     * 一次写回多个顶层字段（批量校验用）。与 setSourceField 的区别是不重读全部书源，
     * 否则 1400 个源 × 每个写三次就是 4000 次全量重载，根本跑不完。
     */
    public boolean setSourceFields(int i, String[] keys, Object[] values) {
        if (i < 0 || i >= nbSources.size() || i >= nbSourceFiles.size()) return false;
        if (keys == null || values == null || keys.length != values.length) return false;
        try {
            JSONObject mem = nbSources.get(i);
            File f = nbSourceFiles.get(i);
            String text = host.readTextFile(f);
            if (text == null) return false;
            String url = mem.optString("bookSourceUrl", "");
            String nm = mem.optString("bookSourceName", "");
            String t = text.trim();
            org.json.JSONArray arr;
            if (t.startsWith("[")) arr = new org.json.JSONArray(t);
            else {
                arr = new org.json.JSONArray();
                arr.put(new JSONObject(t));
            }
            boolean hit = false;
            for (int k = 0; k < arr.length(); k++) {
                JSONObject o = arr.optJSONObject(k);
                if (o == null) continue;
                if (!o.optString("bookSourceUrl", "").equals(url)
                        || !o.optString("bookSourceName", "").equals(nm)) continue;
                for (int j = 0; j < keys.length; j++) {
                    Object v = values[j];
                    if (v == null) o.put(keys[j], "");
                    else if (v instanceof Boolean || v instanceof Number) o.put(keys[j], v);
                    else o.put(keys[j], String.valueOf(v));
                    // 内存里也同步一下，同一个线程后面读到的就是新值
                    if (v instanceof Boolean || v instanceof Number) mem.put(keys[j], v);
                    else mem.put(keys[j], v == null ? "" : String.valueOf(v));
                }
                hit = true;
                break;
            }
            if (!hit) return false;
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(arr.toString(2).getBytes("UTF-8"));
            fo.close();
            return true;
        } catch (Exception e) {
            host.log("书源", "批量写字段失败：" + e.getMessage());
            return false;
        }
    }

    public boolean setSourceGroup(int i, String group) {
        if (i < 0 || i >= nbSources.size() || i >= nbSourceFiles.size()) return false;
        try {
            JSONObject mem = nbSources.get(i);
            File f = nbSourceFiles.get(i);
            String text = host.readTextFile(f);
            if (text == null) return false;
            String url = mem.optString("bookSourceUrl", "");
            String nm = mem.optString("bookSourceName", "");
            String t = text.trim();
            if (t.startsWith("[")) {
                org.json.JSONArray arr = new org.json.JSONArray(t);
                for (int k = 0; k < arr.length(); k++) {
                    JSONObject o = arr.optJSONObject(k);
                    if (o == null) continue;
                    if (o.optString("bookSourceUrl", "").equals(url)
                            && o.optString("bookSourceName", "").equals(nm)) {
                        o.put("bookSourceGroup", group);
                    }
                }
                text = arr.toString(2);
            } else {
                JSONObject o = new JSONObject(t);
                o.put("bookSourceGroup", group);
                text = o.toString(2);
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(text.getBytes("UTF-8"));
            fo.close();
            mem.put("bookSourceGroup", group);
            srcDirStamp = null;      // 下次重载按新内容读
            return true;
        } catch (Throwable e) {
            host.log("听书", "改分组失败：" + e);
            return false;
        }
    }

    public boolean srcEnabled(JSONObject src) {
        String name = src.optString("bookSourceName", "未命名");
        return prefs.getBoolean("bsrcOn_" + name, true);
    }

    public Screen bsrcFindScreen(String q) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < nbSources.size(); i++) {
            JSONObject o = nbSources.get(i);
            String name = o.optString("bookSourceName", "未命名");
            String base = o.optString("bookSourceUrl", "");
            if (!name.toLowerCase().contains(q.toLowerCase()) && !base.toLowerCase().contains(q.toLowerCase())) continue;
            items.add(new Item(name + "（" + (srcEnabled(o) ? "开" : "关") + "）", "bsrc:detail:" + i));
        }
        if (items.isEmpty()) items.add(new Item("（没匹配到书源）"));
        return new Screen("书源搜索　" + q, items);
    }

    public void bsrcSetAll(boolean on) {
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < nbSources.size(); i++) {
            ed.putBoolean("bsrcOn_" + nbSources.get(i).optString("bookSourceName", "未命名"), on);
        }
        ed.apply();
        stack.remove(stack.size() - 1);
        stack.add(host.bookSourceScreen());
        host.render(true);
        host.speak(on ? "全部书源已启用" : "全部书源已关闭");
    }

    /** 上次搜的关键字（切精准搜索时用来就地重搜） */
    public String nbLastKey = "";

    /** 精准搜索：开着就只留"书名或作者含关键字"的结果，丢掉源返回的无关条目（对齐 Legado 的 precisionSearch） */
    public boolean precisionSearch() {
        // 默认开：源返回的无关条目太多，新装的用户第一次搜就该是准的；
        // 存过值的（自己关过）照旧尊重，不强行覆盖
        if (!prefs.contains("precisionSearch")) {
            prefs.edit().putBoolean("precisionSearch", true).apply();
            return true;
        }
        return prefs.getBoolean("precisionSearch", true);
    }

    public void setPrecisionSearch(boolean on) {
        prefs.edit().putBoolean("precisionSearch", on).apply();
    }

    // ================================================================ 最近搜索（对齐 Legado 的 search_keywords）
    //  一行一条「词\u0001次数\u0001最后用时」。口径照 Legado：同一个词再搜不新增一条，
    //  只把 usage 加一并刷新 lastUseTime；列表按 lastUseTime 倒序。存 30 条、搜索页铺 5 条。

    private static final String HIST_KEY = "nbSearchHist";
    private static final int HIST_KEEP = 30;
    private static final int HIST_SHOW = 5;

    /** 读最近搜索，最近用的排在前面：每项 {词, 次数, 时间} */
    public List<String[]> recentSearches() {
        List<String[]> out = new ArrayList<String[]>();
        String st = prefs.getString(HIST_KEY, "");
        if (st == null) return out;
        for (String line : st.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] p = line.split("\u0001");
            if (p.length < 1 || p[0].trim().isEmpty()) continue;
            out.add(new String[]{p[0], p.length > 1 ? p[1] : "1", p.length > 2 ? p[2] : "0"});
        }
        return out;
    }

    public int recentSearchCount() { return recentSearches().size(); }

    /** 记一笔搜索关键词：有就次数加一并刷到最后，没有就插到最前 */
    public void saveSearchKey(String key) {
        String w = key == null ? "" : key.trim().replace('\u0001', ' ').replace('\n', ' ');
        if (w.isEmpty()) return;
        List<String[]> list = recentSearches();
        int at = -1;
        for (int i = 0; i < list.size(); i++) {
            if (w.equals(list.get(i)[0])) { at = i; break; }
        }
        int use = 1;
        if (at >= 0) {
            String[] old = list.remove(at);
            try { use = Integer.parseInt(old[1]) + 1; } catch (Exception e) { use = 2; }
        }
        list.add(0, new String[]{w, String.valueOf(use), String.valueOf(System.currentTimeMillis())});
        while (list.size() > HIST_KEEP) list.remove(list.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            String[] r = list.get(i);
            sb.append(r[0]).append('\u0001').append(r[1]).append('\u0001').append(r[2]).append('\n');
        }
        prefs.edit().putString(HIST_KEY, sb.toString()).apply();
    }

    /** 清空最近搜索：顺手把当前搜索页上那几行也摘掉，不然点了没反应，看着像没生效 */
    public void clearSearchHistory() {
        int had = recentSearchCount();
        prefs.edit().remove(HIST_KEY).apply();
        Screen sc = host.top();
        if (sc == null || !"nbSearch".equals(sc.key)) {
            host.speak("最近搜索已清空");
            return;
        }
        for (int i = sc.items.size() - 1; i >= 0; i--) {
            String kk = sc.items.get(i).key;
            if (kk != null && kk.startsWith("nb:hist:")) sc.items.remove(i);
        }
        if (sc.cur >= sc.items.size()) sc.cur = Math.max(0, sc.items.size() - 1);
        host.log("搜索", "清空最近搜索，原有 " + had + " 条");
        host.render(true);
        host.speak("最近搜索已清空，共清了 " + had + " 条");
    }

    /** 对齐 Legado SearchModel.startSearch 里的 filter：
     *  !precision || 书名含 || 作者含 || 分类含 —— 三者命中任一就留下。
     *  以前只查书名和作者，书名被书源塞进分类的那一批全被滤掉了，命中率就是这么掉的。 */
    private boolean precisionOk(String name, String author, String kind, String key) {
        if (!precisionSearch()) return true;
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) return true;
        String n = name == null ? "" : name.trim();
        String a = author == null ? "" : author.trim();
        if (n.contains(k) || a.contains(k)) return true;
        String kd = kind == null ? "" : kind.trim();
        return kd.length() > 0 && kd.contains(k);
    }

    /** 搜索范围：空=全部，g:组名=只搜这些分组，s:源名=只搜这一个源（对齐 Legado 的 SearchScope） */
    public String searchScope() {
        String v = prefs.getString("searchScope", "");
        return v == null ? "" : v.trim();
    }

    public void setSearchScope(String v) {
        prefs.edit().putString("searchScope", v == null ? "" : v.trim()).apply();
    }

    public String searchScopeLabel() {
        String v = searchScope();
        if (v.isEmpty()) return "全部书源";
        if (v.startsWith("g:")) return "分组：" + v.substring(2);
        if (v.startsWith("s:")) return "书源：" + v.substring(2);
        return "全部书源";
    }

    /** 该书源在不在当前搜索范围内 */
    public boolean inSearchScope(int si) {
        String v = searchScope();
        if (v.isEmpty()) return true;
        if (si < 0 || si >= nbSources.size()) return false;
        JSONObject o = nbSources.get(si);
        if (v.startsWith("g:")) {
            String g = v.substring(2);
            String srcG = o.optString("bookSourceGroup", "");
            for (String one : srcG.split("[,，]")) {
                String t = one.trim();
                if (t.isEmpty()) continue;
                if (g.contains(t)) return true;
            }
            return false;
        }
        if (v.startsWith("s:")) {
            String nm = o.optString("bookSourceName", "").trim();
            return nm.equals(v.substring(2).trim());
        }
        return true;
    }

    public List<String[]> nbSearchInSource(int si, String key) {
        List<String[]> out = new ArrayList<>();
        if (si < 0 || si >= nbSources.size()) return out;
        JSONObject src = nbSources.get(si);
        bindSrc(src);
        String sName = src.optString("bookSourceName", "源" + (si + 1));
        probe: {
            if (!srcEnabled(src) && !nbChecking) break probe;
            String comment = src.optString("bookSourceComment", "");
            if (!nbChecking && (comment.contains("失效") || comment.contains("Error"))) break probe;
            try {
                JSONObject rs = rules.sub(src, "ruleSearch");
                String sUrl = rules.ruleStr(src, "searchUrl");
                if (sUrl.isEmpty() || rs == null) break probe;
                // 原样交给请求层：{{key}}/{page}、@js、<a,b,c>、,{...} 由 UrlResolver 统一求值，
                // 这里再自己编码一遍会把 gbk 源打成 UTF-8，等于白算
                String base = rules.ruleStr(src, "bookSourceUrl");
                jsKey = key;
                jsPage = 1;
                host.setJsPage(1);
                // 搜索求快不求全：单源 8 秒，超时就当这个源没结果，别让它占着一个线程位几十秒
                Http.Resp resp = reqR(sUrl, base, 8000);
                if (resp == null || resp.body == null || resp.body.trim().isEmpty()) break probe;
                String html = resp.body;
                String pageUrl = (resp.url == null || resp.url.trim().isEmpty()) ? absUrl(base, sUrl) : resp.url.trim();
                String listRule = rules.ruleStr(rs, "bookList");
                // 统一入口：搜索列表也可能是 @json: / $ / <js> / || 候选
                List<Object> sits = listRule.isEmpty() ? new ArrayList<Object>()
                        : rules.listItems(listRule, pageUrl, html);
                if (!sits.isEmpty()) {
                    // 单源上限对齐"别把一次搜索撑爆"，但不再因为 50 条就提前掐断整源
                    for (Object it : sits) {
                        // 很多源把「书名　作者：xxx」整行塞进 name，不清洗会导致「完全同名」永远判不上，
                        // 正版被当成「书名含关键词」沉到命中区末尾——搜「斗破苍穹」正版排到第 21 位就是这么来的
                        String nm = cleanName(rules.fieldOf(rules.ruleStr(rs, "name"), it, pageUrl));
                        if (nm.isEmpty()) continue;
                        String au = cleanAuthor(rules.fieldOf(rules.ruleStr(rs, "author"), it, pageUrl));
                        // 对齐 Legado：分类也算命中面（有些源书名就写在分类里）
                        String kd = rules.fieldOf(rules.ruleStr(rs, "kind"), it, pageUrl);
                        String uu = rules.fieldOf(rules.ruleStr(rs, "bookUrl"), it, pageUrl);
                        String su = absUrl(pageUrl, uu);
                        // 对齐 Legado：取不到详情页地址时用列表页本身，条目照收（这类源就是"列表页即详情页"）
                        if (su.isEmpty()) su = pageUrl;
                        if (!precisionOk(nm, au, kd, key)) continue;
                        out.add(new String[]{nm, au, su, String.valueOf(si), sName,
                                kd == null ? "" : kd.trim()});
                        if (out.size() >= 50) break;
                    }
                    break probe;
                }
                // 列表取不到 → 按详情页解析一条（对齐 Legado BookList.kt 的"列表为空,按详情页解析"）
                String[] one = detailFromHtml(src, html, pageUrl, pageUrl);
                if (one != null) {
                    if (precisionOk(one[0], one[1], "", key)) {
                        out.add(new String[]{one[0], one[1], one[2], String.valueOf(si), sName, ""});
                    }
                    break probe;
                }
                if (listRule.isEmpty()) break probe;
                List<String> books = rules.ruleAll(listRule, pageUrl, html);
                String listHtml = html;
                if (books.size() == 1) listHtml = books.get(0);
                List<String> names = rules.ruleAll(rules.ruleStr(rs, "name"), pageUrl, listHtml);
                List<String> authors = rules.ruleAll(rules.ruleStr(rs, "author"), pageUrl, listHtml);
                List<String> urls = rules.ruleAll(rules.ruleStr(rs, "bookUrl"), pageUrl, listHtml);
                List<String> kinds = rules.ruleAll(rules.ruleStr(rs, "kind"), pageUrl, listHtml);
                int cnt = Math.min(names.size(), Math.max(1, Math.min(urls.size(), 50)));
                for (int i = 0; i < cnt; i++) {
                    String u = i < urls.size() ? urls.get(i) : "";
                    String a = i < authors.size() ? authors.get(i) : "";
                    String kd = i < kinds.size() ? kinds.get(i) : "";
                    String nm2 = cleanName(names.get(i));
                    if (nm2.isEmpty()) continue;
                    String a2 = cleanAuthor(a);
                    if (!precisionOk(nm2, a2, kd, key)) continue;
                    u = absUrl(pageUrl, u);
                    if (u.isEmpty()) u = pageUrl;
                    out.add(new String[]{nm2, a2, u, String.valueOf(si), sName,
                            kd == null ? "" : kd.trim()});
                }
            } catch (Exception e) {
                host.log("听书", "搜索异常 源" + si + "：" + e.getMessage());
            }
        }
        return out;
    }

    public List<String[]> nbSearchAll(String key) {
        loadBookSources();
        int n = nbSources.size();
        // 并发数对齐 Legado：设置值默认 16，但线程池上限 9（AppConst.MAX_THREAD）
        int want = prefs.getInt("searchThreads", 16);
        if (want > 9) want = 9;
        if (want < 1) want = 1;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(want, Math.max(1, n)));
        final List<List<String[]>> parts = java.util.Collections.synchronizedList(new ArrayList<List<String[]>>());
        for (int si = 0; si < n; si++) {
            if (!srcSearchOk(si)) continue;
            final int fsi = si;
            pool.submit(new Runnable() { @Override public void run() {
                List<String[]> one = nbSearchInSource(fsi, key);
                if (!one.isEmpty()) parts.add(one);
            } });
        }
        pool.shutdown();
        try { pool.awaitTermination(25, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) {}
        List<String[]> all = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (List<String[]> one : parts) {
            for (String[] b : one) {
                String k = b[0] + "\u0001" + b[2];
                if (!seen.add(k)) continue;
                all.add(b);
            }
        }
        final String key0 = key == null ? "" : key.trim();
        java.util.Collections.sort(all, new java.util.Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) {
                boolean ea = a[0].trim().equals(key0), eb = b[0].trim().equals(key0);
                if (ea != eb) return ea ? -1 : 1;
                int c = a[0].compareTo(b[0]);
                if (c != 0) return c;
                return a[4].compareTo(b[4]);
            }
        });
        int tried = 0;
        for (int si = 0; si < n; si++) if (srcEnabled(nbSources.get(si))) tried++;
        host.log("听书", "聚合搜索 " + key0 + "　源 " + n + " 个（启用 " + tried + "）　结果 " + all.size());
        return all;
    }

    public String nbResultLabel(String[] b) {
        StringBuilder sb = new StringBuilder(b[0]);
        if (b.length > 1 && !b[1].isEmpty()) sb.append("　").append(b[1]);
        if (b.length > 4 && !b[4].isEmpty()) {
            String[] ss = b[4].split("／");
            if (ss.length > 1) sb.append("　共").append(ss.length).append("源：").append(b[4].replace("／", "、"));
            else sb.append("　").append(b[4]);
        }
        // 地址本身就是个搜索页：多半是「把关键词当书名」的那类源，点开没有正文。
        // 排在最后也照样喊一声，免得人摸到这一行还以为能开。
        if (looksLikeSearchUrl(b.length > 2 ? b[2] : "")) sb.append("　（疑似不是书）");
        return sb.toString();
    }

    /** 合并用的键：书名 + 作者（都去掉首尾空白；也过一遍清洗，免得同名同作者不同写法合并不上、列表出重复条目） */
    private static String mergeKeyOf(String[] b) {
        String n = cleanName(b[0] == null ? "" : b[0]);
        String a = (b.length > 1 && b[1] != null) ? cleanAuthor(b[1]) : "";
        return n + "\u0001" + a;
    }

    /**
     * 这个地址是不是「又一个搜索页」，而不是这本书的详情页。
     *
     * 有一类源的搜索规则是把关键词原样当书名、地址指向同站的下一个搜索页
     * （日志实证：百度图片那条返回 {"title":"关键词","url":"https://image.baidu.com/search/flip?tn=…"}）。
     * 它们搜什么都能"出书"，点进去必然取空，却因为书名与关键词完全相同被排到第一位。
     * 按地址特征判，不按源名判——换一批源也照样管用。
     */
    private static boolean looksLikeSearchUrl(String u) {
        if (u == null) return false;
        String s = u.toLowerCase(java.util.Locale.ROOT);
        if (s.length() < 8) return false;
        return s.contains("/search") || s.contains("search?") || s.contains("msearch")
                || s.contains("/list") || s.contains("searchlist")
                || s.contains("query=") || s.contains("keyword=") || s.contains("keywords=")
                || s.contains("wd=") || s.contains("kw=") || s.contains("flip?");
    }

    /** 命中档位：0 完全同名/同作者，1 分类命中，2 书名或作者含，3 不命中，4 疑似不是书 —— 对齐 Legado mergeItems 的四档 */
    private static int tierOf(String[] b, String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) return 3;
        // 兜底再清一遍：取数环节已经洗过，但别的入口（缓存、历史、聚合）未必干净
        String n = (b.length > 0 && b[0] != null) ? cleanName(b[0]) : "";
        String a = (b.length > 1 && b[1] != null) ? cleanAuthor(b[1]) : "";
        String kd = (b.length > 5 && b[5] != null) ? b[5].trim() : "";
        // 书名与关键词一模一样、作者又是空的、地址还是个搜索页：这类不是书，压到最后
        if (n.equals(k) && a.isEmpty()
                && looksLikeSearchUrl(b.length > 2 && b[2] != null ? b[2] : "")) return 4;
        if (n.equals(k) || a.equals(k)) return 0;
        if (kd.length() > 0 && kd.contains(k)) return 1;
        if (n.contains(k) || a.contains(k)) return 2;
        return 3;
    }

    /**
     * 搜索结果排序：对齐 Legado SearchModel.mergeItems 的四档
     * 一档 书名或作者与关键词完全相同；二档 分类含关键词；三档 书名或作者含关键词；四档 其余（精准搜索关时才留）
     * 档内按来源数降序——多个源都有这本书，说明它更常见、更可能是要找的那本正版
     * （搜「斗破苍穹」时「斗破苍穹后传」只能落到三档，永远排不到一档前面）
     * 同档同来源数再按书名、作者排，保证同一个关键词每次排出来顺序都一样
     */
    private static void sortSearchResults(final List<String[]> all, final String key) {
        final String k = key == null ? "" : key.trim();
        java.util.Collections.sort(all, new java.util.Comparator<String[]>() {
            private int origins(String[] b) {
                int n = 1;
                if (b.length > 4 && b[4] != null) {
                    for (int i = 0; i < b[4].length(); i++) if (b[4].charAt(i) == '／') n++;
                }
                return n;
            }
            @Override public int compare(String[] x, String[] y) {
                int tx = tierOf(x, k), ty = tierOf(y, k);
                if (tx != ty) return tx - ty;
                int ox = origins(x), oy = origins(y);
                if (ox != oy) return oy - ox;
                String nx = (x.length > 0 && x[0] != null) ? x[0] : "";
                String ny = (y.length > 0 && y[0] != null) ? y[0] : "";
                int c = nx.compareTo(ny);
                if (c != 0) return c;
                String ax = (x.length > 1 && x[1] != null) ? x[1] : "";
                String ay = (y.length > 1 && y[1] != null) ? y[1] : "";
                return ax.compareTo(ay);
            }
        });
    }

    /** 按当前排好的 nbResults 重建搜索页的条目列表；withTail 表示这一轮已经搜完，末尾要挂「只看其中一个来源」 */
    private void rebuildSearchItems(Screen sc, List<Item> items, boolean withTail) {
        String curLabel = (sc.cur >= 0 && sc.cur < items.size()) ? items.get(sc.cur).label : null;
        List<Item> rebuilt = new ArrayList<Item>();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            String kk = it.key;
            if (kk != null && kk.startsWith("nb:book:")) continue;   // 旧结果整批换掉
            if (kk != null && (kk.equals("nb:fsrc") || kk.equals("sec"))) continue;
            rebuilt.add(it);
        }
        for (int i = 0; i < nbResults.size(); i++) {
            rebuilt.add(new Item(nbResultLabel(nbResults.get(i)),
                    "nb:book:" + i + ":" + nbResultsSeq));
        }
        if (withTail) rebuilt.add(new Item("只看其中一个来源", "nb:fsrc"));
        items.clear();
        items.addAll(rebuilt);
        // 光标尽量还停在原来那本书上，别因为前面插进来一本就跳到别处
        if (curLabel != null) {
            for (int i = 0; i < items.size(); i++) {
                if (curLabel.equals(items.get(i).label)) { sc.cur = i; return; }
            }
        }
        if (sc.cur >= items.size()) sc.cur = Math.max(0, items.size() - 1);
    }

    /** 命中区之内：完全同名、分类命中、书名或作者含关键词，这三种算命中 */
    private static boolean isHit(String[] b, String key) { return tierOf(b, key) <= 2; }

    /** 列表里非书条目的数量（「精准搜索」开关这类），书的行号要加上它 */
    private static int baseCount(List<Item> items) {
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            String kk = items.get(i).key;
            if (kk == null || !kk.startsWith("nb:book:")) n++;
        }
        return n;
    }

    /** 把 sc.items 里书的行号键按当前顺序重写一遍：插进来一行，后面所有书的下标都变了 */
    private void renumberSearchKeys(Screen sc) {
        int bi = 0;
        for (int i = 0; i < sc.items.size(); i++) {
            String kk = sc.items.get(i).key;
            if (kk != null && kk.startsWith("nb:book:")) {
                sc.items.get(i).key = "nb:book:" + (bi++) + ":" + nbResultsSeq;
            }
        }
    }

    /** 搜索结果只插不重建：命中的插在命中区末尾、完全同名的顶到最前、不命中的排最后。
     *  已经上屏的行位置钉死，只把插入点之后的行重排序号，读屏不会因为又搜到一本就把前面念一遍。
     *  界面那一步交给 host，这里只管数据。 */
    private void insertSearchRow(String label, Screen sc, int at, int pos) {
        if (sc == null || sc.reader) return;
        // 第一次出结果时先把「正在搜…」那条占位拿掉
        int secAt = -1;
        for (int i = 0; i < sc.items.size() && i < 4; i++) {
            if ("sec".equals(sc.items.get(i).key)) { secAt = i; break; }
        }
        if (secAt >= 0) {
            sc.items.remove(secAt);
            if (secAt <= at) at--;
        }
        if (at < 0) at = 0;
        if (at > sc.items.size()) at = sc.items.size();
        // 行号键必须带上：以前这里留空，收尾 rebuildSearchItems 靠 nb:book: 前缀剔旧行，
        // 空键一个都剔不掉，结果列表里每本书出现两遍（前一遍是乱序到达顺序，正版沉在后面那遍的开头）
        sc.items.add(at, new Item(label, "nb:book:" + pos + ":" + nbResultsSeq));
        renumberSearchKeys(sc);
        host.insertSearchRowView(sc, at, secAt);
    }

    /** 字数文本转数字：支持「1.2万」「123456」「120000字」这几种写法 */
    private static long parseWordCount(String s) {
        if (s == null) return 0;
        String t = s.replace("字", "").replace(",", "").trim();
        if (t.isEmpty()) return 0;
        double mul = 1;
        if (t.contains("万")) { mul = 10000; t = t.replace("万", ""); }
        else if (t.contains("千")) { mul = 1000; t = t.replace("千", ""); }
        try { return (long) (Double.parseDouble(t.trim()) * mul); } catch (Throwable ignored) { return 0; }
    }

    private void stack_add(final Screen sc) {
        ui.post(new Runnable() { @Override public void run() { host.pushScreen(sc); } });
    }

    private boolean stack_contains(final Screen sc) {
        return host.top() == sc;
    }

    /** 边搜边出：先建空页，每个书源回来就往页尾追加一条，不再等全部跑完 */
    public void nbSearch(final String key) {
        loadBookSources();
        if (nbSources.isEmpty()) {
            host.speak("还没有书源，先把书源 JSON 放到盲界阅读的书源目录");
            host.toast("还没有书源");
            return;
        }
        int textN = countSearchable();
        host.log("搜索", "共 " + nbSources.size() + " 个源，范围=" + searchScopeLabel()
                + "，可搜 " + textN + " 个");
        // 范围指的分组/源已经不存在（改了名、删了源），会一个都匹配不上。
        // 以前这里直接报「没有可用的文本书源」就退出，人卡死在这一步连菜单都进不去。
        if (textN == 0 && !searchScope().isEmpty()) {
            host.log("搜索", "搜索范围「" + searchScope() + "」一个源都没匹配到，自动改回全部书源");
            setSearchScope("");
            textN = countSearchable();
        }
        if (textN == 0) {
            int enabledN = 0;
            for (int si = 0; si < nbSources.size(); si++) if (srcEnabled(nbSources.get(si))) enabledN++;
            host.log("搜索", "可搜为 0：启用的源 " + enabledN + " 个");
            host.speak("没有可用的文本书源：共 " + nbSources.size() + " 个源，启用 " + enabledN
                    + " 个。去书源管理里把源打开再搜");
            host.toast("没有可用的文本书源");
            return;
        }
        saveSearchKey(key);                  // 记一笔：同一个词只加次数、刷新时间，不新增一条
        final String kt = key == null ? "" : key.trim();
        final List<Item> items = new ArrayList<>();
        // 搜索框钉在第一行：双击回输入页改关键词，不用先进菜单翻「换关键词」
        items.add(new Item("搜索关键词：" + kt + "，点一下改", "nb:reinput"));
        // 最近搜过的词搬到「资源搜索」输入页去了（用户要求：最近搜索就放在输入页里），
        // 结果页顶部不再重复列，只留一行回输入页改关键词的入口
        // 开关跟着往下排，双击就能切，不用先进菜单找（去重：源返回的无关书太多时开它）
        items.add(new Item("精准搜索：" + (precisionSearch() ? "开（只留书名或作者含关键字的）" : "关")
                + "，点一下切换", "nb:prec"));
        items.add(new Item("正在搜 " + textN + " 个文本书源…　搜索菜单里可以停", "sec"));
        nbSecIdx = items.size() - 1;
        nbSecAt = 0L;
        String scopeTxt = searchScopeLabel();
        final Screen sc = new Screen("搜索　" + key + (searchScope().isEmpty() ? "" : "　" + scopeTxt), items).k("nbSearch");
        nbResults = new ArrayList<>();
        nbResultsSeq++;
        nbLastKey = key == null ? "" : key.trim();
        stack_add(sc);
        host.speak("正在同时搜 " + textN + " 个文本书源");
        nbSearchBusy = true;
        nbSearchSeq++;                       // 新一轮搜索，上一轮的迟到结果作废
        nbSearchStream(key, sc, items, nbSearchSeq);
    }

    /** 当前搜索范围下能拿来搜的源有几个 */
    private int countSearchable() {
        int n = 0;
        for (int si = 0; si < nbSources.size(); si++) if (srcSearchOk(si) && inSearchScope(si)) n++;
        return n;
    }

    /** 逐源搜，谁先回来谁先上屏（跟 Legado 的追加式列表一致） */
    private void nbSearchStream(final String key, final Screen sc, final List<Item> items, final int seq) {
        final List<Integer> use = new ArrayList<>();
        for (int si = 0; si < nbSources.size(); si++) if (srcSearchOk(si) && inSearchScope(si)) use.add(si);
        final int n = use.size();
        nbHitCount = 0;           // 新一轮，命中区重新算
        int want = prefs.getInt("searchThreads", 16);
        if (want > 9) want = 9;
        if (want < 1) want = 1;
        if (n == 0) {
            nbSearchBusy = false;
            host.appendSearchRow("（没搜到，换个书名或检查书源）", "", sc);
            host.speak("没搜到（范围：" + searchScopeLabel() + "）");
            return;
        }
        final int total = n;
        final java.util.concurrent.atomic.AtomicInteger done =
                new java.util.concurrent.atomic.AtomicInteger();
        final long[] lastRender = new long[]{0};
        // 同名同作者的多源合成一条（对齐 Legado 的 mergeItems），来源累积到第 4 栏
        final java.util.Map<String, Integer> bookAt = new java.util.HashMap<String, Integer>();
        // 按「同时搜几个源」分批投：先投 want 个，回来一个再投下一个，设置里改的数字真的生效
        final java.util.concurrent.atomic.AtomicInteger cursor =
                new java.util.concurrent.atomic.AtomicInteger();
        // 每轮自己的池，大小就是设置的并发数。以前所有轮共用一个写死 4 线程的 static 池，
        // 连点几十次搜索就往无界队列里堆了几千个旧任务，当前这轮排在他们后面出不来
        final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(want, new java.util.concurrent.ThreadFactory() {
                    private final java.util.concurrent.atomic.AtomicInteger SEQ =
                            new java.util.concurrent.atomic.AtomicInteger();
                    @Override public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "search-" + SEQ.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                });
        java.util.concurrent.ExecutorService old = nbSearchPool;
        nbSearchPool = pool;
        if (old != null && old != pool) old.shutdownNow();     // 上一轮的旧任务连同排队的全砍掉
        final Runnable[] pump = new Runnable[1];
        pump[0] = new Runnable() { @Override public void run() {
            if (seq != nbSearchSeq) return;          // 这轮已经被新搜索顶掉，别再往下投
            int next = cursor.getAndIncrement();
            if (next >= n) return;
            final int fsi = use.get(next);
            try {
                pool.submit(new Runnable() { @Override public void run() {
                    List<String[]> one = new ArrayList<String[]>();
                    int rest;
                    try {
                        if (seq != nbSearchSeq) return;   // 在队列里排到的时候已经过期，秒退，不占线程
                        one = nbSearchInSource(fsi, key);
                    } catch (Throwable t) {
                        // 以前这里是 Exception，抛 Throwable 的源连计数都加不上
                        host.log("搜索", "源 " + fsi + " 异常：" + t.getClass().getSimpleName()
                                + "：" + (t.getMessage() == null ? "" : t.getMessage()));
                    } finally {
                        // 计数必须无条件加：以前只在正常返回后加，异常源算不进来，
                        // 收尾条件 rest>=total 永远凑不满，排序和「共 N 本」一次都没跑过
                        rest = done.incrementAndGet();
                    }
                    final List<String[]> fone = one;
                    final int frest = rest;
                    final List<String[]> mine = nbResults;        // 这一轮的容器，之后可能被换源等流程整个换掉
                    ui.post(new Runnable() { @Override public void run() {
                        if (seq != nbSearchSeq) return;          // 已经有更新的一次搜索了
                        if (mine != nbResults) return;           // 结果容器被换掉了（换源、重新搜），这一路作废
                        for (String[] b : fone) {
                            String bk = mergeKeyOf(b);
                            Integer at = bookAt.get(bk);
                            if (at != null && at >= 0 && at < nbResults.size()) {
                                String[] ex = nbResults.get(at);
                                boolean merged = false;
                                // 已经上屏的那条地址像搜索页、新来的这条像详情页：把书名与地址换成新的。
                                // 否则合并后保留的是先到的假地址，人点进去还是 0 章。
                                if (looksLikeSearchUrl(ex[2]) && !looksLikeSearchUrl(b[2])) {
                                    ex[0] = b[0];
                                    ex[1] = b[1];
                                    ex[2] = b[2];
                                    merged = true;
                                }
                                if (!ex[4].contains(b[4])) {
                                    ex[4] = ex[4] + "／" + b[4];
                                    merged = true;
                                }
                                if (merged) {
                                    // 同一本书又多了个来源：只改这一行的文字，位置不动
                                    host.updateSearchRow(baseCount(items) + at, nbResultLabel(ex));
                                }
                                continue;
                            }
                            // 按命中档位定插入位置：完全同名的顶到最前；分类/书名命中的追加到命中区末尾；
                            // 不命中的排到最后。命中区已经上屏的那几本位置钉死，不跟着后面新来的挪
                            int t = tierOf(b, key);
                            int pos;
                            if (t == 0) pos = 0;
                            else if (t <= 2) pos = nbHitCount;
                            else pos = nbResults.size();
                            if (pos < 0) pos = 0;                       // 命中数可能是上一轮留下的，夹住再用
                            if (pos > nbResults.size()) pos = nbResults.size();
                            nbResults.add(pos, b);
                            if (t <= 2) nbHitCount++;
                            bookAt.clear();
                            for (int i = 0; i < nbResults.size(); i++) bookAt.put(mergeKeyOf(nbResults.get(i)), i);
                            insertSearchRow(nbResultLabel(b), sc, baseCount(items) + pos, pos);
                        }
                        // 回进度：每 2.5 秒把那行「正在搜…」改写一次。
                        // 以前这一行从建页到收尾一个字都不变，463 个源跑几十秒，人只能干等，也不知道要不要停
                        long nowS = System.currentTimeMillis();
                        if (frest < total && nbSecIdx >= 0 && nbSecIdx < items.size()
                                && nowS - nbSecAt > 2500 && host.top() == sc) {
                            nbSecAt = nowS;
                            String prog = "正在搜 " + total + " 个源，已回来 " + frest
                                    + " 个，找到 " + nbResults.size() + " 本…　搜索菜单里可以停";
                            items.set(nbSecIdx, new Item(prog, "sec"));
                            host.updateSearchRow(nbSecIdx, prog);
                        }
                        if (frest >= total) {
                            nbSearchBusy = false;         // 这一轮收尾，菜单里不再露「停止搜索」
                            if (nbResults.isEmpty()) {
                                host.appendSearchRow("（没搜到，换个书名或检查书源）", "", sc);
                                host.speak("没搜到（范围：" + searchScopeLabel() + "）");
                            } else {
                                // 收尾不再整体重排：命中区在搜索过程中已经按档位插好了，
                                // 这时候再排一次等于把刚钉住的位置全打乱，读屏又要重念一遍。
                                // 只把末尾那一行「只看其中一个来源」挂上，再报总数
                                rebuildSearchItems(sc, items, true);
                                // 焦点落到第一本书。以前这里写死 1，前提是头部只有一行；
                                // 现在头部有搜索框＋精准开关，写死 1 会停在精准开关上
                                sc.cur = 0;
                                for (int q = 0; q < items.size(); q++) {
                                    String qk = items.get(q) == null ? null : items.get(q).key;
                                    if (qk != null && qk.startsWith("nb:book:")) { sc.cur = q; break; }
                                }
                                // 人可能已经不在这一页了，只有它还停在最上面才重建界面
                                if (host.top() == sc) {
                                    host.render(true);
                                    // 总数和第一本单独念一遍。以前这句只进了日志，
                                    // 读屏模式下人只听到标题「搜索　斗破苍穹」，搜到几本全靠自己数
                                    host.speak("搜索完成，共 " + nbResults.size() + " 本，第一本 "
                                            + nbResults.get(0)[0]);
                                }
                                host.log("搜索", "排序后第一本 " + nbResults.get(0)[0]
                                        + "，共 " + nbResults.size() + " 本，列表 " + items.size() + " 项");
                            }
                        }
                    } });
                    pump[0].run();      // 这一路回来了，再放下一个源出去
                } });
            } catch (Throwable t) {
                done.incrementAndGet();      // 池不收也得算，否则收尾卡死
                host.log("搜索", "投递失败：" + t.getMessage());
            }
        } };
        int first = Math.min(want, n);
        for (int k0 = 0; k0 < first; k0++) pump[0].run();
    }

    public int nbCountChapters(JSONObject src, String bookUrl) {
        try {
            String base = rules.ruleStr(src, "bookSourceUrl");
            JSONObject rc = rules.sub(src, "ruleToc");
            if (rc == null) rc = rules.sub(src, "ruleChapterList");
            if (rc == null) return -1;
            String tocUrl = bookUrl;
            String tok = rules.ruleStr(src, "tocUrl");
            if (!tok.isEmpty()) {
                String u = rules.urlOf(tok, base, bookUrl);
                if (!u.isEmpty()) tocUrl = u.startsWith("http") ? u : (u.startsWith("/") ? base + u : base + "/" + u);
            }
            String html = req(tocUrl, base);
            if (html == null) return -1;
            String nTok = rules.ruleStr(rc, "name").isEmpty() ? "text" : rules.ruleStr(rc, "name");
            String uTok = rules.ruleStr(rc, "url").isEmpty() ? "href" : rules.ruleStr(rc, "url");
            List<String> names = rules.ruleAll(nTok, base, html);
            List<String> urls = rules.ruleAll(uTok, base, html);
            return Math.min(names.size(), urls.size());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 第 si 个源的源网址（书架上用它当标识，源增删排序都不会错位） */
    public String sourceKeyAt(int si) {
        if (si < 0 || si >= nbSources.size()) return "";
        String k = rules.ruleStr(nbSources.get(si), "bookSourceUrl");
        return k == null ? "" : k.trim();
    }

    /** 反查：给一个源网址（或老的序号），返回现在的源下标；找不到返回 -1 */
    public int sourceIdxByRef(String ref, String bookUrl) {
        if (ref == null) ref = "";
        ref = ref.trim();
        boolean digits = ref.length() > 0;
        for (int i = 0; i < ref.length(); i++) if (!Character.isDigit(ref.charAt(i))) { digits = false; break; }
        if (digits) {                                     // 老记录：序号
            int si;
            try { si = Integer.parseInt(ref); } catch (Exception e) { si = -1; }
            if (si >= 0 && si < nbSources.size()) return si;
        }
        if (!ref.isEmpty()) {                             // 新记录：源网址
            for (int i = 0; i < nbSources.size(); i++) {
                if (ref.equals(rules.ruleStr(nbSources.get(i), "bookSourceUrl").trim())) return i;
            }
        }
        // 最后按书网址猜：哪本书的详情页地址落在某个源的域名下
        if (bookUrl != null && !bookUrl.isEmpty()) {
            for (int i = 0; i < nbSources.size(); i++) {
                String base = rules.ruleStr(nbSources.get(i), "bookSourceUrl").trim();
                if (base.isEmpty()) continue;
                String host = base.replace("https://", "").replace("http://", "");
                int slash = host.indexOf('/');
                if (slash > 0) host = host.substring(0, slash);
                if (!host.isEmpty() && bookUrl.contains(host)) return i;
            }
        }
        return -1;
    }

    /** 源没了：拿书名去所有文本书源里重找一个，找到就接着开 */
    public void nbOpenByFallback(final String name, final String bookUrl) {
        final String want = name == null ? "" : name.trim();
        if (want.isEmpty()) { host.speak("这本书的书源不在了，请重新搜索加入"); return; }
        host.speakQueued("书源不在了，正在给你的书重新找源");
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            List<Integer> use = new ArrayList<>();
            for (int si = 0; si < nbSources.size(); si++) if (srcSearchOk(si)) use.add(si);
            int want2 = prefs.getInt("searchThreads", 16);
            if (want2 > 9) want2 = 9;
            if (want2 < 1) want2 = 1;
            final List<String[]> hits = java.util.Collections.synchronizedList(new ArrayList<String[]>());
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(Math.min(want2, Math.max(1, use.size())));
            for (final int fsi : use) {
                pool.submit(new Runnable() { @Override public void run() {
                    if (!hits.isEmpty()) return;
                    List<String[]> one = nbSearchInSource(fsi, want);
                    for (String[] b : one) {
                        if (b[0] != null && b[0].trim().equals(want)) { hits.add(new String[]{b[0], b[1], b[2], "" + fsi}); return; }
                    }
                    if (!one.isEmpty() && hits.isEmpty()) hits.add(new String[]{one.get(0)[0], one.get(0)[1], one.get(0)[2], "" + fsi});
                } });
            }
            pool.shutdown();
            try { pool.awaitTermination(90, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
            ui.post(new Runnable() { @Override public void run() {
                if (hits.isEmpty()) { host.speak("所有书源里都没找到《" + want + "》，请重新搜索加入"); return; }
                // 结果容器要换掉：先作废还在跑的那一轮搜索，否则它往新容器里按旧位置插，直接越界崩主线程
                // （日志实证 a11y-20261121-135720：13:57:17 换源后 Index: 268, Size: 4）
                stopSearchQuiet();
                nbResults = new ArrayList<>();
                nbResults.add(hits.get(0));
                host.speakQueued("换了书源，正在打开《" + want + "》");
                nbOpenBook(0);
            } });
        } });
    }

    /** 这本书在不在书架里 */
    public boolean nbIsFav(String url) {
        if (url == null || url.isEmpty()) return false;
        for (String line : prefs.getString("nbFav", "").split("\n")) {
            String[] p = line.split("\u0001");
            if (p.length >= 2 && p[1].trim().equals(url)) return true;
        }
        return false;
    }

    public void nbCheckUpdates() {
        final List<String[]> favs = new ArrayList<>();
        String store = prefs.getString("nbFav", "");
        if (store != null && !store.isEmpty()) {
            for (String line : store.split("\n")) {
                String[] p = line.split("\u0001");
                if (p.length >= 3) favs.add(p);
            }
        }
        if (favs.isEmpty()) { host.speak("还没有收藏网络书"); host.toast("还没有收藏"); return; }
        host.speak("检查 " + favs.size() + " 本收藏的更新");
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            List<String> rows = new ArrayList<>();
            int changed = 0;
            for (String[] f : favs) {
                String name = f[0];
                String url = f[1];
                int si;
                try { si = Integer.parseInt(f[2]); } catch (Exception e) { si = -1; }
                if (si < 0 || si >= nbSources.size()) continue;
                int now = nbCountChapters(nbSources.get(si), url);
                int old = prefs.getInt("nbCnt_" + url, 0);
                if (now <= 0) { rows.add("？ 《" + name + "》 没数出来"); continue; }
                if (old <= 0) { rows.add("· 《" + name + "》 共 " + now + " 章（首次记录）"); }
                else if (now > old) { rows.add("↑ 《" + name + "》 " + old + " → " + now + " 章，多 " + (now - old) + " 章"); changed++; }
                else rows.add("· 《" + name + "》 共 " + now + " 章，没变化");
            }
            final List<String> r = rows;
            final int ch = changed;
            ui.post(new Runnable() { @Override public void run() {
                List<Item> items = new ArrayList<>();
                for (String line : r) items.add(new Item(line));
                if (r.isEmpty()) items.add(new Item("（收藏里没有能检查的条目）"));
                items.add(new Item("再检查一次", "nb:checkupd"));
                stack.add(new Screen("收藏更新", items));
                host.render(true);
                host.speak(ch == 0 ? "没有新章" : (ch + " 本有新章"));
                host.log("追更", "检查 " + favs.size() + " 本，更新 " + ch + " 本");
            } });
        } });
    }

    public void nbStartSwitch() {
        final String name = nbBookName == null ? "" : nbBookName.trim();
        final String author = nbBookAuthor == null ? "" : nbBookAuthor.trim();
        if (name.isEmpty()) { host.speak("还没打开书"); return; }
        final boolean checkAuthor = prefs.getBoolean("swCheckAuthor", true);
        final boolean loadToc = prefs.getBoolean("swLoadToc", true);
        final int sortMode = prefs.getInt("swSortMode", 1);   // 0 源顺序 1 章数 2 字数
        final String groupFilter = prefs.getString("swGroup", "").trim();
        final String wordFilter = prefs.getString("swKey", "").trim();
        host.speak("正在别的书源里找 " + name);
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            List<String[]> all = nbSearchAll(name);
            final List<String[]> cand = new ArrayList<>();
            // 完全同名的顶到最前：Legado 换源 filter 是 fName == name（严格相等），
            // 我们为了不漏「书名（完）」这类放宽成了 startsWith，会混进《斗破苍穹之xxx》，
            // 这里只拿它决定排序先后，筛选口径不动
            final java.util.Map<String, Boolean> exactMap = new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
            for (String[] b : all) {
                if (b[2] != null && b[2].equals(nbBookUrl)) continue;      // 同一个地址，跳过
                // 换源要同类：文本换文本，音频换音频，别把音频源当文本候选
                int siT = 0;
                try { siT = Integer.parseInt(b[3]); } catch (Exception ignored) {}
                if (siT < 0 || siT >= nbSources.size()) continue;
                if (srcType(nbSources.get(siT)) != (nbIsAudioBook ? 1 : 0)) continue;
                String nm = b[0] == null ? "" : b[0].trim();
                if (!(nm.equals(name) || nm.startsWith(name) || name.startsWith(nm))) continue;
                // 书源分组：跟 Legado 的 searchGroup 一样，只在指定分组里找（逗号分隔，任一命中即可）
                if (!groupFilter.isEmpty()) {
                    int gsi = 0;
                    try { gsi = Integer.parseInt(b[3]); } catch (Exception ignored) {}
                    String g = (gsi >= 0 && gsi < nbSources.size())
                            ? nbSources.get(gsi).optString("bookSourceGroup", "") : "";
                    if (!groupHit(g, groupFilter)) continue;
                }
                // 筛选词：对齐 Legado 的 screen(key)，匹配书源名（它还能匹配最新章节标题，我们没有那个字段）
                if (!wordFilter.isEmpty()) {
                    String sName = b.length > 4 && b[4] != null ? b[4] : "";
                    boolean hit = sName.contains(wordFilter) || nm.contains(wordFilter)
                            || (b[1] != null && b[1].contains(wordFilter));
                    if (!hit) continue;
                }
                // 作者核对（对齐 Legado：书名 + 作者一起对；两边都有作者却对不上就不算同一本）
                if (checkAuthor) {
                    String au = b[1] == null ? "" : b[1].trim();
                    if (!author.isEmpty() && !au.isEmpty()
                            && !(au.equals(author) || au.contains(author) || author.contains(au))) continue;
                }
                exactMap.put(b[2] == null ? "" : b[2], b[0] != null && b[0].trim().equals(name));
                cand.add(b);
            }
            // 目录验证：取不到目录的源不算有效源，顺手拿章数当排序依据（Legado 也是先验目录再排序）
            final List<String[]> valid = new ArrayList<>();
            final List<Integer> cnts = new ArrayList<>();
            final List<String> wcs = new ArrayList<>();
            if (!cand.isEmpty()) {
                final List<String[]> okList = java.util.Collections.synchronizedList(new ArrayList<String[]>());
                final List<Integer> okCnt = java.util.Collections.synchronizedList(new ArrayList<Integer>());
                final List<String> okWc = java.util.Collections.synchronizedList(new ArrayList<String>());
                java.util.concurrent.ExecutorService pool =
                        java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, cand.size()));
                for (final String[] b : cand) {
                    pool.submit(new Runnable() { @Override public void run() {
                        int si = 0;
                        try { si = Integer.parseInt(b[3]); } catch (Exception ignored) {}
                        int n = loadToc ? nbTocCount(si, b[2]) : -2;
                        if (n < 0 && loadToc) { host.log("换源", "目录取不到，跳过：" + b[2]); return; }
                        String wc = "";
                        try {                                   // 顺带抓字数（对齐 Legado 的字数排序）
                            String[] d = nbFetchDetail(si, b[2]);
                            if (d != null && d.length > 3 && d[3] != null) wc = d[3].trim();
                        } catch (Throwable ignored) {}
                        okList.add(b);
                        okCnt.add(n);
                        okWc.add(wc);
                    } });
                }
                pool.shutdown();
                try { pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) {}
                synchronized (okList) { valid.addAll(okList); }
                synchronized (okCnt) { cnts.addAll(okCnt); }
                synchronized (okWc) { wcs.addAll(okWc); }
            }
            // 章数多的靠前（缺章少），同章数保持源顺序
            final List<Integer> order = new ArrayList<Integer>();
            for (int i = 0; i < valid.size(); i++) order.add(i);
            // 排序三段：完全同名 → 字数/章数（看 sortMode）→ 源顺序
            java.util.Collections.sort(order, new java.util.Comparator<Integer>() {
                private boolean exact(int i) {
                    String[] b = valid.get(i);
                    return Boolean.TRUE.equals(exactMap.get(b[2] == null ? "" : b[2]));
                }
                @Override public int compare(Integer a, Integer b2) {
                    boolean ea = exact(a), eb = exact(b2);
                    if (ea != eb) return ea ? -1 : 1;
                    if (sortMode == 2) {                        // 按字数：多的在前
                        long wa = parseWordCount(wcs.get(a)), wb = parseWordCount(wcs.get(b2));
                        if (wa != wb) return wb > wa ? 1 : -1;
                    }
                    if (sortMode != 0) {
                        int ca = cnts.get(a), cb = cnts.get(b2);
                        if (ca != cb) return cb - ca;
                    }
                    return a - b2;
                }
            });
            final List<String[]> out = new ArrayList<>();
            final List<Integer> outCnt = new ArrayList<>();
            final List<String> outWc = new ArrayList<>();
            for (int i : order) {
                out.add(valid.get(i));
                outCnt.add(cnts.get(i));
                outWc.add(i < wcs.size() ? wcs.get(i) : "");
            }
            ui.post(new Runnable() { @Override public void run() {
                if (out.isEmpty()) {
                    String tip;
                    if (!groupFilter.isEmpty() || !wordFilter.isEmpty()) tip = "按当前筛选没有可用的源，改一下筛选试试";
                    else if (cand.isEmpty()) tip = "别的书源里没找到这本";
                    else tip = "找到了，但目录都取不到，这些源用不了";
                    if (!groupFilter.isEmpty() || !wordFilter.isEmpty()) {
                        // 空结果也把筛选行留在页上，用户才能改回来
                        List<Item> only = new ArrayList<>();
                        only.add(new Item("书源分组：" + (groupFilter.isEmpty() ? "全部" : groupFilter), "nb:swgroup"));
                        only.add(new Item("筛选词：" + (wordFilter.isEmpty() ? "无" : wordFilter), "nb:swkey"));
                        only.add(new Item("校验作者：" + (checkAuthor ? "开" : "关") + "，点一下切换", "nb:swauthor"));
                        only.add(new Item("先验目录：" + (loadToc ? "开" : "关") + "，点一下切换", "nb:swtoc"));
                        only.add(new Item("重新搜索", "nb:switch"));
                        only.add(new Item(tip));
                        Screen pg = new Screen("换源　" + name, only);
                        Screen ct = host.top();
                        if (ct != null && ct.title != null && ct.title.startsWith("换源")) stack.set(stack.size() - 1, pg);
                        else stack.add(pg);
                        host.render(true);
                    }
                    host.speak(tip);
                    host.toast("换源没有可用的源");
                    return;
                }
                nbResultsSeq++;              // 结果集换掉了，屏上老下标作废
                nbResults = out;
                List<Item> items = new ArrayList<>();
                items.add(new Item("校验作者：" + (checkAuthor ? "开" : "关") + "，点一下切换", "nb:swauthor"));
                items.add(new Item("先验目录：" + (loadToc ? "开" : "关") + "，点一下切换", "nb:swtoc"));
                items.add(new Item("排序：" + (sortMode == 0 ? "按源顺序" : (sortMode == 1 ? "按章数" : "按字数"))
                        + "，点一下切换", "nb:swsort"));
                items.add(new Item("书源分组：" + (groupFilter.isEmpty() ? "全部" : groupFilter), "nb:swgroup"));
                items.add(new Item("筛选词：" + (wordFilter.isEmpty() ? "无" : wordFilter), "nb:swkey"));
                items.add(new Item("重新搜索", "nb:switch"));
                items.add(new Item("—— 共 " + out.size() + " 个可用源 ——"));
                for (int i = 0; i < out.size(); i++) {
                    String[] b = out.get(i);
                    StringBuilder sb = new StringBuilder(b[0]);
                    if (b[1] != null && !b[1].isEmpty()) sb.append("　").append(b[1]);
                    int si2 = 0;
                    try { si2 = Integer.parseInt(b[3]); } catch (Exception ignored) {}
                    if (si2 >= 0 && si2 < nbSources.size())
                        sb.append("　").append(rules.ruleStr(nbSources.get(si2), "bookSourceName"));
                    if (loadToc) sb.append("　").append(outCnt.get(i)).append(" 章");
                    if (i < outWc.size() && outWc.get(i) != null && !outWc.get(i).isEmpty())
                        sb.append("　").append(outWc.get(i)).append(" 字");
                    items.add(new Item(sb.toString(), "nb:sw:" + i));
                }
                Screen page = new Screen("换源　" + name, items);
                Screen curTop = host.top();
                if (curTop != null && curTop.title != null && curTop.title.startsWith("换源")) {
                    stack.set(stack.size() - 1, page);      // 改筛选后重搜：替换当前页，不叠栈
                } else {
                    stack.add(page);
                }
                host.render(true);
                host.log("换源", "候选 " + cand.size() + " 个，目录可用的 " + out.size() + " 个"
                        + "　校验作者=" + checkAuthor + " 验目录=" + loadToc + " 分组=" + groupFilter + " 筛选词=" + wordFilter);
                host.speak("找到 " + out.size() + " 个能用的源");
            } });
        } });
    }

    /**
     * 书籍详情（对齐 Legado 的 BookInfo 规则字段：name/author/intro/lastChapter/wordCount/coverUrl）
     * 返回 {书名, 作者, 源名, 字数, 最新章节, 简介, 封面地址}
     */
    public String[] nbFetchDetail(int si, String bookUrl) {
        String[] out = new String[]{"", "", "", "", "", "", ""};
        try {
            if (si < 0 || si >= nbSources.size() || bookUrl == null || bookUrl.isEmpty()) return out;
            JSONObject src = nbSources.get(si);
            bindSrc(src);
            JSONObject rt = rules.sub(src, "ruleBookInfo");
            String base = rules.ruleStr(src, "bookSourceUrl");
            out[2] = rules.ruleStr(src, "bookSourceName");
            String html = req(bookUrl, base);
            if (html == null || rt == null) return out;
            if (html.trim().isEmpty()) {
                host.log("详情", "详情响应是空的：" + bookUrl);
                return out;
            }
            String rawHtml = html;
            html = rules.applyInit(rt, base, html);   // ruleBookInfo.init：先归一化响应再取字段
            // 详情页这一趟只请求一次，把「页面是什么形态、init 有没有改写」记一行，
            // 后面 name 取空时才有依据判断是页面不对、还是规则不对。
            host.log("详情", "详情页=" + RuleEngine.bodyKind(rawHtml)
                    + " " + rawHtml.length() + "字"
                    + (rawHtml.equals(html) ? "" : "，init 已归一化")
                    + " 开头「" + RuleEngine.headOf(rawHtml, 20) + "」");
            out[0] = cleanName(pickDetail(rt, "name", base, html));
            out[1] = cleanAuthor(pickDetail(rt, "author", base, html));
            out[3] = wordCountFormat(pickDetail(rt, "wordCount", base, html));
            out[4] = pickDetail(rt, "lastChapter", base, html);
            if (out[4].isEmpty()) out[4] = pickDetail(rt, "latestChapterTitle", base, html);
            out[5] = cleanIntro(pickDetail(rt, "intro", base, html));
            out[6] = pickDetail(rt, "coverUrl", base, html);
            host.log("详情", "取到《" + out[0] + "》 作者=" + out[1] + " 字数=" + out[3]);
            // 目录地址记住，打开目录时少请求一次详情页（对齐 Legado 把 tocUrl 存进 Book）
            String tRule = rules.ruleStr(rt, "tocUrl");
            if (!tRule.isEmpty()) {
                String t = rules.urlOf(tRule, base, html);
                if (!t.isEmpty()) prefs.edit().putString("nbToc_" + bookUrl, t).apply();
            }
        } catch (Throwable t) {
            host.log("详情", "取详情异常：" + t.getMessage());
        }
        return out;
    }

    /**
     * 用已经拿到的页面按 ruleBookInfo 解析出一条。
     * 对齐 Legado BookList.kt 的两条兜底：搜索地址本身就是详情页、或列表规则取不到元素时，
     * 直接把这一页当详情页出一本书，而不是判成"这个源搜不出东西"。
     */
    private String[] detailFromHtml(JSONObject src, String html, String base, String url) {
        try {
            JSONObject rt = rules.sub(src, "ruleBookInfo");
            if (rt == null) return null;
            String h = rules.applyInit(rt, base, html);
            String nm = cleanName(pickDetail(rt, "name", base, h));
            if (nm == null || nm.isEmpty()) return null;
            String au = cleanAuthor(pickDetail(rt, "author", base, h));
            return new String[]{nm, au == null ? "" : au, url};
        } catch (Throwable t) {
            host.log("听书", "详情页兜底失败：" + t.getMessage());
            return null;
        }
    }

    /** 书名清洗：对齐 Legado 的 formatBookName（去掉「作者：xxx」「xxx 著」这类尾巴） */
    private static String cleanName(String v) {
        if (v == null) return "";
        String t = PuaText.fix(v).replace('\u3000', ' ').trim();
        t = java.util.regex.Pattern.compile("\\s+作\\s*者.*|\\s+\\S+\\s+著|(?<=.)作\\s*者\\s*[:：].*").matcher(t).replaceAll("");
        // 末尾的叹号、句点、省略号统一去掉：同一本书在不同源里常写成「xx！」「xx。」，差一个标点
        // 就被当成两本不同的书（合并不上、列表出现两条同名结果，一条能开一条 0 章）
        t = t.replaceAll("[！!。.·…~～]+$", "").trim();
        return t;
    }

    /** 作者清洗：对齐 Legado 的 formatBookAuthor（去掉开头的「作者：」） */
    private static String cleanAuthor(String v) {
        if (v == null) return "";
        String t = PuaText.fix(v).replace('\u3000', ' ').trim();
        t = java.util.regex.Pattern.compile("^\\s*作\\s*者[:：\\s]+|\\s+著").matcher(t).replaceAll("");
        return t.trim();
    }

    /** 字数格式化：对齐 Legado 的 wordCountFormat，过万显示「万字」 */
    private static String wordCountFormat(String v) {
        if (v == null) return "";
        String t = v.replace("字", "").replace(",", "").replace('\u3000', ' ').trim();
        try {
            long n = Long.parseLong(t);
            if (n > 10000) {
                java.text.DecimalFormat df = new java.text.DecimalFormat("#.#");
                return df.format(n / 10000.0) + "万字";
            }
            if (n > 0) return n + "字";
        } catch (Throwable ignored) {}
        return v.trim();
    }

    /** 简介清洗：对齐 Legado 的 formatIntro，去实体与标签、压空行，但不加段首缩进 */
    private static String cleanIntro(String v) {
        if (v == null) return "";
        String t = PuaText.fix(v);
        t = com.mangjie.reader.reader.HtmlFormat.formatIntro(t);
        return t.trim();
    }

    private String pickDetail(JSONObject rt, String name, String base, String html) {
        try {
            String rule = rules.ruleStr(rt, name);
            if (rule == null || rule.isEmpty()) return "";
            String r0 = rule.trim();
            // 地址字段走 URL 通道（对齐 Legado 的 isUrl）：规则整条是链接、或字段本身是封面地址时，
            // 取到后要按 base 绝对化，不能把结果当普通文本。
            boolean isAddr = r0.startsWith("http://") || r0.startsWith("https://")
                    || "coverUrl".equals(name) || "tocUrl".equals(name) || "bookUrl".equals(name);
            String v = isAddr ? rules.urlOf(rule, base, html) : rules.ruleFirst(rule, base, html);
            // 只在取空时记一行：以前每个字段都打「字段=… 规则=… 取值=…」，
            // 换源一次 6 本 × 7 字段把日志刷成几十行同样的空值（a11y-20261121-135720）
            if (v == null || v.trim().isEmpty()) {
                // 只记一行，但要能看出是哪一段断的：字段 / 规则形态 / 页面形态。
                // name、author 这两个是换源与书架认书的关键，多带个页面开头，方便一眼判断是
                // 「页面本身是空的或错的」还是「规则形态跟页面对不上」。
                String rk = RuleEngine.ruleKind(r0);
                String bk = RuleEngine.bodyKind(html);
                StringBuilder sb = new StringBuilder();
                sb.append("字段=").append(name).append(" 取空，规则形态=").append(rk)
                        .append("，页面=").append(bk).append("，规则=")
                        .append(r0.length() > 60 ? r0.substring(0, 60) + "…" : r0);
                boolean key = "name".equals(name) || "author".equals(name);
                if (key) sb.append("，页面开头「").append(RuleEngine.headOf(html, 20)).append("」");
                if ("json".equals(rk) && "HTML".equals(bk)) {
                    sb.append("，错配：规则要 JSON、响应是 HTML");
                } else if (("xpath".equals(rk) || "css".equals(rk)) && "JSON".equals(bk)) {
                    sb.append("，错配：规则要 HTML 结构、响应是 JSON");
                }
                host.log("详情", sb.toString());
            }
            if (v == null) return "";
            v = v.trim();
            if (v.contains("<")) v = org.jsoup.Jsoup.parse(v).text();
            return v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 封面下载到这本书的缓存目录（cover.img），已有就直接用 */
    public String nbFetchCover(int si, String coverUrl) {
        try {
            File dir = ChapterCache.bookDir(host.dataDir(), nbBookUrl);
            File f = new File(dir, "cover.img");
            if (f.isFile() && f.length() > 0) return f.getAbsolutePath();
            if (coverUrl == null || coverUrl.trim().isEmpty()) return null;
            String base = (si >= 0 && si < nbSources.size()) ? rules.ruleStr(nbSources.get(si), "bookSourceUrl") : "";
            String u = coverUrl.trim();
            if (u.startsWith("//")) u = "https:" + u;
            else if (!u.startsWith("http")) u = new java.net.URL(new java.net.URL(base), u).toString();
            if (!dir.exists()) dir.mkdirs();
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
            c.setRequestProperty("Referer", base);
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            o.close();
            in.close();
            return f.length() > 0 ? f.getAbsolutePath() : null;
        } catch (Throwable t) {
            host.log("详情", "封面下载失败：" + t.getMessage());
            return null;
        }
    }

    /** 书源分组是否命中：筛选词可用逗号/｜分隔多组，任一组包含即算 */
    private boolean groupHit(String srcGroup, String filter) {
        if (srcGroup == null || srcGroup.isEmpty()) return false;
        String[] parts = filter.split("[,，|｜]");
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (srcGroup.contains(t)) return true;
        }
        return false;
    }

    /** 换源验证：这个源能不能取到目录，取到多少章（小于 0 表示取不到） */
    public int nbTocCount(int si, String bookUrl) {
        try {
            if (si < 0 || si >= nbSources.size()) return -1;
            JSONObject src = nbSources.get(si);
            bindSrc(src);
            JSONObject rc = rules.sub(src, "ruleToc");
            if (rc == null) return -1;
            String base = rules.ruleStr(src, "bookSourceUrl");
            String lr = rules.ruleStr(rc, "chapterList");
            if (lr.startsWith("-")) lr = lr.substring(1);
            if (lr.startsWith("-")) lr = lr.substring(1);
            if (lr.isEmpty()) return -1;
            if (lr.startsWith(":")) return 1;          // 正则式目录：取不到元素列表，先按有效处理
            String bookHtml = req(bookUrl, base);
            if (bookHtml == null) return -1;
            // 目录可能在独立页（ruleBookInfo.tocUrl），跟开书时同一套取法
            String tocUrl = bookUrl;
            JSONObject rt = rules.sub(src, "ruleBookInfo");
            if (rt != null) {
                String t = rules.ruleStr(rt, "tocUrl");
                if (!t.isEmpty()) {
                    String t2 = rules.urlOf(t, base, bookHtml);
                    if (!t2.isEmpty()) tocUrl = t2;
                }
            }
            String html = tocUrl.equals(bookUrl) ? bookHtml : req(tocUrl, base);
            if (html == null) html = bookHtml;
            if (html.length() < 200) return -1;
            List<org.jsoup.nodes.Element> els = rules.defaultChain(lr, base, html);
            if (els == null) return -1;
            return els.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    public void nbOpenBook(final int i) {
        if (i < 0 || i >= nbResults.size()) return;
        final String[] b = nbResults.get(i);
        nbBookName = b[0];
        nbBookAuthor = b[1];
        nbBookUrl = b[2] == null ? "" : b[2].trim();
        if (nbBookUrl.isEmpty()) {
            host.speak("这本书没取到地址，重新搜一次或者换源");
            host.toast("书地址为空");
            return;
        }
        int si;
        try { si = Integer.parseInt(b[3]); } catch (Exception e) { si = 0; }
        nbSourceIdx = si;
        nbIsAudioBook = (si >= 0 && si < nbSources.size() && srcType(nbSources.get(si)) == 1);
        nbLastChapter = prefs.getString("nbLastCh_" + nbBookUrl, "");
        prefs.edit().putString("nbRecent", b[0] + "\u0001" + b[2] + "\u0001" + b[3]).apply();
        noteRecentBook(b[0], b[2], b[3]);
        host.speak("正在打开 " + b[0] + " 的目录");
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                List<String[]> chapters = new ArrayList<>();
                try {
                    JSONObject src = nbSources.get(nbSourceIdx);
                    bindSrc(src);
                    JSONObject rt = rules.sub(src, "ruleBookInfo");
                    JSONObject rc = rules.sub(src, "ruleToc");
                    String base = rules.ruleStr(src, "bookSourceUrl");
                    // 目录地址复用：详情页取过一次就记住（对齐 Legado 把 tocUrl 存进 Book 的做法），
                    // 下次打开这本书直接请求目录页，省掉详情页那一趟。
                    String cachedToc = prefs.getString("nbToc_" + nbBookUrl, "");
                    String bookHtml = null;
                    String rawBookHtml = null;   // init 之前的原始正文，目录规则要用它
                    String tocUrl = nbBookUrl;
                    String tRule = rt == null ? "" : rules.ruleStr(rt, "tocUrl");
                    if (!cachedToc.isEmpty()) {
                        tocUrl = cachedToc;
                        if (!tRule.isEmpty()) {
                            bookHtml = req(nbBookUrl, base);
                            rawBookHtml = bookHtml;
                            bookHtml = rules.applyInit(rt, base, bookHtml);
                            if (bookHtml != null) {
                                String t2 = rules.urlOf(tRule, base, bookHtml);
                                if (!t2.isEmpty() && !t2.equals(cachedToc)) {
                                    tocUrl = t2;
                                    prefs.edit().putString("nbToc_" + nbBookUrl, t2).apply();
                                }
                            }
                        }
                    } else {
                        bookHtml = req(nbBookUrl, base);
                        rawBookHtml = bookHtml;
                        bookHtml = rules.applyInit(rt, base, bookHtml);
                        if (rt != null && bookHtml != null) {
                            String t = rules.ruleStr(rt, "tocUrl");
                            if (!t.isEmpty()) {
                                String t2 = rules.urlOf(t, base, bookHtml);
                                if (!t2.isEmpty()) {
                                    tocUrl = t2;
                                    prefs.edit().putString("nbToc_" + nbBookUrl, t2).apply();
                                }
                            }
                        }
                    }
                    String tocLow = tocUrl == null ? "" : tocUrl.trim().toLowerCase(java.util.Locale.ROOT);
                    boolean tocPlaceholder = !tocLow.isEmpty()
                            && !tocLow.startsWith("http://") && !tocLow.startsWith("https://");
                    // tocUrl 就是详情页地址、正文又已经在手：直接复用，别再请求一次。
                    // 52书库这类站点同一个 URL 连发两遍会回 205 字节的反爬页（第二次就空目录），
                    // Legado 也是 tocUrl==baseUrl 时 tocHtml=body。
                    boolean reuseBookHtml = rawBookHtml != null
                            && !tocLow.isEmpty() && tocLow.equals(nbBookUrl == null ? "" : nbBookUrl.trim().toLowerCase(java.util.Locale.ROOT));
                    Http.Resp treq = reuseBookHtml ? null : reqR(tocUrl, base, 0);
                    String tocHtml = reuseBookHtml ? rawBookHtml : treq.body;
                    lastTocWhy = "";
                    if (tocPlaceholder) {
                        lastTocWhy = "目录地址没算出来（源里给的是占位串 "
                                + (tocUrl.length() > 40 ? tocUrl.substring(0, 40) + "…" : tocUrl)
                                + "），这源要靠 JS 拼目录地址，暂不支持";
                    } else if (tocHtml == null) {
                        if (bookHtml == null) {
                            Http.Resp breq = reqR(nbBookUrl, base, 0);
                            bookHtml = rules.applyInit(rt, base, breq.body);
                        }
                        tocHtml = bookHtml;
                        lastTocWhy = "目录地址没抓到：" + pageWhy(
                                treq == null || treq.url == null || treq.url.isEmpty() ? tocUrl : treq.url,
                                treq == null ? "" : treq.err, treq == null ? 0 : treq.code);
                    }
                    host.log("听书", "目录 源=" + rules.ruleStr(src, "bookSourceName") + " 书=" + nbBookUrl
                            + " 目录=" + (treq == null || treq.url == null || treq.url.isEmpty() ? tocUrl : treq.url)
                            + " 状态=" + (treq == null ? "复用详情" : treq.code)
                            + " 页面=" + (tocHtml == null ? -1 : tocHtml.length())
                            + " 规则=" + rules.ruleStr(rc, "chapterList"));
                    if (tocHtml != null && rc != null) {
                        String listRule = rules.ruleStr(rc, "chapterList");
                        String lr = listRule;
                        boolean tocReverse = lr.startsWith("-");
                        if (tocReverse) lr = lr.substring(1);
                        if (lr.startsWith("-")) { tocReverse = !tocReverse; lr = lr.substring(1); }
                        boolean allInOne = lr.startsWith(":");
                        if (allInOne) {
                            String pat = lr.substring(1);
                            String nTok0 = rules.ruleStr(rc, "chapterName");
                            String uTok0 = rules.ruleStr(rc, "chapterUrl");
                            try {
                                java.util.regex.Pattern pp = java.util.regex.Pattern.compile(pat);
                                java.util.regex.Matcher mm = pp.matcher(tocHtml);
                                while (mm.find() && chapters.size() < 30000) {
                                    String nm = host.groupOf(mm, nTok0);
                                    String uu = host.groupOf(mm, uTok0);
                                    if (nm == null || uu == null) continue;
                                    nm = nm.trim(); uu = uu.trim();
                                    if (nm.isEmpty() || uu.isEmpty()) continue;
                                    try { uu = new java.net.URL(new java.net.URL(tocUrl), uu).toString(); } catch (Exception ignored) {}
                                    chapters.add(new String[]{nm, uu});
                                }
                            } catch (Exception e) {
                                host.log("听书", "目录正则失败：" + e.getMessage());
                            }
                            if (tocReverse) java.util.Collections.reverse(chapters);
                        } else if (!lr.isEmpty()) {
                            String nTok = rules.ruleStr(rc, "chapterName");
                            if (nTok.isEmpty()) nTok = rules.ruleStr(rc, "name");
                            if (nTok.isEmpty()) nTok = "text";
                            String uTok = rules.ruleStr(rc, "chapterUrl");
                            if (uTok.isEmpty()) uTok = rules.ruleStr(rc, "url");
                            if (uTok.isEmpty()) uTok = "href";
                            /*
                             * 章节列表规则是 <js>...</js> 或 @js: 时，结果是 JSON 数组而不是 DOM，
                             * 不能交给 defaultChain（它按 CSS 解析，会得到 0 个元素）。
                             * 洋柿子酱的 <js>fqTocRows(...)</js> 就是这么被吃掉的：
                             * 目录地址早已正确、页面 99107 字节也拿到了，这里一过就剩 0 章。
                             */
                            boolean jsList = lr.contains("<js>") || lr.startsWith("@js:")
                                    || lr.startsWith("$") || lr.startsWith("@json:") || lr.startsWith("@JSON:");
                            int jsHit = 0;
                            List<org.jsoup.nodes.Element> chEls = null;
                            if (jsList) {
                                /*
                                 * <js> 里的 baseUrl 必须是这次真正请求出去的地址（对齐 Legado 的
                                 * AnalyzeUrl.url），不是 bookSourceUrl。洋柿子 fqTocRows 就是靠它
                                 * 反推 bookId；给源根地址会让它报「目录缺少书籍标识」。
                                 */
                                String jsBase = treq == null || treq.url == null || treq.url.isEmpty() ? base : treq.url;
                                List<Object> sits = rules.listItems(lr, jsBase, tocHtml);
                                jsHit = sits.size();
                                host.log("听书", "目录 @js 返回 " + sits.size() + " 条");
                                for (Object it : sits) {
                                    if (chapters.size() >= 30000) break;
                                    String nm = rules.fieldOf(nTok, it, base);
                                    String uu = rules.fieldOf(uTok, it, base);
                                    if (nm == null || uu == null) continue;
                                    nm = PuaText.fix(nm).trim(); uu = uu.trim();
                                    if (nm.isEmpty() || uu.isEmpty()) continue;
                                    chapters.add(new String[]{nm, uu});
                                }
                            } else {
                                chEls = rules.defaultChain(lr, base, tocHtml);
                                for (org.jsoup.nodes.Element e : chEls) {
                                    if (chapters.size() >= 30000) break;
                                    String nm = rules.pickElem(nTok, e, base);
                                    String uu = rules.pickElem(uTok, e, base);
                                    if (nm == null || uu == null) continue;
                                    nm = nm.trim(); uu = uu.trim();
                                    if (nm.isEmpty() || uu.isEmpty()) continue;
                                    chapters.add(new String[]{nm, uu});
                                }
                            }
                            // 目录翻页：Legado 的字段是 TocRule.nextTocUrl（不少源写 page），
                            // 一次可能吐多个下一页链接 → 并发抓、按顺序并进来。
                            String pageRule = rules.ruleStr(rc, "nextTocUrl");
                            if (pageRule.isEmpty()) pageRule = rules.ruleStr(rc, "page");
                            if (!pageRule.isEmpty() && chapters.size() < 30000) {
                                java.util.Set<String> seen = new java.util.HashSet<>();
                                seen.add(tocUrl);
                                String curToc = tocUrl;
                                String curHtml = tocHtml;
                                int pages = 1;
                                while (pages < 20 && chapters.size() < 30000) {
                                    java.util.List<String> nexts = rules.ruleAll(pageRule, base, curHtml);
                                    final java.util.List<String> absList = new ArrayList<>();
                                    for (String s2 : nexts) {
                                        String t2 = s2 == null ? "" : s2.trim();
                                        if (t2.isEmpty()) continue;
                                        String abs;
                                        try { abs = new java.net.URL(new java.net.URL(curToc), t2).toString(); }
                                        catch (Exception ex) { continue; }
                                        if (seen.add(abs)) absList.add(abs);
                                    }
                                    if (absList.isEmpty()) break;
                                    final int pn = absList.size();
                                    final String[] bodies = new String[pn];
                                    java.util.concurrent.ExecutorService pp2 = java.util.concurrent.Executors
                                            .newFixedThreadPool(Math.min(4, pn));
                                    for (int k2 = 0; k2 < pn; k2++) {
                                        final int fk = k2;
                                        pp2.submit(new Runnable() { @Override public void run() {
                                            bodies[fk] = req(absList.get(fk), base);
                                        } });
                                    }
                                    pp2.shutdown();
                                    try { pp2.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) {}
                                    for (int k2 = 0; k2 < pn; k2++) {
                                        String nh = bodies[k2];
                                        if (nh == null || chapters.size() >= 30000) continue;
                                        List<org.jsoup.nodes.Element> els2 = rules.defaultChain(lr, base, nh);
                                        for (org.jsoup.nodes.Element e : els2) {
                                            if (chapters.size() >= 30000) break;
                                            String nm = rules.pickElem(nTok, e, base);
                                            String uu = rules.pickElem(uTok, e, base);
                                            if (nm == null || uu == null) continue;
                                            nm = nm.trim(); uu = uu.trim();
                                            if (nm.isEmpty() || uu.isEmpty()) continue;
                                            chapters.add(new String[]{nm, uu});
                                        }
                                    }
                                    curToc = absList.get(pn - 1);
                                    curHtml = bodies[pn - 1] == null ? curHtml : bodies[pn - 1];
                                    pages += pn;
                                }
                                host.log("听书", "目录翻页：" + pages + " 页，共 " + chapters.size() + " 章"
                                        + " 规则=" + pageRule);
                            }
                            if (tocReverse) java.util.Collections.reverse(chapters);
                            int hitN = jsList ? jsHit : chEls.size();
                            host.log("听书", "目录取到 " + chapters.size() + " 章 源=" + rules.ruleStr(src, "bookSourceName")
                                    + " 命中=" + hitN + " 名规则=" + nTok + " 址规则=" + uTok);
                            if (chapters.isEmpty()) {
                                lastTocWhy = hitN == 0
                                        ? "页面拿到了（" + tocHtml.length() + " 字节），但目录规则没匹配上章节"
                                        : "目录规则命中 " + hitN + " 条，但书名或章节地址取不出来";
                            }
                        }
                    }
                } catch (Exception e) {
                    host.log("听书", "取目录失败：" + e.getMessage());
                }
                final int filteredN;
                {
                    java.util.List<String> words = new ArrayList<>();
                    String fw = prefs.getString("tocFilterWords", "");
                    if (fw.isEmpty()) fw = "求票,广告,上架感言,月票,推荐票,公告,加群,请假";
                    for (String w0 : fw.split("[,，]")) { String t = w0.trim(); if (!t.isEmpty()) words.add(t); }
                    int removed = 0;
                    if (!words.isEmpty()) {
                        List<String[]> keep = new ArrayList<>();
                        for (String[] c : chapters) {
                            boolean drop = false;
                            for (String w0 : words) if (c[0].contains(w0)) { drop = true; break; }
                            if (drop) { removed++; continue; }
                            keep.add(c);
                        }
                        chapters.clear();
                        chapters.addAll(keep);
                    }
                    if (prefs.getBoolean("tocRevAll", false)) java.util.Collections.reverse(chapters);
                    filteredN = removed;
                }
                ui.post(new Runnable() { @Override public void run() {
                    nbChapters = chapters;
                    if (!nbBookUrl.isEmpty() && !chapters.isEmpty())
                        prefs.edit().putInt("nbCnt_" + nbBookUrl, chapters.size()).apply();
                    List<Item> items = new ArrayList<>();
                    if (nbIsAudioBook) items.add(new Item("这是音频书，点章节就直接播放", ""));
                    items.add(new Item(nbIsFav(nbBookUrl) ? "★ 已在书架（点一下移出）" : "加入书架", "nb:favtoggle"));
                    items.add(new Item("书籍详情（封面、简介、字数）", "nb:detail"));
        items.add(new Item("换源（用别的书源找这本书）", "nb:switch"));
                    items.add(new Item("缓存整本到本地", "nb:cacheall"));
                    items.add(new Item("目录倒序：" + (prefs.getBoolean("tocRevAll", false) ? "开" : "关"), "nb:tocrev"));
                    items.add(new Item("目录过滤词", "nb:tocfilter"));
                    if (filteredN > 0) items.add(new Item("已过滤 " + filteredN + " 个章节"));
                    if (items.size() > 0 && chapters.size() > 0) items.add(new Item("全部章节：" + chapters.size() + " 章"));
                    if (chapters.size() > 1) items.add(new Item(nbIsAudioBook ? "从头开始播放" : "从头开始朗读", "nb:readall:0"));
                    for (int k = 0; k < chapters.size(); k++) items.add(new Item(chapters.get(k)[0], "nb:ch:" + k));
                    if (chapters.isEmpty()) {
                        items.add(new Item("重新加载目录", "nb:openbook:" + i));
                        items.add(new Item("查看听书日志", "nb:log"));
                    }
                    if (nbJumpChapter != null && !chapters.isEmpty()) {
                        int target = -1;
                        for (int k = 0; k < chapters.size(); k++) {
                            if (chapters.get(k)[0].equals(nbJumpChapter)) { target = k; break; }
                        }
                        if (target < 0) {
                            for (int k = 0; k < chapters.size(); k++) {
                                if (chapters.get(k)[0].startsWith(nbJumpChapter)
                                        || nbJumpChapter.startsWith(chapters.get(k)[0])) { target = k; break; }
                            }
                        }
                        nbJumpChapter = null;
                        if (target >= 0 && target < chapters.size()) {
                            nbOpenChapter(target, false);
                            host.speak("已换源，跳到第 " + (target + 1) + " 章");
                            host.log("听书", "换源对齐到第 " + (target + 1) + " 章");
                            return;
                        }
                        host.speak("共 " + chapters.size() + " 章，原来那一章没找到，从目录里选");
                        return;
                    }
                    stack.add(new Screen(nbBookName + "　" + chapters.size() + " 章", items).k("toc"));
                    host.render(true);
                    host.speak(chapters.isEmpty()
                            ? ("目录没取到，" + (lastTocWhy.isEmpty() ? "可以重新加载目录或换源" : lastTocWhy))
                            : ("共 " + chapters.size() + " 章"));
                } });
            }
        });
    }

    /**
     * 取这一章的音频地址，对齐 Legado 音频源的做法：
     * 章节 url 本身是音频文件就直接用；是网页就去页面里用 content 规则/<audio>/<source> 找地址。
     */
    public String nbAudioUrlOf(String chapterUrl) {
        if (chapterUrl == null || chapterUrl.isEmpty()) return "";
        String low = chapterUrl.toLowerCase();
        String[] exts = {".mp3", ".m4a", ".aac", ".flac", ".ogg", ".wav", ".m3u8", ".mp4", ".wma"};
        for (String e : exts) if (low.contains(e)) return chapterUrl;
        try {
            if (nbSourceIdx < 0 || nbSourceIdx >= nbSources.size()) return "";
            JSONObject src = nbSources.get(nbSourceIdx);
            bindSrc(src);
            String base = rules.ruleStr(src, "bookSourceUrl");
            String html = reqT(chapterUrl, base, STEP_MS);
            if (html == null) return "";
            JSONObject rc = rules.sub(src, "ruleContent");
            if (rc != null) {
                String c = rules.ruleStr(rc, "content");
                if (!c.isEmpty()) {
                    List<String> vals = rules.ruleAll(c, base, html);
                    for (String v : vals) {
                        String u = pickAudioUrl(v);
                        if (!u.isEmpty()) return absUrl(chapterUrl, u);
                    }
                }
            }
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html, base);
            for (org.jsoup.nodes.Element e : doc.select("audio[src], source[src], audio source[src]")) {
                String u = e.attr("src");
                if (u != null && !u.trim().isEmpty()) return absUrl(chapterUrl, u.trim());
            }
            // 兜底：页面里直接写着的音频地址
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(https?://[^\\s\"']+?\\.(?:mp3|m4a|aac|flac|ogg|m3u8))")
                    .matcher(html);
            if (m.find()) return m.group(1);
        } catch (Throwable t) {
            host.log("音频", "解析音频地址失败：" + t.getMessage());
        }
        return "";
    }

    /** 从规则取到的一小段里挑出音频地址 */
    private static String pickAudioUrl(String v) {
        if (v == null) return "";
        String t = v.trim();
        if (t.isEmpty()) return "";
        if (t.startsWith("http")) return t;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("src\\s*=\\s*[\"']([^\"']+)[\"']").matcher(t);
        if (m.find()) return m.group(1).trim();
        return "";
    }

    /** 音频缓存目录：一本书一个文件夹 */
    public java.io.File nbAudioDir(String bookUrl) {
        String h;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest((bookUrl == null ? "" : bookUrl).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, d.length); i++) sb.append(String.format("%02x", d[i]));
            h = sb.toString();
        } catch (Throwable t) { h = "book"; }
        java.io.File dir = new java.io.File(host.dataDir(), "audio/" + h);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 单次请求最多等多久、一整章总共给多久（慢源不再把人拖死） */
    private static final int STEP_MS = 10000;
    private static final int BUDGET_MS = 22000;

    public String nbFetchChapterText(JSONObject src, String url) {
        return nbFetchChapterText(src, url, null);
    }

    /** 当前在读那本书用的源对象，整本导出要用它取正文 */
    public JSONObject currentSrc() {
        if (nbSourceIdx < 0 || nbSources == null || nbSourceIdx >= nbSources.size()) return null;
        return nbSources.get(nbSourceIdx);
    }

    /**
     * 取章正文，对齐 Legado 的 BookContent：
     * content 规则取正文 → nextContentUrl 抓正文分页并拼接 → replaceRegex 净化（按行 trim → 替换 → 补缩进）。
     * nextChapterUrl 传下一章地址，翻页翻到下一章就停，免得把下一章读进来。
     */
    public String nbFetchChapterText(JSONObject src, String inUrl, String nextChapterUrl) {
        try {
            JSONObject rc = rules.sub(src, "ruleContent");
            String base = rules.ruleStr(src, "bookSourceUrl");
            if (rc == null || inUrl == null || inUrl.isEmpty()) return "";
            String cRule = rules.ruleStr(rc, "content");
            if (cRule.isEmpty()) cRule = rules.ruleStr(rc, "contentRule");
            if (cRule.isEmpty()) return "";
            String nxRule = rules.ruleStr(rc, "nextContentUrl");
            if (nxRule.isEmpty()) nxRule = rules.ruleStr(rc, "nextPage");
            String nextChap = nextChapterUrl == null ? "" : absUrl(inUrl, nextChapterUrl);

            StringBuilder all = new StringBuilder();
            java.util.Set<String> seen = new java.util.HashSet<>();
            String cur = inUrl;
            nbLastFetchErr = "";
            long t0 = System.currentTimeMillis();
            String html = reqT(cur, base, STEP_MS);
            if (html == null) {
                // 慢源常被掐半截：带上 retry 再要一次（不超过总预算才试）
                html = reqT(cur + ",{\"retry\":1}", base, STEP_MS);
            }
            if (html == null) {
                String js = host.lastJsError();
                nbLastFetchErr = js == null || js.isEmpty()
                        ? "请求失败，可能被墙或被拦"
                        : "书源的脚本没跑通（" + js + "）";
                return "";
            }
            int pages = 0;
            while (html != null && pages < 3) {
                List<String> vals = rules.ruleAll(cRule, base, html);
                StringBuilder seg = new StringBuilder();
                for (String v : vals) {
                    if (v == null) continue;
                    String one = v;
                    if (one.contains("<")) one = org.jsoup.Jsoup.parse(one).text();
                    if (one.trim().isEmpty()) continue;
                    seg.append(one.trim()).append("\n");
                }
                if (seg.length() == 0) {
                    seg = contentFallback(html, base);
                    if (seg.length() > 0) host.log("听书", "正文规则没命中，走兜底解析 " + cur);
                }
                if (seg.length() == 0) break;
                all.append(seg);
                pages++;
                if (nxRule.isEmpty()) break;
                String nx = rules.ruleFirst(nxRule, base, html);
                if (nx == null || nx.trim().isEmpty()) break;
                String abs = absUrl(cur, nx.trim());
                if (abs.isEmpty()) break;
                if (!nextChap.isEmpty() && abs.equals(nextChap)) break;
                if (!seen.add(abs)) break;
                if (System.currentTimeMillis() - t0 > BUDGET_MS) {
                    host.log("听书", "正文超预算，停在第 " + pages + " 页 " + inUrl);
                    break;
                }
                cur = abs;
                html = reqT(abs, base, STEP_MS);
            }
            if (pages == 0) {
                // 正文取空是最常被误判成「被墙」的一类。把页面形态和规则形态一起写出来，
                // 下次对照日志就能分清是站点没给内容、还是规则形态跟页面对不上。
                nbLastFetchErr = "正文规则 " + cRule + " 没取到内容";
                String bk = RuleEngine.bodyKind(html);
                String rk = RuleEngine.ruleKind(cRule);
                host.log("听书", "正文没取到｜页面=" + bk + " " + (html == null ? 0 : html.length()) + "字"
                        + "｜规则形态=" + rk
                        + "｜规则=" + (cRule.length() > 60 ? cRule.substring(0, 60) + "…" : cRule)
                        + "｜开头「" + RuleEngine.headOf(html, 20) + "」"
                        + (("json".equals(rk) && "HTML".equals(bk)) ? "｜错配：规则要 JSON、响应是 HTML" : ""));
                return "";
            }
            if (pages > 1) host.log("听书", "正文分页 " + pages + " 页 " + inUrl);
            String text = tidyLines(all.toString());
            String rep = rules.ruleStr(rc, "replaceRegex");
            if (!rep.isEmpty()) {
                text = tidyLines(rules.applyReplace(rep, text));
                text = indentLines(text);
                host.log("听书", "正文净化 规则=" + rep + " 字数=" + text.length());
            }
            return text;
        } catch (Exception e) {
            host.log("听书", "取正文异常：" + e.getMessage());
            return "";
        }
    }

    /**
     * 规则取不到时的多路兜底：常见正文容器 → 长段落 → 页面够短才整页取。
     * 顺序照抄「先准后粗」，宁可少抓也别把导航、推荐全念出来。
     */
    private StringBuilder contentFallback(String html, String base) {
        StringBuilder sb = new StringBuilder();
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html, base);
            String[] sel = {"#content", "#chaptercontent", "#chapter-content", ".content",
                    ".chapter-content", ".read-content", ".article-content", "article"};
            for (String one : sel) {
                for (org.jsoup.nodes.Element el : doc.select(one)) {
                    String t = el.text().trim();
                    if (t.length() >= 120) return new StringBuilder(t).append("\n");
                }
            }
            for (org.jsoup.nodes.Element pe : doc.select("p")) {
                String t = pe.text().trim();
                if (t.length() >= 10 && !t.contains("上一页") && !t.contains("下一页")) sb.append(t).append("\n");
            }
            if (sb.length() >= 120) return sb;
            String whole = doc.text().trim();
            if (whole.length() >= 120 && whole.length() <= 4000) return new StringBuilder(whole).append("\n");
        } catch (Throwable ignored) {}
        return sb;
    }

    /** 去空行、每行去首尾空白 */
    private static String tidyLines(String t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String ln : t.split("\n")) {
            String s = ln.trim();
            if (s.isEmpty()) continue;
            sb.append(s).append("\n");
        }
        return sb.toString();
    }

    /** 每行补两个全角空格（Legado 的排版习惯，读屏当作句首停顿） */
    private static String indentLines(String t) {
        if (t == null || t.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String ln : t.split("\n")) {
            String s = ln.trim();
            if (s.isEmpty()) continue;
            sb.append("\u3000\u3000").append(s).append("\n");
        }
        return sb.toString();
    }

    public void nbCacheAll() {
        if (nbChapters.isEmpty()) { host.speak("先把目录打开"); host.toast("没有目录"); return; }
        final List<String[]> chs = new ArrayList<>(nbChapters);
        final String name = nbBookName == null || nbBookName.trim().isEmpty() ? "网络书" : nbBookName.trim();
        final int si = nbSourceIdx;
        host.speak("开始缓存 " + chs.size() + " 章，请稍等");
        com.mangjie.reader.tools.TaskProgress.start("缓存《" + name + "》", chs.size());
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            int ok = 0;
            // 每 10% 报一次，最少 20 章报一次，免得一万章的书从头报到尾
            final int step = Math.max(20, chs.size() / 10);
            StringBuilder all = new StringBuilder();
            JSONObject src = (si >= 0 && si < nbSources.size()) ? nbSources.get(si) : null;
            for (int i = 0; i < chs.size(); i++) {
                String[] ch = chs.get(i);
                String body = src == null ? "" : nbFetchChapterText(src, ch[1]);
                if (body.trim().isEmpty()) continue;
                all.append(ch[0]).append("\n");
                for (String line : body.split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) all.append("　　").append(t).append("\n");
                }
                all.append("\n");
                ok++;
                if (ok % 10 == 0) {
                    final int done = ok;
                    ui.post(new Runnable() { @Override public void run() {
                        host.log("缓存", "已缓存 " + done + "/" + chs.size());
                    } });
                }
                if (ok % step == 0) {
                    final int doneNow = ok;
                    com.mangjie.reader.tools.TaskProgress.update(doneNow);
                    ui.post(new Runnable() { @Override public void run() {
                        host.speakQueued("缓存中，已完成 " + doneNow + "，共 " + chs.size());
                    } });
                }
                if (all.length() > 8000000) break;
            }
            String path = "";
            try {
                File dir = new File(host.dataDir(), "网上缓存");
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, host.safeName(name) + ".txt");
                java.io.FileWriter fw = new java.io.FileWriter(out, false);
                fw.write(all.toString());
                fw.close();
                path = out.getAbsolutePath();
            } catch (Exception e) {
                host.log("缓存", "写文件失败：" + e.getMessage());
            }
            com.mangjie.reader.tools.TaskProgress.finish();
            final int okN = ok;
            final String pth = path;
            ui.post(new Runnable() { @Override public void run() {
                if (pth.isEmpty()) { host.speak("缓存失败"); host.toast("缓存失败"); return; }
                host.log("缓存", "整本缓存完成 章=" + okN + "/" + chs.size() + " 路径=" + pth);
                host.speak("已缓存 " + okN + " 章，存到网上缓存目录，本地阅读能打开");
                host.toast("已缓存 " + okN + " 章");
                prefs.edit().putString("lastCachePath", pth).apply();
            } });
        } });
    }

    public void nbCacheChapter() {
        if (nbChapters.isEmpty()) { host.speak("先把目录打开"); return; }
        int idx = -1;
        String t = host.top() == null ? "" : host.top().title;
        if (t != null && t.startsWith(nbBookName + "　")) {
            String cn = t.substring((nbBookName + "　").length());
            for (int k = 0; k < nbChapters.size(); k++) {
                if (nbChapters.get(k)[0].equals(cn)) { idx = k; break; }
            }
        }
        if (idx < 0) idx = 0;
        final String[] ch = nbChapters.get(idx);
        final JSONObject src = (nbSourceIdx >= 0 && nbSourceIdx < nbSources.size()) ? nbSources.get(nbSourceIdx) : null;
        final String name = nbBookName == null || nbBookName.trim().isEmpty() ? "网络书" : nbBookName.trim();
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            String body = src == null ? "" : nbFetchChapterText(src, ch[1]);
            final String b = body;
            ui.post(new Runnable() { @Override public void run() {
                if (b.trim().isEmpty()) { host.speak("正文没取到"); return; }
                try {
                    File dir = new File(host.dataDir(), "网上缓存");
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, host.safeName(name) + ".txt");
                    java.io.FileWriter fw = new java.io.FileWriter(out, true);
                    fw.write(ch[0] + "\n");
                    for (String line : b.split("\n")) {
                        String x = line.trim();
                        if (!x.isEmpty()) fw.write("　　" + x + "\n");
                    }
                    fw.write("\n");
                    fw.close();
                    host.speak("已缓存 " + ch[0]);
                    host.toast("已缓存这一章");
                } catch (Exception e) {
                    host.speak("缓存失败");
                }
            } });
        } });
    }

    public void nbOpenChapter(final int idx, final boolean autoRead) {
        if (idx < 0 || idx >= nbChapters.size()) return;
        final String[] ch = nbChapters.get(idx);
        nbLastChapter = ch[0];
        if (nbBookUrl != null && !nbBookUrl.isEmpty())
            prefs.edit().putString("nbLastCh_" + nbBookUrl, ch[0]).apply();
        host.speak("正在加载 " + ch[0]);
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                // 先看磁盘缓存（对齐 Legado 的 BookHelp.getContent：有文件就直接读，没有再取网络）
                String text = ChapterCache.read(host.dataDir(), nbBookUrl, idx, ch[0]);
                if (text == null) text = nbTextCache.get(ch[1]);
                if (text == null) {
                    String nextChUrl = (idx + 1 < nbChapters.size()) ? nbChapters.get(idx + 1)[1] : null;
                    text = nbFetchChapterText(nbSources.get(nbSourceIdx), ch[1], nextChUrl);
                    if (text != null && !text.trim().isEmpty()) {
                        nbTextCache.put(ch[1], text);
                        ChapterCache.write(host.dataDir(), nbBookUrl, idx, ch[0], text);   // 取到就落盘
                    }
                }
                // 预取下一章：读这一章的时间足够把下一章抓好
                if (idx + 1 < nbChapters.size()) {
                    String nurl = nbChapters.get(idx + 1)[1];
                    if (!nbTextCache.containsKey(nurl) && !nurl.equals(nbNextPrefetchUrl)) {
                        nbNextPrefetchUrl = nurl;
                        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
                            try {
                                String nn = (idx + 2 < nbChapters.size()) ? nbChapters.get(idx + 2)[1] : null;
                                String nt = nbFetchChapterText(nbSources.get(nbSourceIdx), nurl, nn);
                                if (nt != null && !nt.trim().isEmpty()) {
                                    synchronized (nbTextCache) { nbTextCache.put(nurl, nt); }
                                    ChapterCache.write(host.dataDir(), nbBookUrl, idx + 1, nbChapters.get(idx + 1)[0], nt);
                                    host.log("预取", "下一章已就绪并落盘 " + nurl);
                                }
                            } catch (Throwable ignored) {}
                        } });
                    }
                }
                final String body = text;
                ui.post(new Runnable() { @Override public void run() {
                    if (body.trim().isEmpty()) {
                        // 报错分类型（对齐 Legado 的 ContentEmptyException 一类一类提示）
                        String why = nbLastFetchErr;
                        String msg = why == null || why.isEmpty()
                                ? "正文是空的：书源规则失效或被拦了，建议换源"
                                : ("没取到正文：" + why + "，可以试试换源");
                        host.speak(msg);
                        host.toast("正文没取到");
                        return;
                    }
                    // 正文交给阅读页：按句切、逐句朗读、有进度和书签（以前这里按行铺 60 行，读长章节摸不到后面）
                    nbReaderText = body;
                    host.openNetChapter(ch[0], body, nbBookUrl + "#" + idx, autoRead);
                } });
            }
        });
    }

    /** 最近读过的网书（多本，缓存对账用；prefs 的 nbRecent 只留最近这一条） */
    private void noteRecentBook(String name, String url, String sourceIdx) {
        if (url == null || url.isEmpty()) return;
        String store = prefs.getString("nbRecentList", "");
        StringBuilder sb = new StringBuilder((name == null ? "" : name) + "\u0001" + url + "\u0001" + sourceIdx);
        for (String line : store.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] q = t.split("\u0001");
            if (q.length >= 2 && q[1].equals(url)) continue;
            sb.append("\n").append(t);
        }
        String[] lines = sb.toString().split("\n");
        StringBuilder cut = new StringBuilder();
        for (int i = 0; i < lines.length && i < 30; i++) {
            if (cut.length() > 0) cut.append("\n");
            cut.append(lines[i]);
        }
        prefs.edit().putString("nbRecentList", cut.toString()).apply();
    }

    /** 清掉内存里缓存的章节正文（清磁盘缓存时一并清，免得又读回旧的） */
    public void nbClearTextCache() {
        try { nbTextCache.clear(); } catch (Throwable ignored) {}
    }

    public int nbLastChapterIdx() {
        if (nbLastChapter == null || nbLastChapter.isEmpty()) return 0;
        for (int k = 0; k < nbChapters.size(); k++) if (nbChapters.get(k)[0].equals(nbLastChapter)) return k;
        return 0;
    }

    public void nbSpeakChapterChain(String text, int idx) {
        String spoken = com.mangjie.reader.reader.SpeakClean.clean(text);
        if (spoken != null && !spoken.isEmpty()) text = spoken;
        List<String> chunks = host.chunkForTts(text, 400);
        if (chunks.isEmpty()) return;
        nbChainChapterIdx = idx;
        nbChainChunks = chunks;
        nbChainAt = 0;
        nbChainName = nbBookName;
        nbChainSpeakNext();
        host.log("连播", "第 " + (idx + 1) + " 章开始，共 " + chunks.size() + " 块");
    }

    public void nbChainSpeakNext() {
        if (nbChainChunks == null) return;
        if (nbChainAt >= nbChainChunks.size()) {
            int next = nbChainChapterIdx + 1;
            nbChainChunks = null;
            if (next >= nbChapters.size()) { host.speak("整本念完了"); nbChainChapterIdx = -1; return; }
            host.speakQueued("下一章：" + nbChapters.get(next)[0]);
            nbOpenChapter(next, true);
            return;
        }
        String one = nbChainChunks.get(nbChainAt++);
        if (!host.chainSpeak(one)) nbChainChunks = null;
    }

    public void nbChainStop() {
        nbChainChunks = null;
        nbChainChapterIdx = -1;
    }

    /** 相对地址补成绝对（给校验器拼章节地址用） */
    public String absoluteUrl(String baseUrl, String u) { return absUrl(baseUrl, u); }

    /** 给同包的校验器等外部件用 */
    public Host hostOf() { return host; }
    public RuleEngine rulesOf() { return rules; }

    /** 启用发现：对齐 Legado 的 enabledExplore（缺省 true） */
    public boolean exploreOn(JSONObject src) {
        return src.optBoolean("enabledExplore", true);
    }

    /** 有发现页：对齐 Legado 的 hasExploreUrl（exploreUrl 非空） */
    public boolean hasExploreUrl(JSONObject src) {
        return !exploreUrlOf(src).isEmpty();
    }

    /**
     * 取一个源的发现地址。必须走 rules.ruleStr，不能用 optString：
     * 源里的 exploreUrl 常常是 JSON 数组，optString 会把数组读成空串，误判成没有发现页。
     * 顶层没写的，退回 ruleExplore 里的 discoverUrl / exploreUrl。
     */
    private String exploreUrlOf(JSONObject src) {
        if (src == null) return "";
        String ex = rules.ruleStr(src, "exploreUrl").trim();
        if (!ex.isEmpty()) return ex;
        JSONObject re = rules.sub(src, "ruleExplore");
        if (re != null) {
            String d = rules.ruleStr(re, "discoverUrl").trim();
            if (d.isEmpty()) d = rules.ruleStr(re, "exploreUrl").trim();
            if (!d.isEmpty()) return d;
        }
        return "";
    }

    /** 发现页的分组筛选（存在 prefs，页面菜单改） */
    private String exGroupOf() {
        try {
            return prefs.getString("exGroup", "");
        } catch (Throwable e) { return ""; }
    }

    private String exKeyOf() {
        try {
            return prefs.getString("exKey", "");
        } catch (Throwable e) { return ""; }
    }

    /**
     * 发现页的「分组」：取书源的管理分组 bookSourceGroup，不是源的分类标题。
     * 对齐 Legado 的 flowExploreGroupsUnProcessed：启用、有 exploreUrl、分组非空的源才算。
     */
    public List<String> exploreGroupNames() {
        loadBookSources();
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        for (int si = 0; si < nbSources.size(); si++) {
            JSONObject src = nbSources.get(si);
            bindSrc(src);
            if (!exploreOn(src) || !hasExploreUrl(src) || srcType(src) != 0) continue;
            String g = src.optString("bookSourceGroup", "").trim();
            for (String one : g.split("[,，]")) {
                String t = one.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return new ArrayList<String>(out);
    }

    /** 这个源是否属于某管理分组（逗号分隔，任一命中即算） */
    private boolean srcInGroup(JSONObject src, String group) {
        String g = src.optString("bookSourceGroup", "").trim();
        for (String one : g.split("[,，]")) if (one.trim().equals(group)) return true;
        return false;
    }

    /** 分类表 JS 求值结果的进程内缓存（键 = 源序号 + 规则串指纹），避免每点一次都重跑 JS */
    private final java.util.Map<String, String> exJsCache = new java.util.HashMap<String, String>();

    /** 最近一次分类表求值失败的原因；空串表示没失败 */
    private volatile String lastExError = "";

    public List<Object[]> nbGroupsOf(JSONObject src, int si) {
        List<Object[]> groups = new ArrayList<>();
        lastExError = "";
        bindSrc(src);
        String ex = exploreUrlOf(src).trim();
        if (ex.isEmpty()) return groups;
        // 对齐 Legado 的 exploreKinds()：exploreUrl 允许写 @js: / <js>，先求值再解析。
        // 以前不求值，整段 JS 就被当成"一个分类的地址"，于是这类源点开永远只有一个假分类「发现」，
        // 展开进去也只有一个同名条目，等于展不开。
        String low = ex.toLowerCase();
        if (low.startsWith("@js:") || low.startsWith("<js>")) {
            String ck = si + "|" + ex.hashCode();
            String cached = exJsCache.get(ck);
            if (cached != null) {
                ex = cached;
            } else {
                String v = rules.runJsPublic(ex, "");
                v = v == null ? "" : v.trim();
                if (v.equals("null") || v.equals("undefined")) v = "";
                exJsCache.put(ck, v);
                if (v.isEmpty()) lastExError = "JS 求值为空，具体报错见上面那条「JS 失败」";
                host.log("发现", v.isEmpty()
                        ? "分类表 JS 求值为空（看上面那条「JS 失败」的报错：缺 jsLib 函数、ES6 写法，或脚本里自己发网络请求失败）"
                        : "分类表 JS 求值：" + (v.length() > 400 ? v.substring(0, 400) + "…" : v));
                ex = v;
            }
        }
        if (ex.isEmpty()) return groups;
        if (!ex.startsWith("[")) {
            // 文本形态：分类名::地址，多个用 && 或换行分隔（Legado 同一个口径）
            List<String[]> one = new ArrayList<>();
            for (String seg : ex.split("(&&|\\n)+")) {
                String t = seg.trim();
                if (t.isEmpty()) continue;
                int p = t.indexOf("::");
                String title = p > 0 ? t.substring(0, p).trim() : t;
                String url = p > 0 ? t.substring(p + 2).trim() : "";
                one.add(new String[]{title.isEmpty() ? "发现" : title, url, String.valueOf(si)});
            }
            if (one.isEmpty()) return groups;
            groups.add(new Object[]{"发现", one});
            return groups;
        }
        String curGroup = "发现";
        List<String[]> cur = new ArrayList<>();
        int skippedWidget = 0;
        // 先用标准 JSONArray 解析（action 里带 \" 转义的字符串，手写切分容易切错），失败再退回轻量切分
        java.util.List<JSONObject> rows = new ArrayList<JSONObject>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(ex);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) rows.add(o);
            }
        } catch (Throwable notArray) {
            for (String objStr : rules.splitJsonObjectsLite(ex)) {
                try { rows.add(new JSONObject(objStr)); } catch (Throwable ignored) { }
            }
        }
        for (JSONObject o : rows) {
            try {
                String title = o.optString("title", "").trim();
                String url = o.optString("url", "").trim();
                // 对齐 Legado 的 ExploreKind.type：默认 url 才是分类链接；
                // text/select/button 是发现页上的输入框、下拉、按钮，我们这套纯列表界面演不了，跳过但记账
                String type = o.optString("type", "url").trim();
                boolean cat = "url".equalsIgnoreCase(type) && !url.isEmpty();
                if (cat) {
                    cur.add(new String[]{title.isEmpty() ? curGroup : title, url, String.valueOf(si)});
                } else if (url.isEmpty() && (type.isEmpty() || "url".equalsIgnoreCase(type))) {
                    if (!title.isEmpty()) {                        // 无地址的纯标题当分组名（沿用老约定）
                        if (!cur.isEmpty()) { groups.add(new Object[]{curGroup, cur}); cur = new ArrayList<>(); }
                        curGroup = title;
                    }
                } else {
                    skippedWidget++;
                }
            } catch (Exception ignored) { }
        }
        if (skippedWidget > 0)
            host.log("发现", "分类表里有 " + skippedWidget + " 个控件项（输入框/下拉/按钮），列表界面演不了，已跳过");
        if (!cur.isEmpty()) groups.add(new Object[]{curGroup, cur});
        return groups;
    }

    public void nbExplore() {
        loadBookSources();
        if (nbSources.isEmpty()) { host.speak("还没有书源，先到设置里导入"); host.toast("还没有书源"); return; }
        List<Item> items = new ArrayList<>();
        int n = 0;
        int withCat = 0;
        // 发现页只做「看源」这一件事：按分组筛、按源名筛，其余操作走每行的行菜单（对齐 Legado）
        String onlyGroup = exGroupOf();
        String onlyKey = exKeyOf();
        // 旧版存下的筛选可能已经失效（比如以前按分类标题「◆ 都市 ◆」筛的），自动清掉，别让人对着空页发懵
        if (!onlyGroup.isEmpty() && !exploreGroupNames().contains(onlyGroup)) {
            host.log("发现", "分组筛选「" + onlyGroup + "」已经不是任何源的分组，自动清除");
            try { prefs.edit().remove("exGroup").apply(); } catch (Throwable ignored) { }
            onlyGroup = "";
        }
        int offEx = 0, noUrl = 0;
        for (int si = 0; si < nbSources.size(); si++) {
            JSONObject src = nbSources.get(si);
            bindSrc(src);
            // 有没有发现页按规则判：启用发现 + exploreUrl 非空；分类等点开才知道，不预先算
            if (!exploreOn(src)) { offEx++; continue; }
            if (!hasExploreUrl(src)) { noUrl++; continue; }
            // 对齐 Legado：音频/图片/文件源同样出现在发现页，只是点进正文时读不了。
            // 以前整类挡掉，94 个源直接消失
            String nm = rules.ruleStr(src, "bookSourceName").trim();
            if (nm.isEmpty()) nm = "书源 " + si;
            if (!onlyKey.isEmpty()
                    && !nm.contains(onlyKey)
                    && !src.optString("bookSourceGroup", "").contains(onlyKey)) continue;
            if (!onlyGroup.isEmpty() && !srcInGroup(src, onlyGroup)) continue;
            items.add(new Item(srcNeedsWeb(src) ? nm + "（需浏览器）" : nm, "nb:esrc:" + si));
            withCat++;
            n++;
        }
        if (items.isEmpty()) {
            // 空页也推出来：这样还能用页面菜单改筛选、看这一行为什么空
            String why = nbSources.isEmpty() ? "还没导入书源"
                    : "（没有源满足：启用发现＋有 exploreUrl。关闭发现 " + offEx
                      + " 个，缺 exploreUrl " + noUrl + " 个）";
            if (!onlyGroup.isEmpty() || !onlyKey.isEmpty()) {
                why = "（筛选条件：分组 " + (onlyGroup.isEmpty() ? "不限" : onlyGroup)
                        + "，关键词 " + (onlyKey.isEmpty() ? "不限" : onlyKey)
                        + "，没有源匹配。点这一行清除筛选）";
            }
            items.add(new Item(why, "nb:expclr"));
            host.pushScreen(new Screen("发现 · 书源", items).k("bsrcExplore"));
            host.log("发现", why + "，源 " + nbSources.size());
            host.speak("发现页是空的，" + why);
            return;
        }
        // 不再挂「聚合发现（各源第一个分类一起出）」：那是 369 个源各拉第一个分类拼一屏，
        // 出来是几百条没有顺序的混合列表，从头听到尾也听不到重点，一次点击还要发 388 个请求。
        // 现在有「搜索书源」可以直接定位一个源再进它的分类，这条入口已经没有价值。
        // nbExploreAll 方法保留，等清理冗余时一起处理。
        host.pushScreen(new Screen(onlyGroup.isEmpty() ? "发现 · 书源" : "发现 · 书源（" + onlyGroup + "）", items)
                .k("bsrcExplore"));
        host.log("发现", "源 " + nbSources.size() + "，关闭发现 " + offEx + "，没有 exploreUrl " + noUrl
                + "，列进发现页 " + n + (onlyGroup.isEmpty() ? "" : "，分组=" + onlyGroup)
                + (onlyKey.isEmpty() ? "" : "，关键词=" + onlyKey));
        host.speak("发现，共 " + n + " 个源有发现页");
    }

    /** 源里写了 webView（URL 模板的 UrlOption 字段）：纯 HTTP 抓不到内容，界面上要直接标出来 */
    private boolean srcNeedsWeb(JSONObject src) {
        String a = rules.ruleStr(src, "bookSourceUrl");
        String b = exploreUrlOf(src);
        return (a != null && a.contains("webView")) || (b != null && b.contains("webView"));
    }

    public List<String[]> nbFetchExploreList(JSONObject src, int si, String catUrl) {
        List<String[]> found = new ArrayList<>();
        try {
            JSONObject re = rules.sub(src, "ruleExplore");
            if (re == null) re = new JSONObject();
            String base = rules.ruleStr(src, "bookSourceUrl");
            String sName = src.optString("bookSourceName", "源" + (si + 1));
            jsPage = 1;
            host.setJsPage(1);
            jsKey = "";
            String u = rules.pageAwareUrl(catUrl, 1);
            if (u.contains("@js:") || u.contains("{{")) {
                String u2 = rules.ruleFirst(u, base, "");
                if (!u2.isEmpty()) u = u2;
            }
            String html = req(u, base);
            if (html == null) return found;
            String listRule = rules.ruleStr(re, "bookList");
            if (listRule.isEmpty()) listRule = rules.ruleStr(src, "ruleExplore.bookList");
            if (listRule.startsWith("$") && rules.looksJson(html)) {
                Object root = html.trim().startsWith("[") ? (Object) new org.json.JSONArray(html) : (Object) new JSONObject(html);
                List<Object> its = rules.jsonObjects(root, listRule);
                for (Object it : its) {
                    if (found.size() >= 20) break;
                    String nm = rules.jsonItemValue(rules.ruleStr(re, "name"), it);
                    if (nm == null || nm.trim().isEmpty()) continue;
                    String au = rules.jsonItemValue(rules.ruleStr(re, "author"), it);
                    String uu = rules.jsonItemValue(rules.ruleStr(re, "bookUrl"), it);
                    found.add(new String[]{nm.trim(), au == null ? "" : au.trim(),
                            uu == null ? "" : uu.trim(), String.valueOf(si), sName});
                }
                return found;
            }
            List<org.jsoup.nodes.Element> els = listRule.isEmpty() ? new ArrayList<org.jsoup.nodes.Element>()
                    : rules.defaultChain(rules.stripLeaf(listRule), base, html);
            for (org.jsoup.nodes.Element e : els) {
                if (found.size() >= 20) break;
                String nm = rules.pickElem(rules.ruleStr(re, "name"), e, base);
                if (nm == null || nm.trim().isEmpty()) continue;
                String au = rules.pickElem(rules.ruleStr(re, "author"), e, base);
                String uu2 = rules.pickElem(rules.ruleStr(re, "bookUrl"), e, base);
                found.add(new String[]{nm.trim(), au == null ? "" : au.trim(),
                        uu2 == null ? "" : uu2.trim(), String.valueOf(si), sName});
            }
        } catch (Exception e) {
            host.log("发现聚合", "源" + si + " " + e.getMessage());
        }
        return found;
    }

    public void nbExploreAll() {
        loadBookSources();
        if (nbSources.isEmpty()) { host.speak("还没有书源"); return; }
        final List<int[]> cands = new ArrayList<>();
        for (int si = 0; si < nbSources.size(); si++) {
            if (!srcSearchOk(si)) continue;
            List<Object[]> gs = nbGroupsOf(nbSources.get(si), si);
            if (gs.isEmpty()) continue;
            Object[] g0 = gs.get(0);
            List<?> cats = (List<?>) g0[1];
            if (cats.isEmpty()) continue;
            Object c0 = cats.get(0);
            if (!(c0 instanceof String[])) continue;
            cands.add(new int[]{si});
        }
        if (cands.isEmpty()) { host.speak("没有能用的发现分类"); return; }
        host.speak("正在聚合 " + cands.size() + " 个书源的发现");
        final List<Object[]> spec = new ArrayList<>();
        for (int[] c : cands) {
            int si = c[0];
            List<Object[]> gs = nbGroupsOf(nbSources.get(si), si);
            Object[] g0 = gs.get(0);
            List<?> cats = (List<?>) g0[1];
            String[] cat = (String[]) cats.get(0);
            spec.add(new Object[]{si, cat[1]});
        }
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            final List<List<String[]>> parts = java.util.Collections.synchronizedList(new ArrayList<List<String[]>>());
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
            for (Object[] one : spec) {
                final int si = (Integer) one[0];
                final String url = (String) one[1];
                pool.submit(new Runnable() { @Override public void run() {
                    List<String[]> l = nbFetchExploreList(nbSources.get(si), si, url);
                    if (!l.isEmpty()) parts.add(l);
                } });
            }
            pool.shutdown();
            try { pool.awaitTermination(25, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) {}
            final List<String[]> all = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (List<String[]> one : parts) {
                for (String[] b : one) {
                    if (!seen.add(b[0] + "\u0001" + b[2])) continue;
                    all.add(b);
                }
            }
            ui.post(new Runnable() { @Override public void run() {
                nbResults = all;
                List<Item> items = new ArrayList<>();
                for (int i = 0; i < all.size(); i++) items.add(new Item(nbResultLabel(all.get(i)), "nb:book:" + i));
                if (items.isEmpty()) items.add(new Item("（各源都没取到书）"));
                stack.add(new Screen("聚合发现", items));
                host.render(true);
                host.speak(all.isEmpty() ? "没取到书" : ("聚合到 " + all.size() + " 本，带来源"));
                host.log("发现聚合", "书源 " + spec.size() + " 个，结果 " + all.size());
            } });
        } });
    }

    public void nbExploreSrc(final int si) {
        host.log("听书", "发现 打开源 si=" + si + "/" + nbSources.size());
        if (si < 0 || si >= nbSources.size()) { host.log("听书", "发现 源序号越界"); return; }
        final JSONObject src = nbSources.get(si);
        final String nm = rules.ruleStr(src, "bookSourceName");
        nbExploreSi = si;
        host.speak("正在读分组");
        // 分类表经常要联网（JSON.parse(java.ajax(tagUrl)) 这种写法），不能放主线程：
        // 主线程一发网络请求就被 Android 掐掉（NetworkOnMainThreadException），异常又被吞成空串，
        // 结果每个这种源都显示"没有发现页"。日志证据：拷贝轻说 8 毫秒就报 SyntaxError: Empty JSON string
        new Thread(new Runnable() { @Override public void run() {
            final List<Object[]> gs;
            final String[] why = new String[1];
            try {
                gs = nbGroupsOf(src, si);
                why[0] = lastExError;
            } catch (Throwable t) {
                ui.post(new Runnable() { @Override public void run() {
                    host.log("听书", "发现 分组计算异常：" + t);
                    host.speak("读分组出错了");
                } });
                return;
            }
            ui.post(new Runnable() { @Override public void run() {
                nbGroups = gs;
                if (gs.isEmpty()) {
                    if (why[0] != null && !why[0].isEmpty()) {
                        host.log("听书", "发现 源=" + nm + " 分类表算不出来：" + why[0]);
                        host.speak("这个书源的分类表算不出来，原因见日志");
                        host.toast("分类表算不出来");
                    } else {
                        host.log("听书", "发现 源=" + nm + " 没有发现规则");
                        host.speak("这个书源没有发现页"); host.toast("这个书源没有发现");
                    }
                    return;
                }
                List<Item> items = new ArrayList<>();
                for (int i = 0; i < gs.size(); i++) {
                    Object[] g = gs.get(i);
                    List<?> cats = (List<?>) g[1];
                    items.add(new Item(g[0] + "（" + cats.size() + " 个分类）", "nb:egrp:" + i));
                }
                host.pushScreen(new Screen(nm + " · 分组", items));
                host.speak(nm + "，共 " + gs.size() + " 个分组");
            } });
        } }).start();
    }

    public void nbExploreGroup(int gi) {
        if (gi < 0 || gi >= nbGroups.size()) { host.log("听书", "发现 分组序号越界 " + gi); return; }
        List<String[]> cats = (List<String[]>) nbGroups.get(gi)[1];
        nbCats = cats;
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < cats.size(); i++) {
            String t = cats.get(i)[0];
            items.add(new Item(t.isEmpty() ? "未命名分类" : t, "nb:ecat:" + i));
        }
        if (items.isEmpty()) { host.speak("这个分组没有分类"); return; }
        host.pushScreen(new Screen((String) nbGroups.get(gi)[0], items));
        host.speak("共 " + cats.size() + " 个分类");
    }

    // ---- 分类列表分页 ----
    // 源里按 {{page}} / {{(page-1)*10}} 分页，一页通常 10~20 本；以前只抓第 1 页，
    // 所以「西方奇幻榜」永远只有 10 本，往下滑也没得看。Legado 是滚到底自动取下一页。
    private int nbCatPage = 1;
    /** 上一页实际发出去的地址，用来识别"分页参数没生效"（返回同一页） */
    private String nbCatLastUrl = "";
    /** 最近一次目录没取到的原因（在哪一步断的），播报时一并说出来 */
    private volatile String lastTocWhy = "";
    private int nbCatSi = -1;
    private String nbCatUrl = "";
    private String nbCatTitle = "";
    private volatile boolean nbCatLoading = false;
    private final List<String[]> nbCatAll = new ArrayList<String[]>();

    /** 已确认"翻页参数没生效"：再滑也不发请求，避免白等 */
    private boolean nbCatDead = false;

    /** 打开分类后还剩几次"后台自动续页"。一页十本太短，静默往后取几页再交给人 */
    private int nbAutoPages = 0;

    public void nbOpenCat(int idx) {
        if (idx < 0 || idx >= nbCats.size()) return;
        final String[] cat = nbCats.get(idx);
        int si;
        try { si = Integer.parseInt(cat[2]); } catch (Exception e) { return; }
        nbCatPage = 1;
        nbCatDead = false;
        nbAutoPages = 3;
        nbCatLastUrl = "";
        nbCatSi = si;
        nbCatUrl = cat[1] == null ? "" : cat[1];
        nbCatTitle = cat[0] == null ? "" : cat[0];
        nbCatAll.clear();
        if (si >= 0 && si < nbSources.size() && srcNeedsWeb(nbSources.get(si))) {
            host.speak("这个源要用浏览器打开，我先按网页抓一次，抓不到就是它要 WebView");
        } else {
            host.speak("正在加载 " + nbCatTitle);
        }
        loadCatPage(true);
    }

    /** 滑到列表底部（或点末尾那行）时取下一页 */
    public void nbCatMore() {
        if (nbCatLoading) return;
        if (nbCatDead) { host.toast("这个分类翻不动了：站点的分页参数没起作用，换别的分类试试"); return; }
        if (nbCatSi < 0 || nbCatUrl.isEmpty()) return;
        if (nbCatAll.isEmpty()) { host.toast("还没有书可翻"); return; }
        nbCatPage++;
        host.speak("正在加载第 " + nbCatPage + " 页");
        loadCatPage(false);
    }

    private void loadCatPage(final boolean first) {
        nbCatLoading = true;
        final int si = nbCatSi;
        final int page = nbCatPage;
        final String catUrl = nbCatUrl;
        final String catTitle = nbCatTitle;
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            final List<String[]> found = new ArrayList<>();
            String why = "";
            String u = "";
            int st = 0;
            String err = "";
            String real = "";
            boolean listEmpty = false;
            try {
                JSONObject src = nbSources.get(si);
                bindSrc(src);
                JSONObject re = rules.sub(src, "ruleExplore");
                if (re == null) re = new JSONObject();
                String base = rules.ruleStr(src, "bookSourceUrl");
                jsPage = page;
                host.setJsPage(page);
                // 分类页没有搜索关键词：jsKey 留空，{{key}} 才不会被换成别的东西。
                // （分类表 JS 的缓存键是 si + 规则串指纹，跟这个无关，别混在一起）
                jsKey = "";
                u = rules.pageAwareUrl(catUrl, page);
                if (u.contains("@js:") || u.contains("{{")) {
                    String u2 = rules.ruleFirst(u, base, "");
                    if (!u2.isEmpty()) u = u2;
                }
                Http.Resp rr = reqR(u, base, 0);
                String html = rr.body;
                st = rr.code;
                err = rr.err == null ? "" : rr.err;
                if (html != null) {
                    JSONObject useRule = re;
                    String listRule = rules.ruleStr(re, "bookList");
                    if (listRule.isEmpty()) listRule = rules.ruleStr(src, "ruleExplore.bookList");
                    // 对齐 Legado BookList：发现规则里没写 bookList 时，退回用搜索规则
                    if (listRule.isEmpty()) {
                        JSONObject rs = rules.sub(src, "ruleSearch");
                        String sl = rs == null ? "" : rules.ruleStr(rs, "bookList");
                        if (!sl.isEmpty()) { useRule = rs; listRule = sl; }
                    }
                    if (listRule.isEmpty()) listEmpty = true;
                    boolean reverse = listRule.startsWith("-");
                    if (reverse || listRule.startsWith("+")) listRule = listRule.substring(1).trim();
                    List<Object> sits = listRule.isEmpty() ? new ArrayList<Object>() : rules.listItems(listRule, base, html);
                    for (Object it : sits) {
                        if (found.size() >= 999) break;
                        String nm = rules.fieldOf(rules.ruleStr(useRule, "name"), it, base);
                        if (nm == null || nm.trim().isEmpty()) continue;
                        String au = rules.fieldOf(rules.ruleStr(useRule, "author"), it, base);
                        String uu = rules.fieldOf(rules.ruleStr(useRule, "bookUrl"), it, base);
                        found.add(new String[]{nm.trim(), au == null ? "" : au.trim(), uu == null ? "" : uu.trim(), String.valueOf(si)});
                    }
                    if (reverse) java.util.Collections.reverse(found);
                    if (found.isEmpty()) {
                        List<String> names = rules.ruleAll(rules.ruleStr(useRule, "name"), base, html);
                        List<String> authors = rules.ruleAll(rules.ruleStr(useRule, "author"), base, html);
                        List<String> urls = rules.ruleAll(rules.ruleStr(useRule, "bookUrl"), base, html);
                        for (int i = 0; i < names.size() && found.size() < 999; i++) {
                            String nm = names.get(i).trim();
                            if (nm.isEmpty()) continue;
                            found.add(new String[]{nm, i < authors.size() ? authors.get(i) : "",
                                    i < urls.size() ? urls.get(i) : "", String.valueOf(si)});
                        }
                    }
                }
                if (html == null) {
                    why = pageWhy(u, err, st);
                } else if (found.isEmpty()) {
                    if (listEmpty) {
                        why = page <= 1 ? "书单规则是空的（发现规则和搜索规则里都没写 bookList）"
                                : "这一页没书，而且这源没写书单规则，翻不了页";
                    } else if (page <= 1) {
                        why = "页面拿到了（" + html.length() + " 字节），但书单规则没匹配上书，这个源可能不支持这个分类";
                    } else {
                        why = "这一页没有书了";
                    }
                }
                real = (rr != null && rr.url != null && !rr.url.isEmpty()) ? rr.url : u;
                host.log("发现", "源=" + rules.ruleStr(src, "bookSourceName")
                        + " | 源#=" + si
                        + " | 站点=" + rules.ruleStr(src, "bookSourceUrl")
                        + " | 分类=" + catTitle + " | 页=" + page
                        + " | 地址=" + real + " | 状态=" + st
                        + " | 字节=" + (html == null ? -1 : html.length())
                        + " | 出书=" + found.size()
                        + " | 结论=" + (why.isEmpty() ? "ok" : why));
            } catch (Exception e) {
                host.log("发现", "源#" + si + " | 分类=" + catTitle + " | 页=" + page + " | 异常=" + e.getClass().getSimpleName());
                if (si >= 0 && si < nbSources.size()) {
                    host.log("发现", "出错的那个源：" + rules.ruleStr(nbSources.get(si), "bookSourceName")
                            + " / " + rules.ruleStr(nbSources.get(si), "bookSourceUrl"));
                }
            }
            final String whyF = why;
            final String urlF = real.isEmpty() ? (u == null ? "" : u) : real;
            final List<String[]> fs = found;
            ui.post(new Runnable() { @Override public void run() {
                renderCatPage(first, page, fs, whyF, urlF);
            } });
        } });
    }

    /** 把一次请求的失败翻成人话：八类，对应 Legado 的错误码语义（-1 到 -7） */
    private static String pageWhy(String u, String err, int st) {
        if (u == null || u.trim().isEmpty()) return "地址是空的（URL 模板算出来没有地址）";
        String low = u.trim().toLowerCase(java.util.Locale.ROOT);
        if (!low.startsWith("http://") && !low.startsWith("https://")) {
            String shortu = u.length() > 60 ? u.substring(0, 60) + "…" : u;
            return "地址规则没算出真实网址（现在拿到的是占位串 " + shortu
                    + "），这个源要靠 JS 全流程拼地址，暂不支持";
        }
        if (err != null && err.startsWith("HTTP ")) {
            String t = err.substring(5).trim();
            int c = 0;
            try { c = Integer.parseInt(t); } catch (Exception ignored) { }
            if (c == 202) return "站点返回 202，要人机校验，这一页不给内容";
            if (c == 401 || c == 403) return "站点返回 " + c + "，拒绝访问（要登录或改 header）";
            if (c == 404) return "站点返回 404，这个分类地址不存在";
            if (c == 429) return "站点返回 429，请求太密被限流";
            if (c >= 500) return "站点返回 " + c + "，是站自己出错";
            if (c > 0) return "站点返回 " + c + "，这一页没给内容";
            return "站点返回 " + t + "，这一页没给内容";
        }
        if (err != null && !err.isEmpty()) return err + "，这一页没取到";
        if (st > 0) return "站点返回 " + st + "，这一页没给内容";
        return "页面没取到（超时或被拦），可以再试一次";
    }

    /** 第一页重建列表；后续页只往表尾接，不动滚动位置（读屏焦点留在原处） */
    private void renderCatPage(boolean first, int page, List<String[]> found, String why, String pageUrl) {
        nbCatLoading = false;
        // 番茄会把书名里的字换成私用区码位（屏幕上就是方块），显示前统一还原
        if (found != null) for (String[] r : found) {
            if (r != null && r.length > 0) r[0] = PuaText.fix(r[0]);
            if (r != null && r.length > 1 && r[1] != null) r[1] = PuaText.fix(r[1]);
        }
        if (first) {
            nbCatLastUrl = pageUrl == null ? "" : pageUrl;
            nbCatAll.clear();
            nbCatAll.addAll(found);
            nbResults = nbCatAll;
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < nbCatAll.size(); i++)
                items.add(new Item(nbCatAll.get(i)[0] + (nbCatAll.get(i)[1].isEmpty() ? "" : "　" + nbCatAll.get(i)[1]), "nb:book:" + i));
            if (items.isEmpty()) {
                items.add(new Item("（这个分类没取到书：" + (why.isEmpty() ? "原因不明" : why) + "）"));
            } else {
                items.add(new Item("继续加载（第 2 页）", "nb:catmore"));
            }
            host.pushScreen(new Screen(nbCatTitle.isEmpty() ? "发现" : nbCatTitle, items).k("nbcat"));
            host.speak(nbCatAll.isEmpty()
                    ? ("这个分类没取到书，" + (why.isEmpty() ? "原因不明" : why))
                    : ("先给 " + nbCatAll.size() + " 本，后台接着往后取"));
            return;
        }
        int before = nbCatAll.size();
        for (int k = 0; k < found.size(); k++) {
            String[] b = found.get(k);
            boolean dup = false;
            for (int j = 0; j < nbCatAll.size(); j++) {
                String[] a = nbCatAll.get(j);
                if (!b[2].isEmpty() && a[2].equals(b[2])) { dup = true; break; }
                if (b[2].isEmpty() && a[0].equals(b[0])) { dup = true; break; }
            }
            if (!dup) nbCatAll.add(b);
        }
        nbResults = nbCatAll;
        host.dropTailRow("nb:catmore");
        host.dropTailRow("nb:catload");
        int added = nbCatAll.size() - before;
        // 判"到头"要先看地址变没变：站点分页参数没生效时，第 2 页返回的还是第 1 页，
        // 全重复会被旧判据误报成"没有更多了"——这正是"只加载十本书、滑不下去"的根。
        boolean sameUrl = pageUrl != null && !pageUrl.isEmpty()
                && pageUrl.equals(nbCatLastUrl == null ? "" : nbCatLastUrl);
        if (added > 0) {
            nbCatLastUrl = pageUrl == null ? "" : pageUrl;
            for (int i = before; i < nbCatAll.size(); i++)
                host.appendSearchRow(nbCatAll.get(i)[0] + (nbCatAll.get(i)[1].isEmpty() ? "" : "　" + nbCatAll.get(i)[1]), "nb:book:" + i, null);
            // 趁着用户在听头几本，后台静默往后取，直到攒够 60 本或走到尽头。
            // 自动取的时候一句都不播，免得每页念一声「又加十本」，把正在听的那本打断
            if (!nbCatDead && nbAutoPages > 0 && nbCatAll.size() < 60) {
                nbAutoPages--;
                host.appendSearchRow("还在往后加载…", "nb:catload", null);
                nbCatPage++;
                loadCatPage(false);
                return;
            }
            host.appendSearchRow("继续加载（第 " + (page + 1) + " 页）", "nb:catmore", null);
            host.speak("共 " + nbCatAll.size() + " 本，后面还有，滑到底继续");
        } else if (sameUrl) {
            nbCatDead = true;
            host.appendSearchRow("（第 " + page + " 页和第 1 页是同一个地址：这源的分页参数没起作用）", "nb:catnomore", null);
            host.speak("这源的分页参数没起作用，第 " + page + " 页还是第 1 页，就这些了");
        } else {
            nbCatLastUrl = pageUrl == null ? "" : pageUrl;
            host.appendSearchRow("（没有更多了）", "nb:catnomore", null);
            host.speak("没有更多了，共 " + nbCatAll.size() + " 本");
        }
    }


    public void nbExploreOld() {
        host.speak("正在加载发现页");
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                final List<String[]> found = new ArrayList<>();
                for (int si = 0; si < nbSources.size(); si++) {
                    JSONObject src = nbSources.get(si);
                    bindSrc(src);
                    if (!srcEnabled(src)) continue;
                    try {
                        JSONObject re = rules.sub(src, "ruleExplore");
                        if (re == null) continue;
                        String dUrl = rules.ruleStr(re, "discoverUrl");
                        if (dUrl.isEmpty()) dUrl = rules.ruleStr(src, "exploreUrl");
                        if (dUrl.isEmpty()) continue;
                        String base = rules.ruleStr(src, "bookSourceUrl");
                        if (dUrl.startsWith("//")) dUrl = "https:" + dUrl;
                        else if (dUrl.startsWith("/")) dUrl = base + dUrl;
                        else if (!dUrl.startsWith("http")) dUrl = base + "/" + dUrl;
                        String html = req(dUrl, base);
                        if (html == null) continue;
                        String listRule = rules.ruleStr(re, "bookList");
                        List<String> names = rules.ruleAll(rules.ruleStr(re, "name"), base, html);
                        List<String> authors = rules.ruleAll(rules.ruleStr(re, "author"), base, html);
                        List<String> urls = rules.ruleAll(rules.ruleStr(re, "bookUrl"), base, html);
                        if (names.isEmpty() && !listRule.isEmpty()) {
                            List<String> blocks = rules.ruleAll(listRule, base, html);
                            if (blocks.size() == 1 && blocks.get(0).contains("<")) {
                                names = rules.ruleAll(rules.ruleStr(re, "name"), base, blocks.get(0));
                                authors = rules.ruleAll(rules.ruleStr(re, "author"), base, blocks.get(0));
                                urls = rules.ruleAll(rules.ruleStr(re, "bookUrl"), base, blocks.get(0));
                            }
                        }
                        int n = Math.min(names.size(), Math.max(names.size(), urls.size()) == 0 ? 0 : Math.min(names.size(), Math.max(1, urls.size())));
                        for (int i = 0; i < n && i < 40; i++) {
                            String nm = names.get(i).trim();
                            if (nm.isEmpty()) continue;
                            found.add(new String[]{nm, i < authors.size() ? authors.get(i) : "",
                                    i < urls.size() ? urls.get(i) : "", String.valueOf(si)});
                        }
                    } catch (Exception e) {
                        host.log("听书", "发现异常 源" + si + "：" + e.getMessage());
                    }
                }
                ui.post(new Runnable() { @Override public void run() {
                    nbResults = found;
                    List<Item> items = new ArrayList<>();
                    for (int i = 0; i < found.size(); i++) {
                        items.add(new Item(found.get(i)[0] + (found.get(i)[1].isEmpty() ? "" : "　" + found.get(i)[1]), "nb:book:" + i));
                    }
                    if (items.isEmpty()) items.add(new Item("（书源里没有发现规则，或页面取不到）"));
                    host.pushScreen(new Screen("发现", items));
                    host.speak(found.isEmpty() ? "没有发现内容" : ("发现 " + found.size() + " 项"));
                } });
            }
        });
    }

    public Screen netBookScreen() {
        List<Item> items = new ArrayList<>();
        String pend = prefs.getString("updPending", "");
        if (pend != null && !pend.isEmpty())
            items.add(new Item("收藏更新：" + pend + "，点这里看", "nb:checkupd"));
        else
            items.add(new Item("检查收藏更新", "nb:checkupd"));
        items.add(new Item("每天自动检查："
                + (prefs.getBoolean("updAuto", true) ? "开" : "关") + "，点一下切换", "nb:updauto"));
        items.add(new Item("书源管理（导入、启停、导出）", "go:booksrc"));
        items.add(new Item("订阅", "nb:subs"));
        items.add(new Item("订阅源管理（导入、启停）", "go:subsrc"));
        return new Screen("网上阅读", items);
    }


    public Screen nbChapterListScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("全部章节：" + nbChapters.size() + " 章"));
        int last = nbLastChapterIdx();
        boolean hasLast = last >= 0 && last < nbChapters.size()
                && nbLastChapter != null && nbChapters.get(last)[0].equals(nbLastChapter);
        if (hasLast) {
            items.add(new Item("继续上次：第 " + (last + 1) + " 章 " + nbChapters.get(last)[0], "nb:ch:" + last));
        }
        for (int k = 0; k < nbChapters.size(); k++) {
            items.add(new Item((hasLast && k == last ? "✔ " : "") + nbChapters.get(k)[0], "nb:ch:" + k));
        }
        if (nbChapters.isEmpty()) items.add(new Item("（目录还没取到）"));
        return new Screen((nbBookName == null ? "目录" : nbBookName) + "　" + nbChapters.size() + " 章", items).k("toc");
    }
}
