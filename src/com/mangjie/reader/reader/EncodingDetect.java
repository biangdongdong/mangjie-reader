package com.mangjie.reader.reader;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 编码探测：不开文件头也能认出 UTF-8 / UTF-16 / GB18030(GBK) / Big5。
 *
 * 思路和开源阅读（Legado）那套一致：先看 BOM；再按 UTF-8 严格解码（带余字节截断容错），
 * 过了就定 UTF-8；没过就在 GB18030、Big5、UTF-16LE/BE 里按「常用汉字命中 + 替换符 + 控制符」
 * 打分，取分高的那一个。
 */
public final class EncodingDetect {

    /** 判定用的采样长度：前 64KB 足够定性，整本扫反而慢 */
    private static final int SAMPLE = 64 * 1024;

    /** 高频汉字，用来判断这段字节解得像不像中文 */
    private static final String COMMON =
            "的一是不了在人有我他这为之大来以个中上们到说国和地也子时道出而要于就下得可你年生自会那后能对着事其里"
                    + "所去家中信儿五子万问点外天两心情声三四手年月日眼面听写看走动里外前后左右东西南北";

    /** 这些字符出现说明解码基本失败 */
    private static final String NOISE = "�\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0008";

    private EncodingDetect() {}

    /** 探测结果 */
    public static final class Result {
        public final Charset charset;
        public final int offset;
        public final String name;

        Result(Charset charset, int offset, String name) {
            this.charset = charset;
            this.offset = offset;
            this.name = name;
        }
    }

    /** 直接解码，顺带跳过 BOM；解不出来返回 null */
    public static String decode(byte[] buf, int n) {
        Result r = detect(buf, n);
        if (r == null) return null;
        try {
            return new String(buf, r.offset, n - r.offset, r.charset);
        } catch (Exception e) {
            return null;
        }
    }

    public static Result detect(byte[] buf, int n) {
        if (buf == null || n <= 0) return null;

        // 1. BOM
        if (n >= 3 && (buf[0] & 0xFF) == 0xEF && (buf[1] & 0xFF) == 0xBB && (buf[2] & 0xFF) == 0xBF) {
            return new Result(StandardCharsets.UTF_8, 3, "UTF-8(BOM)");
        }
        if (n >= 2 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xFE) {
            return new Result(Charset.forName("UTF-16LE"), 2, "UTF-16LE(BOM)");
        }
        if (n >= 2 && (buf[0] & 0xFF) == 0xFE && (buf[1] & 0xFF) == 0xFF) {
            return new Result(Charset.forName("UTF-16BE"), 2, "UTF-16BE(BOM)");
        }

        int len = Math.min(n, SAMPLE);

        // 2. UTF-8 严格解（末尾可能被 maxBytes 截断，先砍掉残缺尾巴再试）
        String utf8 = strictUtf8(buf, len);
        if (utf8 != null && looksLikeText(utf8) >= 0) {
            return new Result(StandardCharsets.UTF_8, 0, "UTF-8");
        }

        // 3. 多个候选打分
        String[] names = {"GB18030", "Big5", "UTF-16LE", "UTF-16BE"};
        Charset[] sets = {Charset.forName("GB18030"), Charset.forName("Big5"),
                Charset.forName("UTF-16LE"), Charset.forName("UTF-16BE")};
        int bestScore = Integer.MIN_VALUE;
        Result best = null;
        for (int i = 0; i < names.length; i++) {
            String text;
            try {
                text = new String(buf, 0, len, sets[i]);
            } catch (Exception e) {
                continue;
            }
            int score = looksLikeText(text);
            if (names[i].startsWith("UTF-16")) score -= 8;  // 无 BOM 的双字节编码默认靠后
            if (score > bestScore) {
                bestScore = score;
                best = new Result(sets[i], 0, names[i]);
            }
        }
        if (best == null) best = new Result(Charset.forName("GB18030"), 0, "GB18030");
        return best;
    }

    /** UTF-8 严格解码；失败时把末尾 1~3 个残缺字节去掉再试一次 */
    private static String strictUtf8(byte[] buf, int len) {
        for (int cut = 0; cut <= 3 && len - cut > 0; cut++) {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                return dec.decode(ByteBuffer.wrap(buf, 0, len - cut)).toString();
            } catch (CharacterCodingException ignored) {
                // 再少截一个字节试试
            }
        }
        return null;
    }

    /** 越像中文正文分越高；负数表示明显不是文本 */
    private static int looksLikeText(String s) {
        if (s == null || s.isEmpty()) return Integer.MIN_VALUE;
        int score = 0;
        int sample = Math.min(s.length(), 4000);
        for (int i = 0; i < sample; i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) score += 3;        // 汉字
            else if (COMMON.indexOf(c) >= 0) score += 2;        // 高频汉字再加分
            else if (c == '\n' || c == '\r' || c == '\t' || c == ' ') score += 1;
            else if (c >= '0' && c <= '9') score += 1;
            else if (c >= 0x3000 && c <= 0x303F) score += 1;    // 中文标点
            else if (c >= 0xFF00 && c <= 0xFFEF) score += 1;    // 全角
            else if (c < 0x20) score -= 6;                      // 控制符：解码失败的典型症状
            else if (NOISE.indexOf(c) >= 0) score -= 20;
            else if (c == '\uFFFD') score -= 20;
        }
        // 控制符占比过高直接判死
        if (score < 0) return -1;
        return score;
    }
}
