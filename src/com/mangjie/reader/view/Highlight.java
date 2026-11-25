package com.mangjie.reader.view;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

/**
 * 选中高亮。
 *
 * row / grid 走自绘 {@link Bar}：支持 6 种形状、不透明度、内高光、投影、渐变方向。
 * tabOn / tabOff 仍是原来的三层结构，分栏按钮外观不动。
 */
public final class Highlight {

    private Highlight() {}

    /** f>0 提亮，f<0 压暗 */
    public static int shade(int base, float f) {
        int r = Color.red(base), g = Color.green(base), b = Color.blue(base);
        if (f >= 0) {
            r = (int) (r + (255 - r) * f);
            g = (int) (g + (255 - g) * f);
            b = (int) (b + (255 - b) * f);
        } else {
            r = (int) (r * (1 + f));
            g = (int) (g * (1 + f));
            b = (int) (b * (1 + f));
        }
        return Color.argb(255, r, g, b);
    }

    /** 列表行选中条（老签名，等于圆角 + 不透明 + 高光 + 投影 + 上下渐变） */
    public static Drawable row(int base, float density) {
        return row(base, density, 0, 100, true, true, 0);
    }

    /** 列表行选中条：shape 0 圆角 / 1 直角 / 2 胶囊 / 3 下划线 / 4 左竖条 / 5 空心描边 */
    public static Drawable row(int base, float density, int shape, int alphaPct,
                               boolean gloss, boolean shadow, int grad) {
        return new Bar(base, density, shape, alphaPct, gloss, shadow, grad, 6, 0);
    }

    /** 网格格选中块（老签名） */
    public static Drawable grid(int base, float density) {
        return grid(base, density, 0, 100, true, true, 0);
    }

    public static Drawable grid(int base, float density, int shape, int alphaPct,
                                boolean gloss, boolean shadow, int grad) {
        return new Bar(base, density, shape, alphaPct, gloss, shadow, grad, 8, 2);
    }

    /** 分栏按钮·选中：与列表红条同一套（渐变、亮边、内高光、投影） */
    public static Drawable tabOn(int base, float density) {
        return build(base, density, 6, 1, 1, 1, 1, 2, 2, 2, 2);
    }

