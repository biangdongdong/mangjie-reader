package com.mangjie.reader.net;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 批量校验书源，逐条对齐 Legado 的 model/CheckSource.kt + service/CheckSourceService.kt。
 *
 * Legado 的语义（照抄过来的规矩）：
 *  - 校验前先 source.removeInvalidGroups() + source.removeErrorComment()，把上一轮的结论擦掉再判；
 *  - 关键词默认「我的」，源自己写了 checkKeyword 就用源的；
 *  - 超时默认 180000ms，超时打「校验超时」，js 出错打「js失效」，其他异常打「网站失效」；
 *  - 搜索：searchUrl 空打「搜索链接规则为空」；搜不到书打「搜索失效」；搜到了拿第一本去查详情目录正文；
 *  - 发现：exploreUrl 空跳过；一个能用的分类都取不到打「发现规则为空」；列出空打「发现失效」；
 *  - 详情、目录、正文三开关各自可以关；正文空打「搜索正文失效／发现正文失效」，目录空打「搜索目录失效／发现目录失效」；
 *  - 末尾把 respondTime 回写成这一轮的耗时；
 *  - 有一项没过就把这一项的标签写进 bookSourceGroup，同时把原因写进 bookSourceComment。
 */
public class SourceChecker {

    /** Legado 里那几个会来回打/擦的失效分组标签 */
    public static final String[] INVALID_GROUPS = new String[]{
            "校验超时", "js失效", "网站失效",
            "搜索链接规则为空", "搜索失效",
            "发现规则为空", "发现失效",
            "搜索正文失效", "搜索目录失效",
            "发现正文失效", "发现目录失效"
    };

    public static class Cfg {
        public String keyword = "我的";
        public int timeoutMs = 180000;
        public boolean search = true;
        public boolean discovery = true;
        public boolean info = true;
        public boolean category = true;
        public boolean content = true;
        public int threads = 3;

        public String summary() {
            List<String> on = new ArrayList<>();
            if (search) on.add("搜索");
            if (discovery) on.add("发现");
            if (info) on.add("详情");
            if (category) on.add("目录");
            if (content) on.add("正文");
            return "超时 " + (timeoutMs / 1000) + " 秒，校验 "
                    + (on.isEmpty() ? "无" : join(on, "/")) + "，关键词「" + keyword + "」";
        }

