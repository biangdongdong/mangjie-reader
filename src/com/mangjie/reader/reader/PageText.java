package com.mangjie.reader.reader;

/** 页面与行的读屏文案，格式固定，改一处就全场一致 */
public final class PageText {

    private PageText() {}

    /** 一行：「正文，第3项，共12项」 */
    public static String row(String label, int index, int total) {
        return (label == null ? "" : label) + "，第" + (index + 1) + "项，共" + total + "项";
    }

    /** 换页：「书名，共12项」 */
    public static String page(String title, int total) {
        return (title == null ? "" : title) + "，共" + total + "项";
    }

    /** 日志用：「书名，共12项，当前第3项」 */
    public static String pageLog(String title, int total, int cur) {
        return (title == null ? "" : title) + "，共" + total + "项，当前第" + (cur + 1) + "项";
    }
}
