package com.mangjie.reader.view;

import android.graphics.Color;

/**
 * RGB → 中文颜色名。给盲人用户调完任意色后一句可听懂的交代。
 *
 * 两级策略：
 * 1) 先查 80 条常见色名表，按 RGB 加权距离取最近；
 * 2) 表里都差得远，就按色相分段 + 深浅/浓淡拼一个方向正确的名字（"偏暗的紫红"）。
 * 颜色名永远是近似的，所以另外给亮度、饱和度两个可验证的描述。
 */
public final class ColorName {

    private ColorName() {}

    /** 色名表：[r,g,b,名称] */
    private static final Object[][] TABLE = {
            {0xFF0000, "红"}, {0xD32F2F, "朱红"}, {0x8B0000, "深红"}, {0xDC143C, "绯红"},
            {0xC72185, "玫红"}, {0x9B111E, "胭脂红"}, {0x7B1FA2, "紫红"}, {0x6D071A, "枣红"},
            {0xB4523B, "砖红"}, {0x8B3A3A, "铁锈红"}, {0x722F37, "酒红"}, {0xFF69B4, "粉红"},
            {0xFFB6C1, "桃粉"}, {0xE6C9CE, "藕粉"}, {0xFF7F50, "珊瑚"}, {0xFF6347, "番茄红"},

            {0xFF7F00, "橙"}, {0xFFA500, "橘黄"}, {0xFFBF00, "琥珀"}, {0xFBCEB1, "杏黄"},
            {0xFF9F40, "蜜橙"}, {0xAF6E4D, "焦糖"},

            {0xFFFF00, "黄"}, {0xFFD700, "金黄"}, {0xFFF44F, "柠檬黄"}, {0xC9A227, "芥末黄"},
            {0xE1A95F, "土黄"}, {0xC3B091, "卡其"}, {0xF7E7CE, "香槟"},

            {0x00FF00, "绿"}, {0x2ECC71, "翠绿"}, {0x7CFC00, "草绿"}, {0x9ACD32, "嫩绿"},
            {0x6B8E23, "橄榄绿"}, {0x006400, "墨绿"}, {0x004B49, "松石绿"}, {0x98FF98, "薄荷"},
            {0x4B5320, "军绿"}, {0xBFFF00, "青柠"},

            {0x00FFFF, "青"}, {0x7FC7FF, "天青"}, {0x1E90FF, "湖蓝"}, {0x0F52BA, "孔雀蓝"},
            {0x2E8B57, "青碧"},

            {0x0000FF, "蓝"}, {0x87CEEB, "天蓝"}, {0x00008B, "深蓝"}, {0x000080, "藏青"},
            {0x0F4C81, "宝蓝"}, {0x0047AB, "钴蓝"}, {0x4682B4, "钢蓝"}, {0xB8D8EB, "雾蓝"},
            {0x4B0082, "靛蓝"}, {0x191970, "午夜蓝"},

            {0x800080, "紫"}, {0x8F00FF, "紫罗兰"}, {0xE6E6FA, "薰衣草"}, {0x6F2DA8, "葡萄紫"},
            {0x5D0E4E, "绛紫"}, {0xC8A2C8, "藕荷"},

            {0x8B4513, "棕"}, {0x4B3621, "咖啡"}, {0x3B2F2F, "巧克力"}, {0x9C5A2E, "赭石"},
            {0x5C4033, "栗"}, {0x6F4E37, "茶褐"}, {0xC19A6B, "驼"},

            {0xFFFFFF, "白"}, {0xFFFAFA, "雪白"}, {0xFFFFF0, "象牙白"}, {0xF5F5DC, "米白"},
            {0xD3D3D3, "浅灰"}, {0xC0C0C0, "银灰"}, {0x808080, "灰"}, {0x555555, "深灰"},
            {0x1C1C1C, "炭黑"}, {0x000000, "黑"},

            {0xC5C5C5, "银"}, {0xD4AF37, "金"}, {0xB87333, "古铜"},
    };

    /** 表里最近色名；差太远就按色相拼一个。返回不带修饰的短名，如"玫红""藏在色相里的紫红" */
    public static String nameOf(int argb) {
        int r = Color.red(argb), g = Color.green(argb), b = Color.blue(argb);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < TABLE.length; i++) {
            int cr = (Integer) TABLE[i][0];
            int tr = (cr >> 16) & 0xFF, tg = (cr >> 8) & 0xFF, tb = cr & 0xFF;
            // 加权距离：人眼对绿最敏感，蓝最钝
            double d = 2.0 * (r - tr) * (r - tr) + 4.0 * (g - tg) * (g - tg) + 3.0 * (b - tb) * (b - tb);
            if (d < bestD) { bestD = d; best = i; }
        }
        double dist = Math.sqrt(bestD) / 3.0;
        if (best >= 0 && dist < 52) return (String) TABLE[best][1];

        // 拼名：基础色相 + 深浅
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        String hue = hueWord(hsv[0]);
        String depth = hsv[2] < 0.35f ? "深" : (hsv[2] > 0.8f ? "浅" : "");
        return depth + hue;
    }

    /** 色相分段的粗名，落在 8 个扇区 */
    private static String hueWord(float h) {
        if (h < 12 || h >= 345) return "红";
        if (h < 40) return "橙";
        if (h < 68) return "黄";
        if (h < 160) return "绿";
        if (h < 200) return "青";
        if (h < 260) return "蓝";
        if (h < 320) return "紫";
        return "玫红";
    }

    /** 亮度与饱和度的可验证描述，如"偏亮、很艳" */
    public static String describe(int argb) {
        float[] hsv = new float[3];
        Color.RGBToHSV(Color.red(argb), Color.green(argb), Color.blue(argb), hsv);
        String bright;
        float v = hsv[2];
        if (v < 0.18f) bright = "很暗";
        else if (v < 0.4f) bright = "偏暗";
        else if (v < 0.65f) bright = "中等亮";
        else if (v < 0.88f) bright = "偏亮";
        else bright = "很亮";

        String sat;
        float sv = hsv[1];
        if (sv < 0.1f) sat = "几乎无色";
        else if (sv < 0.3f) sat = "很淡";
        else if (sv < 0.55f) sat = "偏淡";
        else if (sv < 0.8f) sat = "适中";
        else sat = "很艳";
        return bright + "、" + sat;
    }

    /** 亮度百分比，0–100 */
    public static int brightness(int argb) {
        float[] hsv = new float[3];
        Color.RGBToHSV(Color.red(argb), Color.green(argb), Color.blue(argb), hsv);
        return Math.round(hsv[2] * 100);
    }

    /** 完整交代："玫红，偏亮、很艳，亮度 61" */
    public static String full(int argb) {
        return nameOf(argb) + "，" + describe(argb) + "，亮度 " + brightness(argb);
    }

    /** 依据当前通道值与"在调哪个通道"，给一句带方向的短话，如"绿通道调高，偏青" */
    public static String channelHint(int argb, String channel) {
        return channelName(channel) + "通道，" + full(argb);
    }

    public static String channelName(String channel) {
        if ("r".equals(channel)) return "红";
        if ("g".equals(channel)) return "绿";
        if ("b".equals(channel)) return "蓝";
        return "";
    }

    /** 色值文本：#RRGGBB */
    public static String hex(int argb) {
        return String.format("#%02X%02X%02X", Color.red(argb), Color.green(argb), Color.blue(argb));
    }
}
