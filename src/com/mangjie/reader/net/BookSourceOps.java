/*
 * 本文件的部分实现参照「开源阅读 Legado」（https://github.com/gedoor/legado）。
 * Legado：Copyright (C) 2019-2023 gedoor，GNU General Public License v3.0。
 * 本工程对其的使用方式与修改说明见仓库根目录 NOTICE，完整条款见 LICENSE。
 */
package com.mangjie.reader.net;

import com.mangjie.reader.model.Item;

import org.json.JSONArray;
import org.json.JSONObject;
import com.mangjie.reader.model.Screen;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;

/** 书源与订阅的导入、导出、校验、详情页。页面只装数据，渲染仍由宿主负责。 */
public final class BookSourceOps {

    public interface Host {
        void log(String tag, String msg);
        void speak(String msg);

        /** 不打断正文的播报：进度类提示走这条，默认退化成普通播报 */
        default void speakQueued(String msg) { speak(msg); }
        void toast(String msg);
        android.os.Handler ui();
        String httpText(String url);
        String readTextFile(java.io.File f);
        /** 请求前交出去的 URL 模板上下文；订阅/书源管理这边没有搜索词，传空 */
        default void setUrlCtx(String key, int page) {}

        /** 当前源自带的 jsLib（共享 JS），求值前先灌进作用域 */
        default void setJsLib(String lib) {}

        /** 当前书源本体，JS 里的 source 对象（getKey/getVariable/putVariable 那些） */
        default void setSource(org.json.JSONObject src) {}
        String requestUrl(String spec, String base);
        /** 带超时的请求：冒烟校验要卡时间，不然一堆死源拖死 */
        String requestUrlT(String spec, String base, int timeoutMs);
        String bookSrcDir();
        /** 应用数据目录，单源日志在这儿翻 */
        String dataDir();
        String subSrcDir();
        boolean prefBool(String key, boolean def);
        int prefInt(String key, int def);
        String prefString(String key, String def);
        void prefSet(String key, String value);
        void pushScreen(Screen s);
        void replaceTop(Screen s);
        /** 当前栈顶屏，回调回来先核一下自己还在不在栈顶 */
        Screen topScreen();
        Screen bookSourceScreen();
        Screen subSourceScreen();
        String stamp();
        void renderNow();
        /** 用系统浏览器打开网址；没有浏览器返回 false */
        boolean openUrl(String url);
    }

    private final Host host;
    private final NetBookController nb;

    /** 请求入口：订阅那边没有 {{key}}，把上下文清干净再发，免得带上上次的搜索词 */
    private String req(String url, String base) {
        host.setUrlCtx("", 1);
        return host.requestUrl(url, base);
    }

    private String reqT(String url, String base, int t) {
        host.setUrlCtx("", 1);
        return host.requestUrlT(url, base, t);
    }
    private final RuleEngine rules;
    private String curDir = null;
    public int subArticleSrc = -1;
    public String subArticleName = null;
    public String subArticleBody = "";
    public List<String[]> subArticles = new ArrayList<>();
    /** 切到某个订阅源时把它的 jsLib 交给求值器 */
    private void bindSrc(JSONObject src) {
        host.setSource(src);
        host.setJsLib(src == null ? "" : src.optString("jsLib", ""));
    }

    public List<org.json.JSONObject> subSources = new ArrayList<>();
    public List<File> subSourceFiles = new ArrayList<>();

    public BookSourceOps(Host host, NetBookController nb, RuleEngine rules) {
        this.host = host;
        this.nb = nb;
        this.rules = rules;
    }

