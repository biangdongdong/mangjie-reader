package com.mangjie.reader.reader;

/**
 * 阅读页的渲染窗口：一万多句不能一次建完 View，只建当前句附近一段。
 * 句数不超过 200 时全建；超过则当前句前留 20 行、总共 80 行，不足 80 行时向前补。
 */
public final class BookWindow {

    public static final int FULL_LIMIT = 200;
    public static final int BEFORE = 20;
    public static final int ROWS = 80;

    private BookWindow() {}

    /** 返回 {起始行, 结束行（不含）} */
    public static int[] of(int total, int cur) {
        if (total <= FULL_LIMIT) return new int[]{0, Math.max(0, total)};
        int c = cur;
        if (c < 0) c = 0;
        if (c > total - 1) c = total - 1;
        int first = c - BEFORE;
        if (first < 0) first = 0;
        int last = first + ROWS;
        if (last > total) last = total;
        if (last - first < ROWS) {
            first = last - ROWS;
            if (first < 0) first = 0;
        }
        return new int[]{first, last};
    }
}
