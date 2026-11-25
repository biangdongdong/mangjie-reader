package com.mangjie.reader.view;

import android.content.SharedPreferences;

import com.mangjie.reader.model.Item;
import com.mangjie.reader.model.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置几页的「页面定义」：只拼文字与动作键，不碰 View、不碰状态。
 * 状态由调用方用 {@link Host} 提供，这样主类里那几百行构造就搬出来了。
 */
public final class SettingsScreens {

    /** 页面需要读的那些值，由 MainActivity 实现 */
    public interface Host {
        SharedPreferences prefs();
        /** 当前字号名，如「中」 */
        String fontName();
        /** 当前行距名 */
        String lineSpaceName();
        /** 当前页边距名 */
        String sidePadName();
        /** 正文字体现状，如「系统默认」 */
        String fontLabel();
        /** 当前阅读配色的名字，如「黑底白字」 */
        String paletteName();
        boolean simplifyOn();
        /** 实际生效的搜索线程数（已封顶 9） */
        int searchThreads();
        /** 用户填的原值，1 到 16 */
        int searchThreadsRaw();
        /** 订阅缓存占多少字节 */
        long subCacheSize();
    }

    private SettingsScreens() {}

    /** 字节数说成人话 */
    public static String size(long bytes) {
        if (bytes <= 0) return "未知";
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        if (gb >= 1) return String.format(java.util.Locale.US, "%.2f GB", gb);
        return String.format(java.util.Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0);
    }

    public static Screen settings() {
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("外观", "set:appearance"));
        items.add(new Item("阅读", "set:read"));
        items.add(new Item("书源管理", "go:booksrc"));
        items.add(new Item("订阅源管理", "go:subsrc"));
        items.add(new Item("其他", "set:data"));
        items.add(new Item("手势", "set:gesture"));
        items.add(new Item("操作反馈", "go:fb"));
        items.add(new Item("帮助", "go:help"));
        // 批量测试用：测试者找不到「关于→开发者」那一层，所以放到设置第一屏
        items.add(new Item("回传日志给作者", "dev:upload"));
        items.add(new Item("分享日志文件", "dev:share"));
        return new Screen("设置", items).k("settings");
    }

    public static Screen appearance(Host h) {
        SharedPreferences p = h.prefs();
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("夜间模式（夜间" + (p.getBoolean("nightMode", false) ? "开" : "关")
                + "，自动" + (p.getBoolean("autoNight", false) ? "开" : "关") + "）", "go:night"));
        items.add(new Item("阅读配色（当前 " + h.paletteName() + "）", "go:readcolor"));
        items.add(new Item("光标条颜色（当前 " + com.mangjie.reader.view.ColorName.full(
                com.mangjie.reader.view.ThemeColors.hlColor(p)) + "）", "go:hlcolor"));
        items.add(new Item("光标条样式（" + com.mangjie.reader.view.ThemeColors.shapeName(p)
                + "，" + com.mangjie.reader.view.ThemeColors.hlAlpha(p) + "%）", "go:hlstyle"));
        items.add(new Item("标题栏跟随主题：" + (com.mangjie.reader.view.ThemeColors.titleFollow(p) ? "开" : "关"),
                "go:titlefollow"));
        items.add(new Item("文字设置（字号" + h.fontName() + "，字体" + h.fontLabel() + "）", "go:text"));
        items.add(new Item("版式设置（行距 " + h.lineSpaceName() + "，页边距 " + h.sidePadName() + "）", "go:layout"));
        return new Screen("外观设置", items).k("appearance");
    }

    public static Screen read(Host h) {
        SharedPreferences p = h.prefs();
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("目录规则（内置 " + com.mangjie.reader.reader.TocRules.count() + " 条）", "go:tocrule"));
        items.add(new Item("替换净化（" + com.mangjie.reader.reader.Purify.ruleCount() + " 条规则）", "go:purify"));
        items.add(new Item("简繁转换：" + (h.simplifyOn() ? "已开启" : "已关闭"), "go:simplify"));
        items.add(new Item("摘抄导出", "go:excerpt"));
        items.add(new Item("本地词库查词（" + com.mangjie.reader.reader.Look.count() + " 条）", "go:mydict"));
        return new Screen("阅读", items);
    }

    public static Screen data(Host h) {
        SharedPreferences p = h.prefs();
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("订阅缓存 " + size(h.subCacheSize()) + "，点一下清空", "go:subcacheclear"));
        items.add(new Item("备份与恢复", "go:backup"));
        items.add(new Item("搜索线程数：填 " + h.searchThreadsRaw() + "，生效 " + h.searchThreads() + "（点一下改）", "src:threads"));
        items.add(new Item("每天自动检查更新：" + (p.getBoolean("updAuto", true) ? "开" : "关") + "，点一下切换", "nb:updauto"));
        items.add(new Item("还原", "go:factory"));
        return new Screen("其他", items).k("setData");
    }
}
