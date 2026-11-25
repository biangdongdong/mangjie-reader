package com.mangjie.reader.speech;

/**
 * 一套语音的完整状态：引擎、发音人、语速、语调、是否加倍。
 * 菜单语音和朗读语音各持一个实例，互不影响。
 */
public class VoiceProfile {

    public String enginePkg = null;   // null = 跟随系统
    public String voiceName = null;   // null = 默认发音人
    public int rateP = 50;            // 0–100，50 为正常
    public int pitchP = 50;
    public int speedMul = 1;          // 语速加倍倍数：1–5，1 表示不加倍

    /** 语速加减一段，夹在 [min, max]；返回加完后的值 */
    public int nudgeRate(int delta, int min, int max) {
        rateP = Math.max(min, Math.min(max, rateP + delta));
        return rateP;
    }

    public int nudgePitch(int delta, int min, int max) {
        pitchP = Math.max(min, Math.min(max, pitchP + delta));
        return pitchP;
    }

    /** 实际朗读倍率 */
    public float effectiveRate() {
        return VoiceMath.effective(rateP, speedMul);
    }

    /** 无障碍朗读用的说法：1 倍说成「不加倍」 */
    public static String mulLabel(int m) {
        int x = Math.max(1, Math.min(5, m));
        return x == 1 ? "1 倍（不加倍）" : (x + " 倍");
    }
}
