package com.mangjie.reader.reader;

/**
 * 手指纵坐标落在哪一行。三等：正好在行内；离最近行不超过 slop；滑出首末行不超过 edge 时贴边。
 */
public final class RowHit {

    private RowHit() {}

    /**
     * @param y      相对列表容器顶部的纵坐标
     * @param tops   各行顶边
     * @param bottoms 各行底边（不含）
     * @param focusable 该行是否可聚焦
     * @param offset 第 0 个孩子对应的行号
     * @return 行号（含 offset），未命中返回 -1
     */
    public static int hit(float y, int[] tops, int[] bottoms, boolean[] focusable,
                          int offset, float slop, float edge) {
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        int count = tops.length;
        for (int i = 0; i < count; i++) {
            if (focusable != null && i < focusable.length && !focusable[i]) continue;
            int top = tops[i];
            int bottom = bottoms[i];
            if (y >= top && y < bottom) return i + offset;
            float d = y < top ? top - y : y - bottom;
            if (d < bestDist) { bestDist = d; best = i; }
        }
        if (bestDist <= slop) return best < 0 ? -1 : best + offset;
        if (best >= 0 && count > 0) {
            int top = tops[0];
            int bottom = bottoms[count - 1];
            if (y < top && top - y <= edge) return offset;
            if (y > bottom && y - bottom <= edge) return offset + count - 1;
        }
        return -1;
    }
}
