package com.mangjie.reader.reader;

import com.mangjie.reader.model.Item;
import com.mangjie.reader.model.Screen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 词典这一块：查词、在线词典规则的增删说明页。
 *
 * 从 MainActivity 里搬出来的（滚动式模块化，2026-11-18）。页面生成本来就只依赖
 * 数据目录、播报、日志、请求和屏幕栈，所以这里用 Host 收回口子，主类不再持有这些页面细节。
 */
public class DictScreen {

    public interface Host {
        /** 应用数据目录，词典规则目录建在它下面 */
        String dataDir();
        void speak(String msg);
        void log(String tag, String msg);
        /** 带超时的请求，查词走它 */
        String requestUrlT(String url, String base, int timeoutMs);
        android.os.Handler ui();
        List<Screen> stack();
        void render(boolean reset);
        /** 第一次用要把内置词表解出来 */
        void ensureBuiltinDict();
    }

    private final Host h;

    public DictScreen(Host h) {
        this.h = h;
    }

    /** 盲界阅读/词典规则，不存在就建 */
    public File ruleDir() {
        File d = new File(h.dataDir(), "词典规则");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 词典主页面 */
    public Screen mainScreen() {
        h.ensureBuiltinDict();
        Look.load(new File(h.dataDir(), "词典"));
        List<Item> items = new ArrayList<>();
        items.add(new Item("词库  " + Look.count() + " 条（含内置词表）"));
        items.add(new Item("查词", "mydict:look"));
        int rn = DictOnline.load(ruleDir());
        items.add(new Item("在线词典规则  " + rn + " 条", "mydict:rules"));
        items.add(new Item("重新载入词库", "mydict:reload"));
        return new Screen("词典查词", items).k("mydict");
    }

    /** 在线规则列表页 */
    public Screen rulesScreen() {
        DictOnline.load(ruleDir());
        List<Item> items = new ArrayList<>();
        items.add(new Item("规则目录  盲界阅读/词典规则"));
        items.add(new Item("已载入 " + DictOnline.count() + " 条"));
        for (DictOnline.Rule r : DictOnline.rules()) {
            items.add(new Item("　" + r.text() + "\n　" + r.url, "none"));
        }
        items.add(new Item("新建一条规则（一路问我几个值）", "mydict:ruleadd"));
        items.add(new Item("删除某条规则文件", "mydict:rulerm"));
        items.add(new Item("重新载入规则", "mydict:rulesreload"));
        items.add(new Item("怎么加规则（说明）", "mydict:ruleshelp"));
        return new Screen("在线词典规则", items).k("dictRules");
    }

    /** 删规则页：点一条删一条 */
    public Screen removeScreen() {
        List<Item> items = new ArrayList<>();
        DictOnline.load(ruleDir());
        items.add(new Item("点一条就删掉它（只删自己加在词典规则目录里的文件）"));
        for (DictOnline.Rule r : DictOnline.rules()) {
            items.add(new Item("删　" + r.name, "mydict:rmone:" + r.name));
        }
        if (items.size() == 1) items.add(new Item("（还没有规则）"));
        return new Screen("删除在线规则", items).k("dictRuleRm");
    }

    /** 规则写法说明页 */
    public Screen helpScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("一个词典一个 .json，放进 盲界阅读/词典规则 目录。"));
        items.add(new Item("要 html 的：{\"name\":\"汉典\",\"url\":\"https://www.zdic.net/hans/{{key}}\","
                + "\"type\":\"html\",\"css\":\"div.entry\"}"));
        items.add(new Item("要 json 的：{\"name\":\"某接口\",\"url\":\"https://...?w={{key}}\","
                + "\"type\":\"json\",\"path\":\"result.explain\"}"));
        items.add(new Item("{{key}} 换成查的词；css 里填选择器，path 填 点号路径，数组用 a[0].b 这种"));
        items.add(new Item("一个文件也可以写成数组，一次放好几个词典；按文件名顺序挨个试，谁先有结果用谁"));
        return new Screen("词典规则说明", items).k("dictRulesHelp");
    }

    /** 查词：有在线规则就先在线（后台跑，别卡界面），再落本地词库 */
    public void lookup(final String word) {
        h.ensureBuiltinDict();
        final File rdir = ruleDir();
        final int onlineN = DictOnline.load(rdir);
        if (onlineN <= 0) {
            showResult(word, "", null, 0);
            return;
        }
        h.speak("查 " + word + "，先试在线词典");
        new Thread(new Runnable() {
            @Override public void run() {
                final String[] hint = new String[1];
                String online = "";
                try {
                    online = DictOnline.lookup(word, new DictOnline.Net() {
                        @Override public String get(String url) { return h.requestUrlT(url, "", 12000); }
                    }, hint);
                } catch (Throwable ignored) { }
                final String res = online == null ? "" : online;
                final String hit = hint[0];
                h.log("词典", "在线查 " + word + (res.isEmpty() ? " 没命中" : " 命中于 " + hit));
                h.ui().post(new Runnable() { @Override public void run() {
                    showResult(word, res, hit, onlineN);
                } });
            }
        }, "dict-lookup").start();
    }

    /** 出查词结果页：在线有就用在线，没有落本地 */
    public void showResult(String word, String online, String hit, int onlineN) {
        List<Item> items = new ArrayList<>();
        if (online != null && !online.isEmpty()) {
            items.add(new Item("来自在线词典：" + (hit == null ? "" : hit)));
            for (String line : online.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty()) items.add(new Item(t));
            }
            h.stack().add(new Screen("查词　" + word, items));
            h.render(true);
            h.speak("在线词典查到了");
            return;
        }
        Look.load(new File(h.dataDir(), "词典"));
        List<String> hits = Look.lookup(word);
        if (hits.isEmpty()) {
            items.add(new Item("（没查到这个词）"));
            items.add(new Item("词库 " + Look.count() + " 条，"
                    + (onlineN > 0 ? "在线词典 " + onlineN + " 条也都试了" : "还没有在线词典规则")));
        } else {
            for (String s : hits) items.add(new Item(s));
        }
        h.stack().add(new Screen("查词　" + word, items));
        h.render(true);
        if (hits.isEmpty()) h.speak("没查到这个词");
        else { h.speak(hits.size() + " 条解释"); h.log("词典", "查 " + word + " 命中 " + hits.size()); }
    }
}
