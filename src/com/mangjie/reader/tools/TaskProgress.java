package com.mangjie.reader.tools;

/**
 * 后台长任务的进度登记处：缓存整本、批量校验书源、下载更新包都往这儿报一声。
 * 主菜单会根据这里有没有活儿决定要不要多出一行「任务进度」。
 *
 * 只存一份（同一时刻只跑一个长任务），够用且不会打架。
 */
public final class TaskProgress {

    private static String label = null;
    private static int done = 0;
    private static int total = 0;

    private TaskProgress() {}

    public static synchronized void start(String what, int total) {
        label = what;
        done = 0;
        TaskProgress.total = total;
    }

    public static synchronized void update(int doneNow) {
        done = doneNow;
    }

    public static synchronized void finish() {
        label = null;
        done = 0;
        total = 0;
    }

    public static synchronized boolean active() {
        return label != null;
    }

    /** 说给人听的一句话，没活时也照样能播 */
    public static synchronized String describe() {
        if (label == null) return "现在没有后台任务";
        if (total <= 0) return label + "，已完成 " + done;
        return label + "：已完成 " + done + "，共 " + total;
    }

    /** 播报用的短句，进度播报用这个更省字 */
    public static synchronized String brief() {
        if (label == null) return "";
        return "已完成 " + done + "，共 " + total;
    }
}