        private static String join(List<String> l, String sep) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) b.append(sep);
                b.append(l.get(i));
            }
            return b.toString();
        }
    }

    /** 一个源的校验结论 */
    public static class Result {
        public String name = "";
        public String url = "";
        public List<String> problems = new ArrayList<>();
        public String error = "";        // 异常原文（超时/网络/解析）
        public long costMs = 0;

        public boolean ok() { return problems.isEmpty() && error.isEmpty(); }

        public String text() {
            if (ok()) return "✓ " + name + "　通过，" + costMs + "ms";
            StringBuilder b = new StringBuilder("✗ ").append(name).append("　");
            if (!problems.isEmpty()) b.append(join(problems, "、"));
            if (!error.isEmpty()) b.append(problems.isEmpty() ? "" : "；").append(error);
            return b.toString();
        }

        private static String join(List<String> l, String sep) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) b.append(sep);
                b.append(l.get(i));
            }
            return b.toString();
        }
    }

    private final NetBookController nb;
    private final BookSourceOps.Host host;
    private final RuleEngine rules;
    private final Cfg cfg;

    private volatile boolean stopped = false;
    private volatile boolean paused = false;
    private final List<Result> results = Collections.synchronizedList(new ArrayList<Result>());

    public SourceChecker(NetBookController nb, BookSourceOps.Host host, Cfg cfg) {
        this.nb = nb;
        this.host = host;
        this.rules = nb.rulesOf();
        this.cfg = cfg == null ? new Cfg() : cfg;
    }

    public List<Result> results() { return results; }
    public Cfg cfg() { return cfg; }
    public void stop() { stopped = true; paused = false; }
    public void pause() { paused = true; }
    public void resume() { paused = false; }
    public boolean isRunning() { return !stopped && started; }
    public boolean isPaused() { return paused; }
    private boolean started = false;
    private volatile int total = 0;      // 这一批一共多少个源

    /** 后台跑一遍，回调每源结论；跑完调 onDone */
    public void start(final List<Integer> idxs, final Runnable onDone) {
        if (idxs == null || idxs.isEmpty()) { if (onDone != null) onDone.run(); return; }
        started = true;
        stopped = false;
        paused = false;
        total = idxs.size();
        results.clear();
        nb.nbChecking = true;
        int n = idxs.size();
        total = n;
        com.mangjie.reader.tools.TaskProgress.start("校验书源", n);
        host.log("校验", "开始，共 " + n + " 个源。" + cfg.summary());
        new Thread(new Runnable() {
            @Override public void run() {
                int threads = Math.min(Math.max(1, cfg.threads), 9);
                final int[] next = new int[]{0};
                final int[] done = new int[]{1};
                final Object lock = new Object();
                List<Thread> ts = new ArrayList<Thread>();
                for (int t = 0; t < threads; t++) {
                    Thread th = new Thread(new Runnable() {
                        @Override public void run() {
                            while (!stopped) {
                                if (paused) {
                                    try { Thread.sleep(200); } catch (Throwable ignored) { }
                                    continue;
                                }
                                int k;
                                synchronized (lock) { k = next[0]++; }
                                if (k >= idxs.size()) return;
                                int si = idxs.get(k);
                                Result r = checkOne(si);
                                synchronized (lock) {
                                    results.add(r);
                                    int d = done[0]++;
                                    com.mangjie.reader.tools.TaskProgress.update(d);
                                    if (d % 5 == 0 || d == n) {
                                        host.speakQueued("校验 " + (d - 1) + "／" + n
                                                + "，不合格 " + countBad());
                                    }
                                    if (r.ok()) host.log("校验", "通过：" + r.name);
                                    else host.log("校验", "不合格：" + r.text());
                                }
                            }
                        }
                    });
                    th.setDaemon(true);
                    ts.add(th);
                    th.start();
                }
                for (Thread th : ts) { try { th.join(); } catch (Throwable ignored) { } }
                com.mangjie.reader.tools.TaskProgress.finish();
                nb.nbChecking = false;
                started = false;
                host.log("校验", (stopped ? "已中止，" : "结束，") + "共 " + results.size()
                        + " 个，不合格 " + countBad());
                if (onDone != null) host.ui().post(onDone);
            }
        }, "source-checker").start();
    }

    /** 这一批一共多少个源 */
    public int total() { return total; }

    /** 已出结论里通过多少个 */
    public int countOk() { return results.size() - countBad(); }

    /** 还没校验的还剩多少个 */
    public int countLeft() { int l = total - results.size(); return l < 0 ? 0 : l; }

    public int countBad() {
        int bad = 0;
        for (Result r : results) if (!r.ok()) bad++;
        return bad;
    }

    // ---------------- 单源 ----------------

    private Result checkOne(int si) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        try {
            if (si < 0 || si >= nb.nbSources.size()) { r.name = "源 " + si; return r; }
            JSONObject src = nb.nbSources.get(si);
            r.name = rules.ruleStr(src, "bookSourceName").trim();
            if (r.name.isEmpty()) r.name = "书源 " + si;
            r.url = rules.ruleStr(src, "bookSourceUrl").trim();

            // 擦掉上一轮的结论（Legado: removeInvalidGroups + removeErrorComment）
            // 用户自己写的注释要留着，只抹掉我们以前追加的「[校验]」段
            String oldGroup = src.optString("bookSourceGroup", "");
            src.put("bookSourceGroup", removeInvalid(oldGroup));
            src.put("bookSourceComment", stripCheckComment(src.optString("bookSourceComment", "")));

            checkSource(si, src, r);

            long cost = System.currentTimeMillis() - t0;
            r.costMs = cost;
            writeBack(si, src, r);
        } catch (Throwable e) {
            r.error = e.getClass().getSimpleName() + "：" + (e.getMessage() == null ? "" : e.getMessage());
            r.costMs = System.currentTimeMillis() - t0;
            writeBackSafe(si, r);
        }
        return r;
    }

    private void checkSource(int si, JSONObject src, Result r) {
        long deadline = System.currentTimeMillis() + Math.max(5000, cfg.timeoutMs);

        // 1. 搜索
        if (cfg.search) {
            String searchUrl = rules.ruleStr(src, "searchUrl").trim();
            if (searchUrl.isEmpty()) {
                r.problems.add("搜索链接规则为空");
            } else {
                List<String[]> books = nb.nbSearchInSource(si, checkKeyword(src));
                if (System.currentTimeMillis() > deadline) { r.problems.add("校验超时"); return; }
                if (books.isEmpty()) {
                    r.problems.add("搜索失效");
                } else {
                    checkBook(si, src, books.get(0), true, r, deadline);
                }
            }
        }

        // 2. 发现
        if (cfg.discovery) {
            String exUrl = rules.ruleStr(src, "exploreUrl").trim();
            if (!exUrl.isEmpty()) {
                String catUrl = firstExploreUrl(src, si);
                if (catUrl.isEmpty()) {
                    r.problems.add("发现规则为空");
                } else {
                    List<String[]> found = nb.nbFetchExploreList(src, si, catUrl);
                    if (System.currentTimeMillis() > deadline) { r.problems.add("校验超时"); return; }
                    if (found.isEmpty()) {
                        r.problems.add("发现失效");
                    } else {
                        checkBook(si, src, found.get(0), false, r, deadline);
                    }
                }
            }
        }
    }

    /**
     * 源级关键词优先，没有就用全局的。
     * 对齐 Legado 的 getCheckKeyword()：读的是 ruleSearch.checkKeyWord（注意大写 W），
     * 顶层也认 checkKeyword 这个写法（我们自己加过，留给手工源）。
     */
    private String checkKeyword(JSONObject src) {
        JSONObject rs = rules.sub(src, "ruleSearch");
        String k = rs == null ? "" : rules.ruleStr(rs, "checkKeyWord").trim();
        if (k.isEmpty()) k = rules.ruleStr(src, "checkKeyword").trim();
        return k.isEmpty() ? cfg.keyword : k;
    }

    /** 取第一个带 url 的发现分类地址 */
    private String firstExploreUrl(JSONObject src, int si) {
        try {
            List<Object[]> groups = nb.nbGroupsOf(src, si);
            for (Object[] g : groups) {
                Object list = g[1];
                if (!(list instanceof List)) continue;
                for (Object one : (List<?>) list) {
                    if (!(one instanceof String[])) continue;
                    String[] cat = (String[]) one;
                    if (cat.length >= 2 && cat[1] != null && !cat[1].trim().isEmpty()) return cat[1].trim();
                }
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /** 详情 / 目录 / 正文三段（Legado 的 checkBook） */
    private void checkBook(int si, JSONObject src, String[] book, boolean isSearch, Result r, long deadline) {
        String type = isSearch ? "搜索" : "发现";
        String name = book.length > 0 ? book[0] : "";
        String author = book.length > 1 ? book[1] : "";
        String bookUrl = book.length > 2 ? book[2] : "";
        if (bookUrl == null || bookUrl.isEmpty()) { r.problems.add(type + "失效"); return; }

        if (!cfg.info) return;

        // 详情：顺手把目录地址拿到（我们打开目录时也是这么取）
        String tocUrl = "";
        try {
            String[] d = nb.nbFetchDetail(si, bookUrl);
            if (System.currentTimeMillis() > deadline) { r.problems.add("校验超时"); return; }
            if (d != null && d.length > 0 && (d[0] == null || d[0].isEmpty()) && (d[5] == null || d[5].isEmpty())) {
                r.problems.add("网站失效");
                return;
            }
            if (d != null && d.length > 0) name = d[0] == null || d[0].isEmpty() ? name : d[0];
        } catch (Throwable e) {
            r.problems.add("网站失效");
            r.error = shortError(e);
            return;
        }
        tocUrl = bookUrl;

        if (!cfg.category) return;

        // 目录：至少一条，且要能拿到正文地址
        List<String[]> toc = fetchTocHead(si, src, bookUrl, 2);
        if (System.currentTimeMillis() > deadline) { r.problems.add("校验超时"); return; }
        if (toc.isEmpty()) {
            r.problems.add(type + "目录失效");
            return;
        }
        if (!cfg.content) return;

        // 正文：第一条章节，带上第二条当「下一章」（有下一页规则的书源靠它断尾）
        String cUrl = toc.get(0)[1];
        String nextUrl = toc.size() > 1 ? toc.get(1)[1] : cUrl;
        try {
            String text = nb.nbFetchChapterText(src, cUrl, nextUrl);
            if (System.currentTimeMillis() > deadline) { r.problems.add("校验超时"); return; }
            if (text == null || text.trim().length() < 10) {
                r.problems.add(type + "正文失效");
            }
        } catch (Throwable e) {
            r.problems.add(type + "正文失效");
            r.error = shortError(e);
        }
    }

    /** 目录前几条 {标题, 地址}，取法与打开目录时一致 */
    private List<String[]> fetchTocHead(int si, JSONObject src, String bookUrl, int limit) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONObject rc = rules.sub(src, "ruleToc");
            if (rc == null) return out;
            String base = rules.ruleStr(src, "bookSourceUrl");
            String lr = rules.ruleStr(rc, "chapterList");
            if (lr.startsWith("-")) lr = lr.substring(1);
            if (lr.startsWith("-")) lr = lr.substring(1);
            if (lr.isEmpty() || lr.startsWith(":")) return out;   // 正则式目录这里不追，交给正文那步

            String bookHtml = host.requestUrlT(bookUrl, base, 20000);
            if (bookHtml == null) return out;
            String tocUrl = bookUrl;
            JSONObject rt = rules.sub(src, "ruleBookInfo");
            bookHtml = rules.applyInit(rt, base, bookHtml);
            if (rt != null) {
                String t = rules.ruleStr(rt, "tocUrl");
                if (!t.isEmpty()) {
                    String t2 = rules.urlOf(t, base, bookHtml);
                    if (!t2.isEmpty()) tocUrl = t2;
                }
            }
            String html = tocUrl.equals(bookUrl) ? bookHtml : host.requestUrlT(tocUrl, base, 20000);
            if (html == null) html = bookHtml;
            List<org.jsoup.nodes.Element> els = rules.defaultChain(lr, base, html);
            if (els == null) return out;
            String nTok = rules.ruleStr(rc, "chapterName");
            if (nTok.isEmpty()) nTok = rules.ruleStr(rc, "name");
            if (nTok.isEmpty()) nTok = "text";
            String uTok = rules.ruleStr(rc, "chapterUrl");
            if (uTok.isEmpty()) uTok = rules.ruleStr(rc, "url");
            if (uTok.isEmpty()) uTok = "href";
            for (org.jsoup.nodes.Element e : els) {
                if (out.size() >= limit) break;
                String nm = rules.pickElem(nTok, e, base);
                String uu = rules.pickElem(uTok, e, base);
                if (nm == null || uu == null) continue;
                nm = nm.trim();
                uu = uu.trim();
                if (nm.isEmpty() || uu.isEmpty()) continue;
                out.add(new String[]{nm, nb.absoluteUrl(tocUrl, uu)});
            }
        } catch (Throwable ignored) { }
        return out;
    }

    // ---------------- 写回 ----------------

    private void writeBack(int si, JSONObject src, Result r) {
        try {
            String group = mergeGroup(removeInvalid(src.optString("bookSourceGroup", "")), r.problems);
            String base = stripCheckComment(src.optString("bookSourceComment", ""));
            String comment = base;
            if (!r.ok()) {
                String why = (r.problems.isEmpty() ? "" : join(r.problems, "、"))
                        + (r.error.isEmpty() ? "" : (r.problems.isEmpty() ? r.error : "；" + r.error));
                comment = base.isEmpty() ? ("[校验] " + why) : (base + "\n[校验] " + why);
            }
            // 三个字段一次写盘，别一个字段一次全量重读
            nb.setSourceFields(si, new String[]{"bookSourceGroup", "bookSourceComment", "respondTime"},
                    new Object[]{group, comment, Long.valueOf(r.costMs)});
        } catch (Throwable ignored) { }
    }

    private void writeBackSafe(int si, Result r) {
        try {
            nb.setSourceFields(si, new String[]{"bookSourceComment", "respondTime"},
                    new Object[]{"[校验] 异常：" + r.error, Long.valueOf(r.costMs)});
        } catch (Throwable ignored) { }
    }

    /** 去掉以前追加的「[校验]」行，保留用户自己的注释 */
    private static String stripCheckComment(String c) {
        if (c == null || c.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String line : c.split("\n")) {
            if (line.startsWith("[校验]")) continue;
            if (b.length() > 0) b.append("\n");
            b.append(line);
        }
        return b.toString().trim();
    }

    private static String removeInvalid(String group) {
        if (group == null) return "";
        List<String> keep = new ArrayList<>();
        String[] parts = group.split("[,，]");
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            boolean bad = false;
            for (String inv : INVALID_GROUPS) if (inv.equals(t)) { bad = true; break; }
            if (!bad) keep.add(t);
        }
        return join(keep, ",");
    }

    private static String mergeGroup(String base, List<String> problems) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (base != null) for (String p : base.split("[,，]")) {
            String t = p.trim();
            if (!t.isEmpty()) set.add(t);
        }
        if (problems != null) for (String p : problems) if (p != null && !p.trim().isEmpty()) set.add(p.trim());
        return join(new ArrayList<String>(set), ",");
    }

    private static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(l.get(i));
        }
        return b.toString();
    }

    private static String shortError(Throwable e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : "：" + m);
    }
}
