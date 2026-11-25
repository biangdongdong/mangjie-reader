package com.mangjie.reader.model;

import java.util.List;

/** 一个页面：标题、条目、当前焦点，以及网格页/阅读页/文件页的标志位 */
public class Screen {
    public final String title;
    public String extra = null;    // 标题后缀：按章装载时显示「第N/M章」
    public List<Item> items;
    public int cur = 0;
    public boolean fileMode = false;      // 文件浏览页：允许 ≡ 弹功能菜单
    public boolean reader = false;        // 阅读页：每行是一句正文
    public boolean txtOnly = false;       // 书单页：只看 txt 和文件夹
    public int cols = 0;                  // >0 表示网格页（万年历 7 列、计算器 4 列）
    public int cellCur = 0;               // 网格里的红格位置
    public List<String> cells;            // 网格每格的文字
    public boolean calc = false;          // 计算器页
    public boolean voiceCtl = false;      // 「朗读语音」子菜单
    /** 可调节行：焦点停在这些行上时，上下/左右滑动手势调数值而不是换焦点，-1 表示没有 */
    public int adjustRate = -1;
    public int adjustPitch = -1;
    public boolean player = false;        // 音频播放页：顶部书名章节 + 大播放键 + 进度条
    public String coverPath = null;       // 详情页的小封面（本地图片路径），跟焦点行一起走
    /** 设置类页面的稳定标识：标题可能改，刷新时按键而不是按标题字符串 */
    public String key = null;
    /** 顶部横排分栏（书架分组）：非空时在标题下方显示一溜按钮 */
    public List<String> tabs;
    public int tabCur = 0;
    /** 书架页的两种入口：false=本地书架，true=网络书架。页内不再有分组条，靠这个区分 */
    public boolean shelfNet = false;

    public Screen(String title, List<Item> items) {
        this.title = title;
        this.items = items;
    }

    /** 打上设置页标识，返回自己，便于 new Screen(...).k("settings") 链式写 */
    public Screen k(String key) {
        this.key = key;
        return this;
    }
}
