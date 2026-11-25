package com.mangjie.reader.speech;

/** 语速/语调百分比到 TTS 倍率的换算。两套语音（菜单、正文）共用同一套公式，但各自存值 */
public final class VoiceMath {

    private VoiceMath() {}

    /** 50% 为正常：低于 50 压缩到 0.3–1.0，高于 50 放大到 1.0–2.5 */
    public static float factor(int percent) {
        if (percent <= 50) return 0.3f + 0.7f * percent / 50f;
        return 1.0f + 1.5f * (percent - 50) / 50f;
    }

    /** 实际朗读倍率：基础倍率 × 语速加倍倍数（1–5），夹在 0.1–8.0 之间 */
    public static float effective(int percent, int mul) {
        int m = mul < 1 ? 1 : (mul > 5 ? 5 : mul);
        float f = factor(percent) * m;
        if (f < 0.1f) f = 0.1f;
        if (f > 8.0f) f = 8.0f;          // 5 倍 × 语速 100% 会到 12.5，太吵，封在 8
        return f;
    }
}