    public Screen bsrcPickScreen(String path) {
        List<Item> items = new ArrayList<>();
        File dir = new File(path);
        if (!dir.exists()) dir.mkdirs();
        File[] fs = dir.listFiles();
        if (fs == null) {
            items.add(new Item("无法访问此目录"));
            return new Screen("选择书源文件", items);
        }
        Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        int shows = 0;
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                items.add(new Item(f.getName(), "bsrc:pickdir:" + f.getAbsolutePath()));
                shows++;
            } else {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".json") || n.endsWith(".txt")) {
                    items.add(new Item(f.getName(), "bsrc:imp:" + f.getAbsolutePath()));
                    shows++;
                }
            }
            if (shows >= 300) break;
        }
        if (items.isEmpty()) items.add(new Item("（这个目录里没有 json / txt）"));
        Screen s = new Screen("选择书源文件", items);
        s.fileMode = true;
        this.curDir = path;
        return s;
    }

    public void bsrcImportFile(String path) {
        File f = new File(path);
        String text = host.readTextFile(f);
        if (text == null || text.trim().isEmpty()) { host.speak("这个文件读不出来"); return; }
        int added = bsrcImportText(text, f.getName());
        if (added == 0) host.speak("没导入成功，文件不是书源格式");
        else host.speak("已导入 " + added + " 个书源");
        host.replaceTop(host.bookSourceScreen());
    }

    private int bsrcImportText(String text, String hint) {
        int added = 0;
        try {
            File dir = new File(host.bookSrcDir());
            if (!dir.exists()) dir.mkdirs();
            String stampNow = host.stamp();
            File out = new File(dir, "导入-" + stampNow + ".json");
            String t = text.trim();
            // 校验格式并原样落盘
            if (t.startsWith("[")) {
                org.json.JSONArray arr = new org.json.JSONArray(t);
                added = arr.length();
            } else {
                JSONObject o = new JSONObject(t);
                if (o.has("bookSourceName") || o.has("sources")) added = 1;
                else if (o.has("data") && o.has("code")) {
                    host.speak("这个不是书源文件，像是接口返回");
                    return 0;
                }
            }
            if (added == 0) return 0;
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            fo.write(t.getBytes("UTF-8"));
            fo.close();
            host.log("听书", "导入书源 " + out.getName() + "，共 " + added + " 个");
            nb.loadBookSources();
        } catch (Exception e) {
            host.log("听书", "导入失败：" + e.getMessage());
            return 0;
        }
        return added;
    }

    public void bsrcImportUrl(final String url) {
        host.speak("正在下载书源");
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                final String text = host.httpText(url.trim());
                host.ui().post(new Runnable() { @Override public void run() {
                    if (text == null || text.trim().isEmpty()) { host.speak("下载失败，地址不可达"); return; }
                    int added = bsrcImportText(text, "url");
                    if (added == 0) { host.speak("下载回来的不是书源格式"); return; }
                    host.speak("已导入 " + added + " 个书源");
                    Screen t = host.topScreen();
                    if (t == null || !"bsrc".equals(t.key)) return;   // 人已经走了，别把他的屏换掉
                    host.replaceTop(host.bookSourceScreen());
                    host.renderNow();
                } });
            }
        });
    }

    /** 只导出点名的那几个（勾选导出用）；名字对不上就跳过 */
    public int bsrcExportNames(java.util.Collection<String> names) {
        try {
            // 全量导出同样落公有 /sdcard/盲界阅读导出/书源
            File dir = new File(com.mangjie.reader.App.exportDir(), "书源");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "导出-" + host.stamp() + ".json");
            org.json.JSONArray arr = new org.json.JSONArray();
            int n = 0;
            for (JSONObject o : nb.nbSources) {
                String nm = o.optString("bookSourceName", "");
                if (names != null && !names.contains(nm)) continue;
                arr.put(o);
                n++;
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            fo.write(arr.toString(2).getBytes("UTF-8"));
            fo.close();
            host.speak("已导出 " + n + " 个书源到 " + out.getName());
            return n;
        } catch (Exception e) {
            host.speak("导出失败");
            host.log("听书", "导出失败：" + e.getMessage());
            return 0;
        }
    }

    public void bsrcExport() {
        try {
            // 全量导出同样落公有 /sdcard/盲界阅读导出/书源
            File dir = new File(com.mangjie.reader.App.exportDir(), "书源");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "导出-" + host.stamp() + ".json");
            org.json.JSONArray arr = new org.json.JSONArray();
            for (JSONObject o : nb.nbSources) arr.put(o);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            fo.write(arr.toString(2).getBytes("UTF-8"));
            fo.close();
            host.speak("已导出 " + nb.nbSources.size() + " 个书源，文件在 " + out.getAbsolutePath());
            host.toast("已导出到 " + out.getName());
        } catch (Exception e) {
            host.speak("导出失败");
            host.log("听书", "导出失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------ 书源列表的筛选、菜单屏

    /** 筛选后仍然要显示的书源下标（没筛就是全部） */
    public List<Integer> bsrcKeepIdx() {
        nb.loadBookSources();
        int ft = host.prefInt("srcFilterType", -1);
        int fo = host.prefInt("srcFilterOn", -1);
        List<Integer> keep = new ArrayList<Integer>();
        for (int i = 0; i < nb.nbSources.size(); i++) {
            JSONObject o = nb.nbSources.get(i);
            if (ft >= 0 && nb.srcType(o) != ft) continue;
            if (fo == 1 && !nb.srcEnabled(o)) continue;
            if (fo == 0 && nb.srcEnabled(o)) continue;
            keep.add(i);
        }
        return keep;
    }

    // ---------- 批量校验（对齐 Legado CheckSource） ----------

    private SourceChecker checker = null;

    public SourceChecker.Cfg checkCfg() {
        SourceChecker.Cfg c = new SourceChecker.Cfg();
        c.keyword = host.prefString("chkKey", "我的");
        if (c.keyword == null || c.keyword.trim().isEmpty()) c.keyword = "我的";
        c.timeoutMs = host.prefInt("chkTimeoutSec", 180) * 1000;
        c.search = host.prefBool("chkSearch", true);
        c.discovery = host.prefBool("chkDisc", true);
        c.info = host.prefBool("chkInfo", true);
        c.category = host.prefBool("chkCat", true);
        c.content = host.prefBool("chkCont", true);
        // 校验串行跑：我们的搜索/发现流程共用 RuleEngine 的 JS 变量表和分页游标，
        // 并发会让两个源互相覆盖，宁可慢也不能把好源判成失效
        c.threads = 1;
        return c;
    }

    public SourceChecker checker() { return checker; }

    /** 校验页：设置 + 开始/暂停/停止 + 上次结果 */
    public Screen bsrcCheckScreen() {
        SourceChecker.Cfg c = checkCfg();
        List<Item> items = new ArrayList<Item>();
        int keep = bsrcKeepIdx().size();
        items.add(new Item("校验关键词：" + c.keyword + "，点一下改", "bsrc:chkkey"));
        items.add(new Item("每个源超时：" + (c.timeoutMs / 1000) + " 秒，点一下改", "bsrc:chktimeout"));
        items.add(new Item("校验搜索：" + yn(c.search), "bsrc:chksearch"));
        items.add(new Item("校验发现：" + yn(c.discovery), "bsrc:chkdisc"));
        items.add(new Item("校验详情：" + yn(c.info), "bsrc:chkinfo"));
        items.add(new Item("校验目录：" + yn(c.category), "bsrc:chkcat"));
        items.add(new Item("校验正文：" + yn(c.content), "bsrc:chkcont"));
        boolean busy = checker != null && checker.isRunning();
        int doneN = checker == null ? 0 : checker.results().size();
        int okN = checker == null ? 0 : checker.countOk();
        int badN = checker == null ? 0 : checker.countBad();
        int leftN = (checker != null && checker.isRunning() && checker.total() > 0)
                ? checker.countLeft() : Math.max(0, keep - doneN);
        int allN = (checker != null && checker.isRunning() && checker.total() > 0) ? checker.total() : keep;
        items.add(new Item((busy ? "校验中，已出 " + doneN + " 个结论" : "校验那些失效的源")
                + "，这批 " + keep + " 个", busy ? "bsrc:chkpause" : "bsrc:chkrun"));
        if (busy) {
            items.add(new Item("统计：共 " + allN + " 个，已校验 " + doneN + " 个，通过 " + okN
                    + " 个，不合格 " + badN + " 个，还在校验 " + leftN + " 个", "none"));
            items.add(new Item("刷新统计（点一下）", "bsrc:chkrefresh"));
        } else if (doneN > 0) {
            items.add(new Item("统计：上次校验 " + doneN + " 个，通过 " + okN
                    + " 个，不合格 " + badN + " 个", "none"));
        } else {
            items.add(new Item("统计：这批 " + allN + " 个，还没开始校验", "none"));
        }
        if (busy) {
            items.add(new Item(checker.isPaused() ? "继续校验" : "暂停校验",
                    checker.isPaused() ? "bsrc:chkresume" : "bsrc:chkpause"));
            items.add(new Item("停止校验", "bsrc:chkstop"));
        }
        int bad = checker == null ? 0 : checker.countBad();
        items.add(new Item(checker == null ? "还没有校验结果"
                : ("上次结果：" + checker.results().size() + " 个里不合格 " + bad + " 个"), "bsrc:chkresult"));
        items.add(new Item("说明：校验会把「搜索失效」「发现失效」「校验超时」这类标签写进书源分组，"
                + "下次校验前会自动擦掉；每个源的耗时写进 respondTime。", "none"));
        items.add(new Item("校验一个源要跑搜索、发现、详情、目录、正文，串行跑，慢；"
                + "先用筛选把范围缩小再校验。", "none"));
        return new Screen("校验书源", items).k("bsrcCheck");
    }

    /** 结果页：不合格的排前面 */
    public Screen bsrcCheckResultScreen() {
        List<Item> items = new ArrayList<Item>();
        if (checker == null || checker.results().isEmpty()) {
            items.add(new Item("（还没有校验结果，先到上一页点校验）"));
            return new Screen("校验结果", items).k("bsrcCheckResult");
        }
        List<SourceChecker.Result> all = new ArrayList<SourceChecker.Result>(checker.results());
        int bad = checker.countBad();
        items.add(new Item("共 " + all.size() + " 个，不合格 " + bad + " 个，"
                + (all.size() - bad) + " 个通过", "none"));
        for (int pass = 0; pass < 2; pass++) {
            for (SourceChecker.Result r : all) {
                if ((pass == 0) != !r.ok()) continue;
                items.add(new Item(r.text(), "none"));
            }
        }
        return new Screen("校验结果", items).k("bsrcCheckResult");
    }

    public void startCheck(List<Integer> idxs) {
        if (checker != null && checker.isRunning()) { host.speak("正在校验，先停止或等一下"); return; }
        if (idxs == null || idxs.isEmpty()) { host.speak("没有要校验的源"); return; }
        checker = new SourceChecker(nb, host, checkCfg());
        checker.start(idxs, new Runnable() {
            @Override public void run() {
                nb.loadBookSources();       // 批量写回没重载，这里统一对齐内存
                host.renderNow();
                host.speak("校验结束，" + checker.results().size() + " 个里不合格 " + checker.countBad() + " 个");
            }
        });
        host.renderNow();
    }

    /** 播报一次校验统计（点「刷新统计」时用） */
    public void speakCheckStat() {
        if (checker == null) { host.speak("还没有校验结果"); return; }
        host.speak("共 " + checker.total() + " 个，已校验 " + checker.results().size()
                + " 个，通过 " + checker.countOk() + " 个，不合格 " + checker.countBad()
                + " 个，还在校验 " + checker.countLeft() + " 个");
    }

    public void pauseCheck() {
        if (checker == null) return;
        checker.pause();
        host.speak("已暂停");
        host.renderNow();
    }

    public void resumeCheck() {
        if (checker == null) return;
        checker.resume();
        host.speak("已继续");
        host.renderNow();
    }

    public void stopCheck() {
        if (checker == null) return;
        checker.stop();
        host.speak("已停止，已出 " + checker.results().size() + " 个结论");
        host.renderNow();
    }

    /** 校验页上的开关与字段：改完写 prefs 再重画这一页 */
    public void toggleCheck(String prefKey) {
        boolean now = host.prefBool(prefKey, true);
        host.prefSet(prefKey, String.valueOf(!now));
        host.renderNow();
        host.speak((now ? "已关闭" : "已打开"));
    }

    public void setCheckCfg(String prefKey, String value) {
        host.prefSet(prefKey, value);
    }

    private static String yn(boolean b) { return b ? "开" : "关"; }

    /** 书源页的菜单：筛选、排序、批量校验、分组、搜索、导入导出都挂在这儿 */
    public Screen bsrcMenuScreen() {
        int ft = host.prefInt("srcFilterType", -1);
        int fo = host.prefInt("srcFilterOn", -1);
        int srt = host.prefInt("srcSort", 0);
        int keep = bsrcKeepIdx().size();
        int gn = nb.sourceGroupNames().size();
        String[] tyNames = {"全部类型", "只看文本", "只看音频", "只看图片"};
        String[] onNames = {"全部状态", "只看启用", "只看关闭"};
        String[] srtNames = {"原顺序", "按名称", "按权重"};
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("筛选：" + tyNames[Math.max(0, Math.min(3, ft + 1))]
                + "　" + onNames[Math.max(0, Math.min(2, fo + 1))] + "（点一下改）", "bsrc:filter"));
        items.add(new Item("排序：" + srtNames[Math.max(0, Math.min(2, srt))], "bsrc:sort"));
        items.add(new Item("校验书源（按开源阅读的规则查一遍，这批 " + keep + " 个）", "bsrc:checkall"));
        items.add(new Item("按分组查看（" + gn + " 组）", "bsrc:groups"));
        items.add(new Item("搜索书源", "bsrc:find"));
        items.add(new Item("全部启用", "bsrc:allon"));
        items.add(new Item("全部关闭", "bsrc:alloff"));
        items.add(new Item("导入书源（选文件）", "nb:impfile"));
        items.add(new Item("导入书源（填网址）", "nb:impurl"));
        items.add(new Item("导出全部书源", "nb:export"));
        items.add(new Item("导出当前筛出的 " + keep + " 个", "bsrc:expshown"));
        items.add(new Item("勾选导出（进勾选页）", "bsrc:markstart"));
        items.add(new Item("新建书源（空源／通用兜底／嗅探地址）", "bsrc:new"));
        items.add(new Item("系统书源更新后清空本地旧副本："
                + (host.prefBool("srcSyncClean", true) ? "开" : "关"), "bsrc:sysclean"));
        items.add(new Item("重新载入书源", "nb:reload"));
        return new Screen("书源菜单", items).k("bsrcMenu");
    }

    /** 筛选页：类型和状态各一行，点一下往下换一档 */
    public Screen bsrcFilterScreen() {
        int ft = host.prefInt("srcFilterType", -1);
        int fo = host.prefInt("srcFilterOn", -1);
        String[] tyNames = {"全部类型", "只看文本", "只看音频", "只看图片"};
        String[] onNames = {"全部状态", "只看启用", "只看关闭"};
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("类型：" + tyNames[Math.max(0, Math.min(3, ft + 1))], "bsrc:ftype"));
        items.add(new Item("状态：" + onNames[Math.max(0, Math.min(2, fo + 1))], "bsrc:fon"));
        items.add(new Item("当前筛出 " + bsrcKeepIdx().size() + " 个源"));
        items.add(new Item("清空筛选，看全部", "bsrc:fclear"));
        items.add(new Item("返回书源列表", "bsrc:back"));
        return new Screen("筛选书源", items).k("bsrcFilter");
    }

    /** 文章链接的短指纹：给列表项带上，列表刷新后还能认出是哪一篇 */
    public static String urlTag(String url) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] dg = md.digest((url == null ? "" : url).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", dg[i]));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(Math.abs((url == null ? "" : url).hashCode()));
        }
    }

    /** 屏上记的是老下标，列表可能已被后台刷新；先核对指纹，对不上就按指纹全表找 */
    public int findSubArticle(int hint, String tag) {
        if (tag == null || tag.isEmpty()) return hint;
        if (hint >= 0 && hint < subArticles.size() && tag.equals(urlTag(subArticles.get(hint)[1]))) return hint;
        for (int i = 0; i < subArticles.size(); i++) {
            if (tag.equals(urlTag(subArticles.get(i)[1]))) return i;
        }
        return -1;
    }

    /** 打开单源日志：先出一屏占位，日志在后台读完再替换，别让主线程去啃 1MB 文件 */
    public void openBsrcLog(final int i) {
        final String name = (nb != null && nb.nbSources != null
                && i >= 0 && i < nb.nbSources.size())
                ? nb.nbSources.get(i).optString("bookSourceName", "") : "";
        List<Item> ph = new ArrayList<Item>();
        ph.add(new Item("（正在读日志…）"));
        host.pushScreen(new Screen("源日志　" + name, ph).k("bsrcLog"));
        host.speak("正在读这个源的日志");
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                final Screen real = bsrcLogScreen(i);
                host.ui().post(new Runnable() { @Override public void run() {
                    Screen t = host.topScreen();
                    if (t == null || !"bsrcLog".equals(t.key)) return;   // 用户已经退出去了，别动他的栈
                    host.replaceTop(real);
                    host.renderNow();
                } });
            }
        });
    }

    /** 单个源的调试日志：从全局日志里挑出提到这个源名或地址的行 */
    public Screen bsrcLogScreen(int i) {
        List<Item> items = new ArrayList<Item>();
        if (i < 0 || i >= nb.nbSources.size()) return new Screen("书源日志", items);
        JSONObject o = nb.nbSources.get(i);
        String name = o.optString("bookSourceName", "");
        String url = o.optString("bookSourceUrl", "");
        List<String> hit = new ArrayList<String>();
        try {
            java.io.File f = new java.io.File(host.dataDir(), "无障碍日志.txt");
            if (f.isFile()) {
                List<String> all = new ArrayList<String>();
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains(name) || (!url.isEmpty() && line.contains(url))) all.add(line);
                }
                br.close();
                int from = Math.max(0, all.size() - 40);
                for (int k = from; k < all.size(); k++) hit.add(all.get(k));
            }
        } catch (Throwable t) {
            hit.add("（日志读不出来：" + t.getMessage() + "）");
        }
        items.add(new Item("源：" + name));
        if (hit.isEmpty()) {
            items.add(new Item("（这个源还没有记录，先点「校验这个书源」跑一次再看）"));
        } else {
            items.add(new Item("最近 " + hit.size() + " 条："));
            for (String h : hit) {
                String t = h.replace("  ", " ").trim();
                if (t.length() > 160) t = t.substring(0, 160) + "…";
                items.add(new Item(t));
            }
        }
        items.add(new Item("回书源详情", "bsrc:detail:" + i));
        return new Screen("源日志　" + name, items).k("bsrcLog");
    }

    /** 复制一个源：字段全拷一份，名字加「副本」，单独存成文件 */
    public void bsrcCopy(int i) {
        try {
            if (i < 0 || i >= nb.nbSources.size()) return;
            JSONObject src = nb.nbSources.get(i);
            JSONObject copy = new JSONObject(src.toString());
            String name = copy.optString("bookSourceName", "未命名");
            copy.put("bookSourceName", name + " 副本");
            File dir = new File(host.bookSrcDir());
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "副本-" + host.stamp() + ".json");
            org.json.JSONArray arr = new org.json.JSONArray();
            arr.put(copy);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            fo.write(arr.toString(2).getBytes("UTF-8"));
            fo.close();
            nb.loadBookSources();
            host.speak("已复制成「" + name + " 副本」，规则跟原来源一样，改的时候注意别混");
        } catch (Exception e) {
            host.speak("复制失败");
            host.log("书源", "复制失败：" + e.getMessage());
        }
    }

    /** 新建一个空书源：只填名称和地址，规则后续照 Legado 的 JSON 补 */
    public void bsrcNewEmpty(String name, String url) {
        try {
            if (name == null || name.trim().isEmpty()) { host.speak("名称不能为空"); return; }
            JSONObject o = new JSONObject();
            o.put("bookSourceName", name.trim());
            o.put("bookSourceUrl", url == null ? "" : url.trim());
            o.put("bookSourceGroup", "");
            o.put("bookSourceType", 0);
            o.put("enabled", true);
            o.put("weight", 0);
            File dir = new File(host.bookSrcDir());
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "手工-" + host.stamp() + ".json");
            org.json.JSONArray arr = new org.json.JSONArray();
            arr.put(o);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            fo.write(arr.toString(2).getBytes("UTF-8"));
            fo.close();
            nb.loadBookSources();
            host.speak("已新建「" + name.trim() + "」，规则还没填，先按这个源的 JSON 结构往里补");
        } catch (Exception e) {
            host.speak("新建失败");
            host.log("书源", "新建失败：" + e.getMessage());
        }
    }

    /** 只导出这一个源，方便单独分享 */
    public void bsrcExportOne(int i) {
        try {
            if (i < 0 || i >= nb.nbSources.size()) return;
            JSONObject o = nb.nbSources.get(i);
            String name = o.optString("bookSourceName", "未命名").replaceAll("[\\\\/:*?\"<>|]", "_");
            // 导出是给用户拿走的，落公有 /sdcard/盲界阅读导出/书源（没权限时 App 会退回私有）
            File dir = new File(com.mangjie.reader.App.exportDir(), "书源");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "单源-" + name + "-" + host.stamp() + ".json");
            org.json.JSONArray arr = new org.json.JSONArray();
            arr.put(o);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            fo.write(arr.toString(2).getBytes("UTF-8"));
            fo.close();
            host.speak("已导出这一个源，文件在 " + out.getAbsolutePath());
        } catch (Exception e) {
            host.speak("导出失败");
            host.log("书源", "单源导出失败：" + e.getMessage());
        }
    }

    public void bsrcDelete(int i) {
        try {
            if (i < 0 || i >= nb.nbSources.size() || i >= nb.nbSourceFiles.size()) return;
            File f = nb.nbSourceFiles.get(i);
            JSONObject o = nb.nbSources.get(i);
            List<JSONObject> sameFile = new ArrayList<>();
            for (int k = 0; k < nb.nbSources.size(); k++) {
                if (nb.nbSourceFiles.get(k).getAbsolutePath().equals(f.getAbsolutePath())) sameFile.add(nb.nbSources.get(k));
            }
            sameFile.remove(o);
            if (sameFile.isEmpty()) {
                f.delete();
                host.speak("已删除这个书源文件");
            } else {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (JSONObject x : sameFile) arr.put(x);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                fo.write(arr.toString(2).getBytes("UTF-8"));
                fo.close();
                host.speak("已从书源文件里删掉这一项");
            }
            nb.loadBookSources();
        } catch (Exception e) {
            host.speak("删除失败");
            host.log("听书", "删除失败：" + e.getMessage());
        }
    }

    public void bsrcValidate(final int si, final String key) {
        if (si < 0 || si >= nb.nbSources.size()) return;
        JSONObject src = nb.nbSources.get(si);
        final String name = src.optString("bookSourceName", "未命名");
        host.speak("开始校验 " + name);
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            final List<String> report = new ArrayList<>();
            int score = 0;
            try {
                JSONObject rs = rules.sub(src, "ruleSearch");
                if (rules.ruleStr(src, "searchUrl").isEmpty()) report.add("✖ 搜索：没填 searchUrl");
                else if (rs == null) report.add("✖ 搜索：没有 ruleSearch 规则");
                else {
                    List<String[]> hits = nb.nbSearchInSource(si, key);
                    if (hits.isEmpty()) report.add("✖ 搜索：没搜出结果（关键词 " + key + "）");
                    else {
                        report.add("✔ 搜索：" + hits.size() + " 条，例：" + hits.get(0)[0]);
                        score++;
                        String bookUrl = hits.get(0)[2];
                        String bookName = hits.get(0)[0];
                        JSONObject rc = rules.sub(src, "ruleToc");
                        if (rc == null) rc = rules.sub(src, "ruleChapterList");
                        if (rc == null) report.add("✖ 目录：没有 ruleToc 规则（有的源靠 bookUrl 直接当目录页）");
                        else {
                            try {
                                String base = rules.ruleStr(src, "bookSourceUrl");
                                String tocUrl = bookUrl;
                                String tocHtml = req(tocUrl, base);
                                tocHtml = rules.applyInit(rules.sub(src, "ruleBookInfo"), base, tocHtml);
                                if (tocHtml == null) report.add("✖ 目录：页面没打开");
                                else {
                                    String lr = rules.ruleStr(rc, "chapterList");
                                    if (lr.isEmpty()) lr = rules.ruleStr(rc, "list");
                                    List<String> names = rules.ruleAll(rules.ruleStr(rc, "name").isEmpty() ? "text" : rules.ruleStr(rc, "name"), base, tocHtml);
                                    List<String> urls = rules.ruleAll(rules.ruleStr(rc, "url").isEmpty() ? "href" : rules.ruleStr(rc, "url"), base, tocHtml);
                                    if (names.isEmpty() || urls.isEmpty()) report.add("✖ 目录：章名或链接规则没命中");
                                    else {
                                        report.add("✔ 目录：" + Math.min(names.size(), urls.size()) + " 章，首章：" + names.get(0));
                                        score++;
                                        String chUrl = urls.get(0);
                                        String body = nb.nbFetchChapterText(src, chUrl);
                                        int len = body == null ? 0 : body.trim().length();
                                        if (len < 50) report.add("✖ 正文：只拿到 " + len + " 字");
                                        else { report.add("✔ 正文：" + len + " 字"); score++; }
                                    }
                                }
                            } catch (Exception e) {
                                report.add("✖ 目录：" + e.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                report.add("✖ 异常：" + e.getMessage());
            }
            report.add("—— 得分 " + score + "/3");
            final List<String> rep = report;
            host.ui().post(new Runnable() { @Override public void run() {
                List<Item> items = new ArrayList<>();
                for (String line : rep) items.add(new Item(line));
                items.add(new Item("重新校验", "bsrc:test:" + si));
                host.pushScreen(new Screen("校验　" + name, items));
                host.renderNow();
                host.speak(rep.get(rep.size() - 1).replace("—— ", "校验完成，"));
                host.log("书源校验", name + " 关键词=" + key + " " + rep);
            } });
        } });
    }

    public Screen bsrcDetailScreen(int i) {
        List<Item> items = new ArrayList<>();
        // 设备日志里崩过两次：书源还没载入完就点进详情，nb 或 nbSources 为空直接 NPE
        if (nb == null || nb.nbSources == null) {
            items.add(new Item("书源还没载入完，返回列表再进一次"));
            items.add(new Item("返回书源列表", "nb:cat"));
            host.speak("书源还没载入完");
            return new Screen("书源", items);
        }
        if (i < 0 || i >= nb.nbSources.size()) {
            items.add(new Item("这本书源已经不在列表里了，返回重进"));
            items.add(new Item("返回书源列表", "nb:cat"));
            return new Screen("书源", items);
        }
        JSONObject o = nb.nbSources.get(i);
        String name = o.optString("bookSourceName", "未命名");
        String base = o.optString("bookSourceUrl", "");
        items.add(new Item("名称：" + name));
        items.add(new Item("地址：" + (base.isEmpty() ? "（未填）" : base)));
        items.add(new Item((nb.srcEnabled(o) ? "当前：已启用" : "当前：已关闭") + "，点一下切换", "bsrc:toggle:" + i));
        String grp = o.optString("bookSourceGroup", "").trim();
        items.add(new Item("分组：" + (grp.isEmpty() ? "未分组" : grp) + "，点一下改", "bsrc:grpd:" + i));
        String chkWord = nb.getSourceField(i, "ruleSearch.checkKeyWord").trim();
        if (chkWord.isEmpty()) chkWord = nb.getSourceField(i, "checkKeyword").trim();
        items.add(new Item("校验关键词：" + (chkWord.isEmpty() ? "跟全局走" : chkWord) + "，点一下改",
                "bsrc:chkword:" + i));
        items.add(new Item("（有的站搜「我的」搜不到，给这个源单独指定一个词）", "none"));
        items.add(new Item("启用发现：" + (nb.exploreOn(o) ? "开" : "关")
                + (nb.hasExploreUrl(o) ? "" : "（这个源没写 exploreUrl，发现页不列它）")
                + "，点一下切换", "bsrc:exon:" + i));
        items.add(new Item("填搜索和正文规则", "bsrc:edit:" + i));
        items.add(new Item("校验这个书源（搜索/目录/正文）", "bsrc:test:" + i));
        items.add(new Item("查看这个源的 JSON 原文", "bsrc:json:" + i));
        items.add(new Item("删除这个书源", "bsrc:del:" + i));
        items.add(new Item("返回书源列表", "nb:cat"));
        return new Screen(name, items).k("bsrcDetail");
    }

    /** 一个源的全部字段摊开看，一行一段，方便核对规则写错在哪 */
    public Screen bsrcJsonScreen(int i) {
        List<Item> items = new ArrayList<>();
        if (i < 0 || i >= nb.nbSources.size()) return new Screen("书源 JSON", items);
        String json;
        try { json = nb.nbSources.get(i).toString(2); }
        catch (Throwable t) { json = nb.nbSources.get(i).toString(); }
        int step = 220;
        for (int p = 0; p < json.length(); p += step) {
            items.add(new Item(json.substring(p, Math.min(json.length(), p + step))));
        }
        return new Screen("书源 JSON　" + nb.nbSources.get(i).optString("bookSourceName", ""), items).k("bsrcJson");
    }

    // ---------- 新建书源：空源 / 通用兜底 / 地址嗅探 ----------

    private String pendingName = "";
    private String pendingUrl = "";
    private SourceSniffer.Sniff pendingSniff = null;

    public void setPendingNew(String name, String url) {
        pendingName = name == null ? "" : name.trim();
        pendingUrl = url == null ? "" : url.trim();
    }

    /** 输入「名称,网址」之后先到这一页：怎么建由用户挑 */
    public Screen bsrcNewWayScreen() {
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("名称：" + pendingName + "　地址：" + pendingUrl, "none"));
        items.add(new Item("① 只建空源，规则自己填", "bsrc:neway:empty"));
        items.add(new Item("② 套通用兜底规则（能跑，但八成不准，靠校验再改）", "bsrc:neway:generic"));
        items.add(new Item("③ 抓这个站嗅探搜索入口，按嗅探结果建", "bsrc:neway:sniff"));
        items.add(new Item("说明：②③ 都是省从零敲规则那一步，没有一个能保证准；"
                + "建完先跑「校验书源」，再进详情页逐项改。", "none"));
        return new Screen("新建书源", items).k("bsrcNewWay");
    }

    public void newEmptyNow() {
        bsrcNewEmpty(pendingName, pendingUrl);
        host.speak("已建空源：" + pendingName);
    }

    public void newGenericNow() {
        if (pendingUrl.isEmpty()) { host.speak("地址是空的"); return; }
        JSONObject o = SourceSniffer.genericSource(pendingName, SourceSniffer.normalize(pendingUrl));
        boolean ok = createFromJson(o);
        host.speak(ok ? "已按通用兜底建源，先跑一次校验" : "建源失败，看日志");
    }

    /** 后台嗅探，出结果换页 */
    public void sniffNow() {
        if (pendingUrl.isEmpty()) { host.speak("地址是空的"); return; }
        host.speak("正在抓这个站，稍等");
        final String name = pendingName;
        new Thread(new Runnable() {
            @Override public void run() {
                SourceSniffer.Net net = new SourceSniffer.Net() {
                    @Override public String get(String url) { return req(url, ""); }
                    @Override public String getT(String url, int timeoutMs) { return reqT(url, "", timeoutMs); }
                };
                final SourceSniffer.Sniff sn = SourceSniffer.sniff(pendingUrl, net);
                pendingSniff = sn;
                host.log("嗅探", name + "：" + sn.text()
                        + (sn.tried.isEmpty() ? "" : "；试过 " + sn.tried.size() + " 条路径"));
                host.ui().post(new Runnable() { @Override public void run() {
                    host.replaceTop(sniffResultScreen());
                    host.speak(sn.ok() ? "嗅探到了搜索入口" : "没嗅探到搜索入口");
                } });
            }
        }, "src-sniff").start();
    }

    public Screen sniffResultScreen() {
        List<Item> items = new ArrayList<Item>();
        SourceSniffer.Sniff sn = pendingSniff;
        if (sn == null) {
            items.add(new Item("（还没嗅探）"));
            return new Screen("嗅探结果", items).k("bsrcSniff");
        }
        items.add(new Item(sn.text(), "none"));
        if (!sn.note.isEmpty()) items.add(new Item(sn.note, "none"));
        if (sn.ok()) {
            items.add(new Item("用这个搜索地址套通用规则建源", "bsrc:sniffbuild"));
        }
        items.add(new Item("拿这个地址只建空源（搜索地址填进去）", "bsrc:sniffempty"));
        items.add(new Item("再嗅一次", "bsrc:sniffagain"));
        return new Screen("嗅探结果　" + pendingName, items).k("bsrcSniff");
    }

    public void buildFromSniff(boolean withGeneric) {
        SourceSniffer.Sniff sn = pendingSniff;
        if (sn == null || !sn.ok()) { host.speak("没有可用的搜索地址"); return; }
        JSONObject o = withGeneric
                ? SourceSniffer.genericSource(pendingName, sn.base, sn.searchUrl)
                : SourceSniffer.genericSource(pendingName, sn.base);
        if (!withGeneric) {
            try {
                o.put("searchUrl", sn.searchUrl);
                JSONObject rs = o.optJSONObject("ruleSearch");
                if (rs == null) { rs = new JSONObject(); o.put("ruleSearch", rs); }
                rs.put("bookList", "@css:ul li");
                rs.put("name", "tag.a.0@text");
                rs.put("bookUrl", "tag.a.0@href");
            } catch (Throwable ignored) { }
        }
        boolean ok = createFromJson(o);
        host.speak(ok ? "已建源，先跑一次校验" : "建源失败，看日志");
    }

    /** 按一个 JSON 对象建源，落盘到 书源目录/自建 */
    public boolean createFromJson(JSONObject o) {
        try {
            // 书源目录只扫顶层，不放子目录（放子目录读不到）
            java.io.File dir = new java.io.File(host.bookSrcDir());
            if (!dir.exists()) dir.mkdirs();
            String nm = o.optString("bookSourceName", "自建源");
            String safe = nm.replaceAll("[\\\\/:*?\"<>|]", "_");
            java.io.File f = new java.io.File(dir, "新建-" + safe + "-"
                    + System.currentTimeMillis() + ".json");
            org.json.JSONArray arr = new org.json.JSONArray();
            arr.put(o);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(arr.toString(2).getBytes("UTF-8"));
            fo.close();
            nb.loadBookSources();
            host.renderNow();
            host.log("书源", "已新建 " + f.getAbsolutePath());
            return true;
        } catch (Throwable e) {
            host.log("书源", "新建失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /** 批量冒烟校验：挨个抓源的首页，看站还在不在；9 个并发，结果把有问题的排前面 */    /** 批量冒烟校验：挨个抓源的首页，看站还在不在；9 个并发，结果把有问题的排前面 */
    public void bsrcSmokeCheck(final List<Integer> idxs) {
        if (idxs == null || idxs.isEmpty()) { host.speak("没有要检查的源"); return; }
        final List<Integer> run = new ArrayList<Integer>();
        for (int i = 0; i < idxs.size() && i < 120; i++) run.add(idxs.get(i));   // 一批最多 120 个：9 并发 × 10 秒，再多就要等半小时
        host.speak(idxs.size() > run.size()
                ? ("共 " + idxs.size() + " 个，先查前 " + run.size() + " 个")
                : ("开始检查 " + run.size() + " 个源，请稍等"));
        com.mangjie.reader.tools.TaskProgress.start("批量校验书源", run.size());
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                final List<String[]> out = new ArrayList<>();
                final int[] next = new int[]{0};
                final int[] done = new int[]{0};
                final Object lock = new Object();
                int threads = Math.min(9, Math.max(1, run.size()));
                java.util.List<Thread> ts = new ArrayList<Thread>();
                for (int t = 0; t < threads; t++) {
                    Thread th = new Thread(new Runnable() {
                        @Override public void run() {
                            while (true) {
                                int k;
                                synchronized (lock) {
                                    k = next[0]++;
                                    if (k >= run.size()) return;
                                }
                                int si = run.get(k);
                                String name = "未命名", url = "", state = "bad", detail = "";
                                try {
                                    JSONObject o = nb.nbSources.get(si);
                                    name = o.optString("bookSourceName", "未命名");
                                    url = rules.ruleStr(o, "bookSourceUrl");
                                    if (url.isEmpty()) { detail = "地址没填"; }
                                    else {
                                        String html = reqT(url, url, 10000);
                                        if (html == null) detail = "抓不到，站点拦了或超时";
                                        else if (html.length() < 50) detail = "取回 " + html.length() + " 字，太少";
                                        else { state = "ok"; detail = "能通，" + html.length() + " 字"; }
                                    }
                                } catch (Throwable e) {
                                    detail = "出错：" + e.getMessage();
                                }
                                synchronized (lock) {
                                    out.add(new String[]{name, state, detail});
                                    done[0]++;
                                    if (done[0] % 20 == 0) {
                                        com.mangjie.reader.tools.TaskProgress.update(done[0]);
                                        host.speakQueued("已检查 " + done[0] + " 个");
                                    }
                                }
                            }
                        }
                    });
                    th.setDaemon(true);
                    ts.add(th);
                    th.start();
                }
                for (Thread th : ts) { try { th.join(); } catch (Throwable ignored) {} }
                com.mangjie.reader.tools.TaskProgress.finish();
                host.ui().post(new Runnable() { @Override public void run() {
                    int ok = 0, bad = 0;
                    for (String[] r : out) if ("ok".equals(r[1])) ok++; else bad++;
                    List<Item> items = new ArrayList<Item>();
                    items.add(new Item("检查完 " + out.size() + " 个：能通 " + ok + "，有问题 " + bad));
                    for (int pass = 0; pass < 2; pass++) {     // 先列有问题的，再列能通的
                        for (String[] r : out) {
                            boolean isBad = !"ok".equals(r[1]);
                            if ((pass == 0) != isBad) continue;
                            items.add(new Item((isBad ? "✗ " : "✓ ") + r[0] + "　" + r[2]));
                        }
                    }
                    host.pushScreen(new Screen("批量校验　" + out.size() + " 个源", items));
                    host.speak(bad == 0 ? "全部能通" : ("有 " + bad + " 个源有问题，列在最前面"));
                } });
            }
        });
    }

    public void loadSubSources() {
        subSources = new ArrayList<>();
        subSourceFiles = new ArrayList<>();
        int skipped = 0;
        try {
            File dir = new File(host.subSrcDir());
            if (!dir.exists()) dir.mkdirs();
            File[] fs = dir.listFiles();
            if (fs != null) {
                Arrays.sort(fs, new Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
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
                                if (o == null) continue;
                                if (isBookSourceObj(o)) { skipped++; continue; }
                                subSources.add(o); subSourceFiles.add(f);
                            }
                        } else {
                            JSONObject o = new JSONObject(t);
                            if (o.has("rssSourceName") || o.has("subscriptionName") || o.has("name")) {
                                subSources.add(o); subSourceFiles.add(f);
                            } else if (o.has("sources")) {
                                org.json.JSONArray arr = o.optJSONArray("sources");
                                for (int i = 0; arr != null && i < arr.length(); i++) {
                                    JSONObject x = arr.optJSONObject(i);
                                    if (x == null) continue;
                                    if (isBookSourceObj(x)) { skipped++; continue; }
                                    subSources.add(x); subSourceFiles.add(f);
                                }
                            }
                        }
                    } catch (Exception e) {
                        host.log("订阅", "订阅源解析失败 " + f.getName() + "：" + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            host.log("订阅", "读订阅源异常：" + e);
        }
        if (skipped > 0) host.log("订阅", "跳过 " + skipped + " 个不是订阅源的条目（书源混在订阅文件里）");
        host.log("订阅", "已载入订阅源 " + subSources.size() + " 个");
        if (!subSources.isEmpty()) {
            JSONObject f0 = subSources.get(0);
            StringBuilder kb = new StringBuilder();
            java.util.Iterator<String> ii = f0.keys();
            while (ii.hasNext() && kb.length() < 300) kb.append(ii.next()).append(' ');
            host.log("订阅", "首个源字段 " + kb + " 名称=" + subName(f0));
        }
    }

    public void openSingleSource(final JSONObject src) {
        final String title = subName(src);
        final String base = srcBaseUrl(src);
        host.log("订阅", "单页源 " + title + " 网址=" + base);
        // 单网页源交给系统浏览器：原样看网页，比抽正文更忠实
        if (host.openUrl(base)) {
            host.speak("用浏览器打开 " + title);
            return;
        }
        host.speak("没找到浏览器，改在这里抽正文读");
        host.speak("正在加载 " + title);
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                String body = "";
                try {
                    String html = host.httpText(base);
                    html = tidyRssHtml(src, html, base);
                    if (html != null) {
                        String cRule = rules.ruleStr(src, "ruleContent");
                        if (!cRule.isEmpty()) {
                            List<String> vals = rules.ruleAll(cRule, base, html);
                            StringBuilder sb = new StringBuilder();
                            for (String v : vals) sb.append(v).append("\n");
                            body = sb.toString();
                        }
                        if (body.trim().isEmpty()) body = org.jsoup.Jsoup.parse(html).text();
                        body = body.replace("\r", "");
                    }
                } catch (Exception e) {
                    host.log("订阅", "单页源取正文失败：" + e.getMessage());
                }
                final String text = body;
                host.ui().post(new Runnable() { @Override public void run() { showSubArticle(title, text); } });
            }
        });
    }

    public String srcBaseUrl(JSONObject o) {
        String u = rules.ruleStr(o, "sourceUrl");
        if (u.isEmpty()) u = rules.ruleStr(o, "rssSourceUrl");
        if (u.isEmpty()) u = rules.ruleStr(o, "subscriptionUrl");
        return u;
    }

    public String subName(JSONObject o) {
        // 开源阅读的订阅源字段叫 sourceName，其次兼容几种旧写法
        String[] keys = {"sourceName", "rssSourceName", "subscriptionName", "name", "bookSourceName", "title"};
        String n = "";
        for (String kk : keys) {
            String v = o.optString(kk, "").trim();
            if (!v.isEmpty()) { n = v; break; }
        }
        if (n.isEmpty()) {
            String u = o.optString("sourceUrl", "");
            if (!u.isEmpty()) {
                try { n = new java.net.URL(u).getHost(); } catch (Exception e) { n = u.length() > 20 ? u.substring(0, 20) : u; }
            }
        }
        if (n.isEmpty()) n = "未命名";
        return n;
    }

    public boolean subEnabled(JSONObject o) {
        return host.prefBool("subOn_" + subName(o), true);
    }

    public Screen subSourceScreen() {
        loadSubSources();
        List<Item> items = new ArrayList<>();
        if (subSources.isEmpty()) {
            items.add(new Item("（空）"));
            return new Screen("订阅源管理", items).k("subsrc");
        }
        for (int i = 0; i < subSources.size(); i++) {
            items.add(new Item(subName(subSources.get(i)) + "（" + (subEnabled(subSources.get(i)) ? "开" : "关") + "）",
                    "sub:detail:" + i));
        }
        return new Screen("订阅源管理　" + subSources.size() + " 个", items).k("subsrc");
    }

    public Screen subDetailScreen(int i) {
        List<Item> items = new ArrayList<>();
        if (i < 0 || i >= subSources.size()) return new Screen("订阅源", items);
        JSONObject o = subSources.get(i);
        String url = o.optString("rssSourceUrl", "");
        if (url.isEmpty()) url = o.optString("subscriptionUrl", "");
        items.add(new Item("名称：" + subName(o)));
        items.add(new Item("地址：" + (url.isEmpty() ? "（未填）" : url)));
        items.add(new Item((subEnabled(o) ? "当前：已启用" : "当前：已关闭") + "，点一下切换", "sub:toggle:" + i));
        items.add(new Item("打开这个源看内容", "sub:open:" + i));
        items.add(new Item("删除这个订阅源", "sub:del:" + i));
        items.add(new Item("返回订阅源列表", "sub:list"));
        return new Screen(subName(o), items).k("subDetail");
    }

    public Screen subPickScreen(String path) {
        List<Item> items = new ArrayList<>();
        File dir = new File(path);
        if (!dir.exists()) dir.mkdirs();
        File[] fs = dir.listFiles();
        if (fs == null) {
            items.add(new Item("无法访问此目录"));
            return new Screen("选择订阅源文件", items);
        }
        Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        int shows = 0;
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                items.add(new Item(f.getName(), "sub:pickdir:" + f.getAbsolutePath()));
                shows++;
            } else {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".json") || n.endsWith(".txt")) {
                    items.add(new Item(f.getName(), "sub:imp:" + f.getAbsolutePath()));
                    shows++;
                }
            }
            if (shows >= 300) break;
        }
        if (items.isEmpty()) items.add(new Item("（这个目录里没有 json / txt）"));
        Screen s = new Screen("选择订阅源文件", items);
        s.fileMode = true;
        this.curDir = path;
        return s;
    }

    private int subImportText(String text, String hint) {
        int added = 0;
        try {
            File dir = new File(host.subSrcDir());
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "导入-" + host.stamp() + ".json");
            String t = text.trim();
            if (t.startsWith("[")) {
                org.json.JSONArray arr = new org.json.JSONArray(t);
                added = arr.length();
            } else {
                JSONObject o = new JSONObject(t);
                if (o.has("rssSourceName") || o.has("subscriptionName") || o.has("name") || o.has("sources")) added = 1;
            }
            if (added == 0) return 0;
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            fo.write(t.getBytes("UTF-8"));
            fo.close();
            host.log("订阅", "导入订阅源 " + out.getName() + "，共 " + added + " 个");
            loadSubSources();
        } catch (Exception e) {
            host.log("订阅", "导入失败：" + e.getMessage());
            return 0;
        }
        return added;
    }

    public void subImportFile(String path) {
        File f = new File(path);
        String text = host.readTextFile(f);
        if (text == null || text.trim().isEmpty()) { host.speak("这个文件读不出来"); return; }
        int added = subImportText(text, f.getName());
        if (added == 0) host.speak("没导入成功，文件不是订阅源格式");
        else host.speak("已导入 " + added + " 个订阅源");
        host.replaceTop(subSourceScreen());
        host.renderNow();
    }

    public void subImportUrl(final String url) {
        host.speak("正在下载订阅源");
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                final String text = host.httpText(url.trim());
                host.ui().post(new Runnable() { @Override public void run() {
                    if (text == null || text.trim().isEmpty()) { host.speak("下载失败，地址不可达"); return; }
                    int added = subImportText(text, "url");
                    if (added == 0) { host.speak("下载回来的不是订阅源格式"); return; }
                    host.speak("已导入 " + added + " 个订阅源");
                    Screen t2 = host.topScreen();
                    if (t2 == null || !"subsrc".equals(t2.key)) return;   // 同上，先核栈顶
                    host.replaceTop(subSourceScreen());
                    host.renderNow();
                } });
            }
        });
    }

    public void subExport() {
        try {
            File dir = new File(host.subSrcDir());
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "导出-" + host.stamp() + ".json");
            org.json.JSONArray arr = new org.json.JSONArray();
            for (JSONObject o : subSources) arr.put(o);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            fo.write(arr.toString(2).getBytes("UTF-8"));
            fo.close();
            host.speak("已导出 " + subSources.size() + " 个订阅源到 订阅源目录下 " + out.getName());
        } catch (Exception e) {
            host.speak("导出失败");
            host.log("订阅", "导出失败：" + e.getMessage());
        }
    }

    public void subDelete(int i) {
        try {
            if (i < 0 || i >= subSources.size() || i >= subSourceFiles.size()) return;
            File f = subSourceFiles.get(i);
            JSONObject o = subSources.get(i);
            List<JSONObject> same = new ArrayList<>();
            for (int k = 0; k < subSources.size(); k++) {
                if (subSourceFiles.get(k).getAbsolutePath().equals(f.getAbsolutePath())) same.add(subSources.get(k));
            }
            same.remove(o);
            if (same.isEmpty()) {
                f.delete();
                host.speak("已删除这个订阅源文件");
            } else {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (JSONObject x : same) arr.put(x);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                fo.write(arr.toString(2).getBytes("UTF-8"));
                fo.close();
                host.speak("已从订阅源文件里删掉这一项");
            }
            loadSubSources();
        } catch (Exception e) {
            host.speak("删除失败");
            host.log("订阅", "删除失败：" + e.getMessage());
        }
    }

    public void subOpenArticles(final int idx) {
        loadSubSources();
        if (idx < 0 || idx >= subSources.size()) return;
        JSONObject src = subSources.get(idx);
        bindSrc(src);
        subArticleSrc = idx;
        // 单网页源：整页就是一篇
        if (src.optBoolean("singleUrl", false)) { openSingleSource(src); return; }
        // 有分类表就先出分类页（对齐 Legado 的 sortUrl）
        List<String[]> sorts = subSortUrls(src);
        if (!sorts.isEmpty()) {
            List<Item> items = new ArrayList<>();
            // 对齐 Legado：Rss.getArticlesAwait 的地址永远取分类表里的 sortUrl，分类表为空才退回 sourceUrl。
            // 很多源的 sourceUrl 只是网关根域名（例：轻松一刻的 https://gw.m.163.com），直接请求必然 404，
            // 所以这里的"全部"必须指第一个分类的真实地址，不能指 sourceUrl。
            String first = sorts.get(0)[0];
            if (first.startsWith("http")) first = subName(src);
            items.add(new Item("全部（" + first + "）", "nb:subcat:" + idx + ":0"));
            for (int i = 0; i < sorts.size(); i++)
                items.add(new Item(sorts.get(i)[0], "nb:subcat:" + idx + ":" + (i + 1)));
            host.pushScreen(new Screen(subName(src) + " 分类", items));
            host.speak("分 " + (sorts.size() + 1) + " 个分类");
            return;
        }
        openArticlesOf(idx, srcBaseUrl(src));
    }

    /** 打开某个分类（0 表示默认入口，分类表非空时即第一个分类；对齐 Legado 不用 sourceUrl 取列表） */
    public void subOpenCat(int idx, int k) {
        loadSubSources();
        if (idx < 0 || idx >= subSources.size()) return;
        JSONObject src = subSources.get(idx);
        bindSrc(src);
        String url = srcBaseUrl(src);
        List<String[]> sorts = subSortUrls(src);
        if (k > 0) {
            if (k - 1 >= sorts.size()) return;
            url = sorts.get(k - 1)[1];
            host.speak("分类：" + sorts.get(k - 1)[0]);
        } else if (!sorts.isEmpty() && !sorts.get(0)[1].isEmpty()) {
            url = sorts.get(0)[1];
            host.log("订阅", "默认入口用第一个分类 " + sorts.get(0)[0] + " 地址=" + url);
        }
        openArticlesOf(idx, url);
    }

    /** 抓某个地址的文章列表；ruleNextPage 有规则就往后翻，最多 5 页 */
    public void openArticlesOf(final int idx, final String listUrl) {
        loadSubSources();
        if (idx < 0 || idx >= subSources.size()) return;
        final JSONObject src = subSources.get(idx);
        subArticleSrc = idx;
        host.speak("正在加载 " + subName(src));
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                final List<String[]> arts = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                try {
                    String curUrl = (listUrl == null || listUrl.isEmpty()) ? srcBaseUrl(src) : listUrl;
                    // 对齐 Legado 的 setBaseUrl(sortUrl)：规则解析的 base 是本次列表地址，不是 sourceUrl
                    String base = curUrl;
                    String npRule = rules.ruleStr(src, "ruleNextPage");
                    for (int page = 0; page < 5; page++) {
                        String html = req(curUrl, base);
                        if (html == null) break;
                        String useHtml = tidyRssHtml(src, html, curUrl);
                        String listRule = rules.ruleStr(src, "ruleArticles");
                        if (listRule.isEmpty()) listRule = rules.ruleStr(src, "ruleArticleList");
                        if (!listRule.isEmpty() && useHtml != null) {
                            List<String> blocks = rules.ruleAll(listRule, base, useHtml);
                            if (blocks.size() == 1 && blocks.get(0).contains("<")) useHtml = blocks.get(0);
                        }
                        List<String> titles = rules.ruleAll(rules.ruleStr(src, "ruleTitle"), base, useHtml);
                        if (titles.isEmpty()) titles = rules.ruleAll(rules.ruleStr(src, "ruleArticleTitle"), base, useHtml);
                        List<String> links = rules.ruleAll(rules.ruleStr(src, "ruleLink"), base, useHtml);
                        if (links.isEmpty()) links = rules.ruleAll(rules.ruleStr(src, "ruleArticleUrl"), base, useHtml);
                        List<String> dates = rules.ruleAll(rules.ruleStr(src, "rulePubDate"), base, useHtml);
                        int n = Math.min(titles.size(), links.size());
                        int added = 0;
                        for (int i = 0; i < n && added < 100; i++) {
                            String t = titles.get(i).trim();
                            String u = links.get(i);
                            if (t.isEmpty() || u == null || u.trim().isEmpty()) continue;
                            String abs = absLink(curUrl, u);
                            if (abs.isEmpty() || !seen.add(abs)) continue;
                            arts.add(new String[]{t, abs, i < dates.size() ? dates.get(i) : "", String.valueOf(idx)});
                            added++;
                        }
                        host.log("订阅", "第" + (page + 1) + "页 取到 " + added + " 篇 地址=" + curUrl);
                        String next = "";
                        if (!npRule.isEmpty()) {
                            if ("PAGE".equalsIgnoreCase(npRule)) {
                                if (curUrl.contains("{page}"))
                                    next = curUrl.replace("{page}", String.valueOf(page + 2));
                            } else {
                                List<String> vals = rules.ruleAll(npRule, base, html);
                                if (!vals.isEmpty()) next = absLink(curUrl, vals.get(0).trim());
                            }
                        }
                        if (next.isEmpty() || next.equals(curUrl) || next.contains("javascript:")) break;
                        curUrl = next;
                    }
                } catch (Exception e) {
                    host.log("订阅", "取文章失败：" + e.getMessage());
                }
                final String noRule;
                if (arts.isEmpty()) {
                    String lr = rules.ruleStr(src, "ruleArticles");
                    if (lr.trim().isEmpty()) lr = rules.ruleStr(src, "ruleArticleList");
                    if (lr.trim().isEmpty()) {
                        // 首页型源：本来就没有列表规则，别硬造一条指向首页的假文章
                        noRule = "这个源没有文章列表规则（首页型），取不到文章";
                    } else {
                        noRule = "列表规则没匹配上，这条规则在这个站取不到文章";
                    }
                    host.log("订阅", noRule + "　地址=" + srcBaseUrl(src));
                } else {
                    noRule = "";
                }
                host.ui().post(new Runnable() { @Override public void run() {
                    subArticles = arts;
                    subArticleName = subName(src);
                    List<Item> items = new ArrayList<>();
                    for (int i = 0; i < arts.size(); i++) {
                        String[] a = arts.get(i);
                        boolean rd = subIsRead(a[1]);
                        items.add(new Item((rd ? "" : "未读　") + a[0]
                                + (a[2] == null || a[2].isEmpty() ? "" : "　" + a[2]), "nb:subart:" + i + ":" + urlTag(a[1])));
                    }
                    if (items.isEmpty()) items.add(new Item("（" + (noRule.isEmpty() ? "这个订阅源没取到文章，检查规则" : noRule) + "）"));
                    host.pushScreen(new Screen(subArticleName, items));
                    host.speak(arts.isEmpty() ? (noRule.isEmpty() ? "没取到文章" : noRule) : ("共 " + arts.size() + " 篇"));
                } });
            }
        });
    }

    /** 订阅源的分类表：sortUrl 里「名称::地址」，用 && 或换行分隔，支持 <js>/@js: */
    public List<String[]> subSortUrls(JSONObject src) {
        List<String[]> out = new ArrayList<>();
        String raw = src.optString("sortUrl", "").trim();
        if (raw.isEmpty()) return out;
        try {
            String low = raw.toLowerCase();
            if (low.startsWith("@js:") || low.startsWith("<js>")) {
                String js = rules.runJsPublic(raw, "");
                if (js != null && !js.trim().isEmpty()) raw = js.trim();
            }
        } catch (Throwable ignored) {}
        for (String one : raw.split("(&&|\\n)+")) {
            String t = one.trim();
            if (t.isEmpty()) continue;
            int p = t.indexOf("::");
            String name = p > 0 ? t.substring(0, p).trim() : t;
            String url = p > 0 ? t.substring(p + 2).trim() : "";
            if (url.isEmpty()) continue;
            if (name.isEmpty()) name = url;
            out.add(new String[]{name, absLink(srcBaseUrl(src), url)});
        }
        return out;
    }

    /** 相对地址补全 */
    private String absLink(String base, String u) {
        if (u == null) return "";
        String t = u.trim();
        if (t.isEmpty()) return "";
        if (t.startsWith("http://") || t.startsWith("https://")) return t;
        if (t.startsWith("//")) return "https:" + t;
        try { return new java.net.URL(new java.net.URL(base), t).toString(); } catch (Exception e) { return t; }
    }

    /** 正文白/黑名单：黑名单按选择器删，不像选择器就当关键词删整块；白名单只留命中的块 */
    /**
     * 正文里混进 HTML 源码时的最后一道护栏。
     * 源里 ruleContent 常写 @js:result / <js>result</js>，我们传进去的 result 是整页 HTML，
     * 求值出来就是源码，直接播会把 <html lang=… 这种念出来。
     */
    static String stripHtmlIfAny(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.length() < 40) return t;
        String low = t.toLowerCase(Locale.ROOT);
        boolean looksHtml = low.contains("<html") || low.contains("<!doctype") || low.contains("<body")
                || low.contains("<head") || low.contains("<div") || low.contains("<p>")
                || low.contains("<br") || low.contains("<script") || low.contains("<meta")
                || low.contains("<span") || low.contains("<ul") || low.contains("<li");
        if (!looksHtml) return text;
        String plain;
        try {
            plain = org.jsoup.Jsoup.parse(t).text();
        } catch (Throwable e) {
            plain = t.replaceAll("<[^>]*>", " ");
        }
        // 整页抽成一行太难念，按句号断行，一行一句
        StringBuilder out = new StringBuilder(plain.length() + 64);
        int start = 0;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c == '。' || c == '！' || c == '？') {
                String one = plain.substring(start, i + 1).trim();
                if (!one.isEmpty()) out.append(one).append('\n');
                start = i + 1;
            }
        }
        String rest = plain.substring(start).trim();
        if (!rest.isEmpty()) out.append(rest);
        return out.length() == 0 ? plain : out.toString();
    }

    /** 书源那套字段的对象不是订阅源，别混进订阅列表 */
    private static boolean isBookSourceObj(JSONObject o) {
        if (o == null) return false;
        return o.has("bookSourceUrl") || o.has("bookSourceName") || o.has("ruleBookContent")
                || o.has("ruleSearch") || o.has("ruleBookInfo");
    }

    private String tidyRssHtml(JSONObject src, String html, String base) {
        if (src == null || html == null) return html;
        String bl = src.optString("contentBlacklist", "").trim();
        String wl = src.optString("contentWhitelist", "").trim();
        if (bl.isEmpty() && wl.isEmpty()) return html;
        String res = html;
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html, base);
            if (!bl.isEmpty()) {
                for (String one : bl.split("[,，]")) {
                    String t = one.trim();
                    if (t.isEmpty()) continue;
                    org.jsoup.select.Elements hit = null;
                    try { hit = doc.select(t); } catch (Exception e) { hit = null; }
                    if (hit != null && !hit.isEmpty()) {
                        for (org.jsoup.nodes.Element el : hit) el.remove();
                        continue;
                    }
                    for (org.jsoup.nodes.Element el : doc.select("div,p,li,section,aside,tr"))
                        if (el.text().contains(t)) el.remove();
                }
                res = doc.html();
            }
            if (!wl.isEmpty() && bl.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String one : wl.split("[,，]")) {
                    String t = one.trim();
                    if (t.isEmpty()) continue;
                    org.jsoup.select.Elements hit = null;
                    try { hit = doc.select(t); } catch (Exception e) { hit = null; }
                    if (hit != null && !hit.isEmpty()) {
                        for (org.jsoup.nodes.Element el : hit) sb.append(el.text()).append("<br/>");
                        continue;
                    }
                    for (org.jsoup.nodes.Element el : doc.select("div,p,li,section,aside")) {
                        String tx = el.text();
                        if (tx.contains(t)) sb.append(tx).append("<br/>");
                    }
                }
                if (sb.length() > 0) res = sb.toString();
            }
        } catch (Throwable ignored) {}
        return res;
    }

    /** 订阅源分组（对齐 Legado RssSource 的 sourceGroup） */
    public String subGroupFilter() {
        String v = host.prefString("subGroup", "");
        return v == null ? "" : v;
    }

    public boolean subInGroup(JSONObject o) {
        String f = subGroupFilter();
        if (f.isEmpty()) return true;
        String g = o.optString("sourceGroup", "");
        for (String one : g.split("[,，;；]")) if (one.trim().equals(f)) return true;
        return false;
    }

    /** 切换订阅分组：全部 → 各组轮着来 */
    public void subGroupNext() {
        loadSubSources();
        List<String> groups = new ArrayList<>();
        groups.add("");
        for (JSONObject o : subSources) {
            if (!subEnabled(o)) continue;
            String g = o.optString("sourceGroup", "");
            for (String one : g.split("[,，;；]")) {
                String t = one.trim();
                if (!t.isEmpty() && !groups.contains(t)) groups.add(t);
            }
        }
        String cur = subGroupFilter();
        int at = groups.indexOf(cur);
        if (at < 0) at = 0;
        String next = groups.get((at + 1) % groups.size());
        host.prefSet("subGroup", next);
        host.speak(next.isEmpty() ? "全部订阅源" : ("只看分组 " + next));
        host.replaceTop(subListForNetbook());
        host.renderNow();
    }

    /** 所有订阅源的最新文章汇总：对齐 Legado 订阅首页的「全部」 */
    public void openSubAll() {
        loadSubSources();
        final List<JSONObject> use = new ArrayList<>();
        for (JSONObject o : subSources) if (subEnabled(o)) use.add(o);
        if (use.isEmpty()) { host.speak("还没有可用的订阅源"); return; }
        host.speak("正在汇总 " + use.size() + " 个订阅源");
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                final List<String[]> all = new ArrayList<>();
                for (int si = 0; si < use.size() && si < 8; si++) {
                    JSONObject src = use.get(si);
                    try {
                        String base = srcBaseUrl(src);
                        String html = req(base, base);
                        if (html == null) continue;
                        String listRule = rules.ruleStr(src, "ruleArticles");
                        if (listRule.isEmpty()) listRule = rules.ruleStr(src, "ruleArticleList");
                        String useHtml = html;
                        if (!listRule.isEmpty()) {
                            List<String> blocks = rules.ruleAll(listRule, base, useHtml);
                            if (blocks.size() == 1 && blocks.get(0).contains("<")) useHtml = blocks.get(0);
                        }
                        List<String> titles = rules.ruleAll(rules.ruleStr(src, "ruleTitle"), base, useHtml);
                        if (titles.isEmpty()) titles = rules.ruleAll(rules.ruleStr(src, "ruleArticleTitle"), base, useHtml);
                        List<String> links = rules.ruleAll(rules.ruleStr(src, "ruleLink"), base, useHtml);
                        if (links.isEmpty()) links = rules.ruleAll(rules.ruleStr(src, "ruleArticleUrl"), base, useHtml);
                        List<String> dates = rules.ruleAll(rules.ruleStr(src, "rulePubDate"), base, useHtml);
                        int n = Math.min(titles.size(), links.size());
                        for (int i = 0; i < n && i < 5; i++) {
                            if (titles.get(i).trim().isEmpty()) continue;
                            all.add(new String[]{titles.get(i), links.get(i),
                                    i < dates.size() ? dates.get(i) : "", String.valueOf(si)});
                        }
                    } catch (Exception ignored) {}
                }
                java.util.Collections.sort(all, new java.util.Comparator<String[]>() {
                    @Override public int compare(String[] x, String[] y) {
                        String a = x[2] == null ? "" : x[2];
                        String b = y[2] == null ? "" : y[2];
                        if (a.isEmpty() && b.isEmpty()) return 0;
                        if (a.isEmpty()) return 1;
                        if (b.isEmpty()) return -1;
                        return b.compareTo(a);
                    }
                });
                host.ui().post(new Runnable() { @Override public void run() {
                    subArticles = all;
                    subArticleSrc = -1;
                    subArticleName = "全部订阅";
                    List<Item> items = new ArrayList<>();
                    for (int i = 0; i < all.size(); i++) {
                        String[] a = all.get(i);
                        int si = 0;
                        try { si = Integer.parseInt(a[3]); } catch (Exception ignored) {}
                        String sn = (si >= 0 && si < subSources.size()) ? subName(subSources.get(si)) : "";
                        boolean rd = subIsRead(a[1]);
                        items.add(new Item((rd ? "" : "未读　") + a[0] + "　" + sn
                                + (a[2] == null || a[2].isEmpty() ? "" : "　" + a[2]), "nb:subart:" + i + ":" + urlTag(a[1])));
                    }
                    if (items.isEmpty()) items.add(new Item("（没取到文章，检查订阅源规则）"));
                    host.pushScreen(new Screen("全部订阅的最新", items));
                    host.speak(all.isEmpty() ? "没取到文章" : ("共 " + all.size() + " 篇"));
                } });
            }
        });
    }

    /** 当前打开的是哪一篇，联网重取时要用 */
    public String subCurUrl = "";

    public void showSubArticle(final String title, final String text) {
        showSubArticle(title, text, false);
    }

    public void showSubArticle(final String title, final String text, boolean fromCache) {
        if (text == null || text.trim().isEmpty()) { host.speak("正文没取到"); return; }
        String body = stripHtmlIfAny(text);
        subArticleBody = body;
        subArticleName = title;
        List<Item> items = new ArrayList<>();
        int n = 0;
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.length() > 500) t = t.substring(0, 500);
            items.add(new Item(t));
            if (++n >= 1500) break;
        }
        items.add(new Item("朗读这一篇", "nb:speaksub"));
        items.add(new Item("收藏这篇", "nb:favsub"));
        items.add(new Item("联网重新取这一篇（覆盖本地缓存）", "sub:reget"));
        if (fromCache) items.add(new Item("（正文来自本地缓存，断网也能看）"));
        host.pushScreen(new Screen(title, items));
        host.speak(title);
    }

    public void subOpenArticle(final int i) {
        if (i < 0 || i >= subArticles.size()) return;
        final String[] a = subArticles.get(i);
        subMarkRead(a[1]);
        subCurUrl = a[1] == null ? "" : a[1];
        String cached = SubCache.read(host.dataDir(), a[1]);
        if (cached != null) {          // 有缓存直接上，不再跑网络
            showSubArticle(a[0], cached, true);
            return;
        }
        host.speak("正在打开 " + a[0]);
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                String body = "";
                try {
                    int si2 = subArticleSrc;
                    try { if (a.length > 3) si2 = Integer.parseInt(a[3]); } catch (Exception ignored) {}
                    JSONObject src = subSources.get(si2);
                    bindSrc(src);
                    String html = req(a[1], srcBaseUrl(src));
                    html = tidyRssHtml(src, html, a[1]);       // 正文黑/白名单先过一遍
                    if (html != null) {
                        String cRule = rules.ruleStr(src, "ruleContent");
                        List<String> vals = rules.ruleAll(cRule, srcBaseUrl(src), html);
                        StringBuilder sb = new StringBuilder();
                        for (String v : vals) sb.append(v).append("\n");
                        body = sb.toString();
                        if (body.trim().isEmpty()) body = org.jsoup.Jsoup.parse(html).text();
                    }
                } catch (Exception e) {
                    host.log("订阅", "取正文失败：" + e.getMessage());
                }
                final String text = body;
                if (text != null && !text.trim().isEmpty()) SubCache.write(host.dataDir(), a[1], text);
                host.ui().post(new Runnable() { @Override public void run() { showSubArticle(a[0], text); } });
            }
        });
    }

    /** 联网重取当前这篇：清掉本地缓存再走一次网 */
    public void subRefetchCurrent() {
        final String url = subCurUrl;
        if (url == null || url.isEmpty()) { host.speak("不知道是哪一篇"); return; }
        SubCache.clear(host.dataDir(), url);
        final String titleNow = subArticleName;
        host.speak("正在重新取这篇");
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                String body = "";
                try {
                    int si2 = subArticleSrc;
                    JSONObject src = subSources.get(si2);
                    bindSrc(src);
                    String html = req(url, srcBaseUrl(src));
                    html = tidyRssHtml(src, html, url);
                    if (html != null) {
                        String cRule = rules.ruleStr(src, "ruleContent");
                        List<String> vals = rules.ruleAll(cRule, srcBaseUrl(src), html);
                        StringBuilder sb = new StringBuilder();
                        for (String v : vals) sb.append(v).append("\n");
                        body = sb.toString();
                        if (body.trim().isEmpty()) body = org.jsoup.Jsoup.parse(html).text();
                    }
                } catch (Exception e) {
                    host.log("订阅", "重取失败：" + e.getMessage());
                }
                final String text = body;
                if (text == null || text.trim().isEmpty()) { host.speak("没取到，可能站点拦了"); return; }
                SubCache.write(host.dataDir(), url, text);
                host.ui().post(new Runnable() { @Override public void run() { showSubArticle(titleNow, text); } });
            }
        });
    }

    // ------------------------------------------------------------ 订阅的已读与收藏

    /** 已读按文章链接记，跟 Legado 的 RssArticle 已读标记一个意思 */
    public boolean subIsRead(String url) {
        if (url == null || url.isEmpty()) return false;
        return host.prefString("subRead", "").contains("\u0001" + url + "\u0001");
    }

    public void subMarkRead(String url) {
        if (url == null || url.isEmpty()) return;
        String tag = "\u0001" + url + "\u0001";
        String all = host.prefString("subRead", "");
        if (all.contains(tag)) return;
        all = tag + all;
        if (all.length() > 60000) all = all.substring(0, 60000);
        host.prefSet("subRead", all);
    }

    /** 收藏的文章篇数 */
    public int subFavCount() {
        String all = host.prefString("subFav", "");
        if (all == null || all.trim().isEmpty()) return 0;
        int n = 0;
        for (String one : all.split("\n")) if (one.contains("\u0001")) n++;
        return n;
    }

    /** 收藏列表：名字 + 存下来的正文，点开就能看（等于离线） */
    public void openSubFavs() {
        List<Item> items = new ArrayList<>();
        if (subFavStrings.size() == 0) loadSubFavs();
        for (int i = 0; i < subFavStrings.size(); i++) {
            String[] p = subFavStrings.get(i).split("\u0001", 2);
            items.add(new Item(p[0], "nb:subfavopen:" + i));
        }
        if (items.isEmpty()) items.add(new Item("（还没有收藏的文章）"));
        host.pushScreen(new Screen("收藏的文章", items));
        host.speak(items.size() + " 篇");
    }

    public void openSubFavAt(int i) {
        if (subFavStrings.size() == 0) loadSubFavs();
        if (i < 0 || i >= subFavStrings.size()) return;
        String[] p = subFavStrings.get(i).split("\u0001", 2);
        showSubArticle(p[0], p.length > 1 ? p[1] : "");
    }

    private List<String> subFavStrings = new ArrayList<>();

    private void loadSubFavs() {
        subFavStrings.clear();
        String all = host.prefString("subFav", "");
        if (all == null) return;
        for (String one : all.split("\n")) {
            if (one.contains("\u0001")) subFavStrings.add(one);
        }
    }

    public Screen subListForNetbook() {
        loadSubSources();
        List<Item> items = new ArrayList<>();
        String gf = subGroupFilter();
        items.add(new Item("当前分组：" + (gf.isEmpty() ? "全部订阅源" : gf)));
        for (int i = 0; i < subSources.size(); i++) {
            if (!subEnabled(subSources.get(i))) continue;
            if (!subInGroup(subSources.get(i))) continue;
            items.add(new Item(subName(subSources.get(i)), "nb:subarts:" + i));
        }
        if (items.isEmpty()) {
            items.add(new Item("（还没有订阅源，到设置里导入）"));
            items.add(new Item("设置 → 订阅源管理", "go:subsrc"));
        }
        return new Screen("订阅", items).k("subList");
    }

    public void openSubsScreen() {
        loadSubSources();
        int on = 0;
        for (int i = 0; i < subSources.size(); i++) {
            if (subEnabled(subSources.get(i))) on++;
        }
        if (on == 0) {
            host.speak("还没有订阅源，先到设置里的订阅源管理导入");
            host.toast("还没有订阅源");
            return;
        }
        host.pushScreen(subListForNetbook());
    }
}
