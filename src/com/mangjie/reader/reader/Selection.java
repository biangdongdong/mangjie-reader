package com.mangjie.reader.reader;

/** 朗读选区：起止都是字级位置（本条正文序列的全局字序），首尾都含 */
public final class Selection {

    public int startIdx = -1;
    public int endIdx = -1;

    public boolean hasStart() { return startIdx >= 0; }

    public boolean hasEnd() { return endIdx >= 0; }

    public boolean complete() { return startIdx >= 0 && endIdx >= 0; }

    public void clear() {
        startIdx = -1;
        endIdx = -1;
    }

    /** 起止对调过的返回 true，方便播报「起止已对调」 */
    public boolean normalize() {
        if (startIdx < 0 || endIdx < 0) return false;
        if (startIdx <= endIdx) return false;
        int t = startIdx;
        startIdx = endIdx;
        endIdx = t;
        return true;
    }

    public int length() {
        if (!complete()) return 0;
        return Math.abs(endIdx - startIdx) + 1;
    }

    /** 只说位置，不给正文，用于播报 */
    public String describe(TextCursor c) {
        if (!hasStart()) return "无起点";
        int[] a = c.locate(startIdx);
        if (!hasEnd()) return "起点，第 " + (a[0] + 1) + " 句第 " + (a[1] + 1) + " 字";
        int[] b = c.locate(endIdx);
        return "起点第 " + (a[0] + 1) + " 句第 " + (a[1] + 1) + " 字，终点第 "
                + (b[0] + 1) + " 句第 " + (b[1] + 1) + " 字";
    }
}