    /** 分栏按钮·未选中：同族暗底，细描边 + 微高光，跟选中条搭但不抢眼 */
    public static Drawable tabOff(int bg, int fg, float density) {
        int radius = px(6, density);
        GradientDrawable main = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{shade(bg, 0.12f), bg, shade(bg, -0.04f)});
        main.setCornerRadius(radius);
        main.setStroke(px(1, density), (fg & 0x00FFFFFF) | 0x99000000);
        GradientDrawable gloss = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x22FFFFFF, 0x08FFFFFF, 0x00FFFFFF});
        gloss.setCornerRadius(radius);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{main, gloss});
        ld.setLayerInset(1, px(1, density), px(1, density), px(1, density), px(1, density));
        return ld;
    }

    private static int px(float v, float density) {
        return Math.round(v * density);
    }

    private static Drawable build(int base, float density,
                                  int radiusDp,
                                  int mainL, int mainT, int mainR, int mainB,
                                  int glossL, int glossT, int glossR, int glossB) {
        int radius = px(radiusDp, density);
        GradientDrawable main = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{shade(base, 0.28f), base, shade(base, -0.18f)});
        main.setCornerRadius(radius);
        main.setStroke(px(1, density), shade(base, 0.5f));
        GradientDrawable gloss = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x38FFFFFF, 0x10FFFFFF, 0x00FFFFFF});   // 上半段内高光
        gloss.setCornerRadius(radius);
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(0x55000000);
        shadow.setCornerRadius(radius);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{shadow, main, gloss});
        ld.setLayerInset(1, px(mainL, density), px(mainT, density), px(mainR, density), px(mainB, density));
        ld.setLayerInset(2, px(glossL, density), px(glossT, density), px(glossR, density), px(glossB, density));
        return ld;
    }

    /** 自绘焦点条：形状按 bounds 实时算，不依赖 View 高度预设 */
    private static final class Bar extends Drawable {
        private final int base;
        private final float density;
        private final int shape;
        private final int alphaPct;
        private final boolean gloss;
        private final boolean shadow;
        private final int grad;      // 0 上下 / 1 左右 / 2 纯色
        private final int radiusDp;
        private final int insetDp;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF tmp = new RectF();

        Bar(int base, float density, int shape, int alphaPct, boolean gloss, boolean shadow,
            int grad, int radiusDp, int insetDp) {
            this.base = base;
            this.density = density;
            this.shape = shape;
            this.alphaPct = Math.max(0, Math.min(100, alphaPct));
            this.gloss = gloss;
            this.shadow = shadow;
            this.grad = grad;
            this.radiusDp = radiusDp;
            this.insetDp = insetDp;
        }

        private int color() {
            int a = Math.round(alphaPct * 2.55f);
            return (base & 0x00FFFFFF) | (a << 24);
        }

        @Override public void draw(Canvas c) {
            rect.set(getBounds());
            if (rect.width() <= 0 || rect.height() <= 0 || alphaPct == 0) return;
            if (insetDp > 0) rect.inset(px(insetDp, density), px(insetDp, density));
            int col = color();
            float r = radiusFor(rect);

            // 投影：同色块下移 1dp，与老外观一致
            if (shadow && shape != 3 && shape != 4) {
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(null);
                paint.setColor(0x55000000);
                tmp.set(rect.left, rect.top + px(1, density), rect.right, rect.bottom + px(1, density));
                c.drawRoundRect(tmp, r, r, paint);
            }

            paint.setShader(null);
            if (shape == 3) {                       // 下划线：底部 2dp
                float h = px(2, density);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(col);
                c.drawRect(rect.left + px(2, density), rect.bottom - h, rect.right - px(2, density), rect.bottom, paint);
                return;
            }
            if (shape == 4) {                       // 左竖条：宽 4dp、全高
                float w = px(4, density);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(col);
                c.drawRect(rect.left, rect.top, rect.left + w, rect.bottom, paint);
                return;
            }
            if (shape == 5) {                       // 空心描边
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(px(1.5f, density));
                paint.setColor(col);
                c.drawRoundRect(rect, px(2, density), px(2, density), paint);
                return;
            }

            // 实心：渐变填充 → 亮描边 → 上半段内高光
            paint.setStyle(Paint.Style.FILL);
            if (grad == 2) {
                paint.setShader(null);
                paint.setColor(col);
            } else {
                int hi = shade(col, 0.28f), lo = shade(col, -0.18f);
                hi = (hi & 0x00FFFFFF) | (Color.alpha(col) << 24);
                lo = (lo & 0x00FFFFFF) | (Color.alpha(col) << 24);
                Shader sh = (grad == 1)
                        ? new LinearGradient(rect.left, 0, rect.right, 0, hi, lo, Shader.TileMode.CLAMP)
                        : new LinearGradient(0, rect.top, 0, rect.bottom, hi, lo, Shader.TileMode.CLAMP);
                paint.setShader(sh);
            }
            c.drawRoundRect(rect, r, r, paint);

            int edge = shade(col, 0.5f);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(px(1, density));
            paint.setColor((edge & 0x00FFFFFF) | (Color.alpha(col) << 24));
            c.drawRoundRect(rect, r, r, paint);

            if (gloss) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor((((Color.alpha(col) >> 1) & 0xFF) << 24) | 0x00FFFFFF);
                tmp.set(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.5f);
                c.drawRoundRect(tmp, r, r, paint);
            }
        }

        private float radiusFor(RectF b) {
            if (shape == 1 || shape == 3 || shape == 4) return 0;
            if (shape == 2) return b.height() / 2f;          // 胶囊
            return Math.min(px(radiusDp, density), b.height() / 2f);
        }

        @Override public void setAlpha(int a) { /* 不透明度由 alphaPct 控制 */ }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
