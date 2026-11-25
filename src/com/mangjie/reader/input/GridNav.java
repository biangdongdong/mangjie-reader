package com.mangjie.reader.input;

import com.mangjie.reader.model.Screen;

/**
 * 网格页的方向键移动：万年历、计算器共用同一套行列换算。
 * 这里只算目标格的序号，不碰界面、不播报。
 */
public final class GridNav {

    /** 方向键对应的行/列增量 */
    public static int[] delta(int keyCode) {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) return new int[]{0, -1};
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) return new int[]{0, 1};
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) return new int[]{-1, 0};
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return new int[]{1, 0};
        return null;
    }

    /**
     * 在当前格基础上移动一格，返回新格序号；移不动时返回 -1。
     * 行尾不满（例如计算器整宽等号那一行）时落到该行第一个实格。
     */
    public static int move(Screen sc, int dc, int dr) {
        if (sc == null || sc.cells == null || sc.cols <= 0) return -1;
        if (dc == 0 && dr == 0) return -1;
        int cols = Math.max(1, sc.cols);
        int rows = (sc.cells.size() + cols - 1) / cols;
        int row = sc.cellCur / cols, col = sc.cellCur % cols;
        row += dr;
        col += dc;
        if (row < 0) row = 0;
        if (row >= rows) row = rows - 1;
        if (col < 0) col = 0;
        if (col >= cols) col = cols - 1;
        int ni = row * cols + col;
        if (ni >= sc.cells.size() || sc.cells.get(ni).isEmpty()) {
            int base = row * cols;
            for (int k = 0; k < cols && base + k < sc.cells.size(); k++) {
                if (!sc.cells.get(base + k).isEmpty()) { ni = base + k; break; }
            }
        }
        if (ni < 0 || ni >= sc.cells.size() || sc.cells.get(ni).isEmpty()) return -1;
        return ni;
    }
}
