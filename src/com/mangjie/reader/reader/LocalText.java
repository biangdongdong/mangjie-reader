package com.mangjie.reader.reader;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 本地纯文本的章表索引：流式扫一遍文件，只记每章标题和行首字节偏移，不存正文。
 *
 * 目的：整本读进内存有 12MB / 120 万字的硬上限，超过就只能读前半本。
 * 有了章表，就能按偏移读某一章，书多大都能开。
 *
 * 编码沿用 EncodingDetect 的判定结果；UTF-16 不在这套字节索引的射程内，
 * 遇到直接返回 note，调用方退回原来的整本读法。
 */
public final class LocalText {

    /** 一章的落点：标题文字 + 标题行首的字节偏移 */
    public static final class Chapter {
        public final String title;
        public final long offset;
        public final int index;

        Chapter(String title, long offset, int index) {
            this.title = title;
            this.offset = offset;
            this.index = index;
        }
    }

    /** 索引结果：章表 + 编码 + 扫描量 + 耗时 */
    public static final class Index {
        public final List<Chapter> chapters;
        public final String charset;
        public final long bytes;
        public final long ms;
        public final String note;
        /** 选中的目录规则名，排查用；目录页和章表用的是同一条 */
        public final String rule;
        /** true = 这本认不出章节，章表是按 1MB 字节切的「段」，title 是「第 N 段」 */
        public final boolean chunked;

        Index(List<Chapter> c, String charset, long bytes, long ms, String note, String rule) {
            this(c, charset, bytes, ms, note, rule, false);
        }

        Index(List<Chapter> c, String charset, long bytes, long ms, String note, String rule, boolean chunked) {
            this.chapters = c;
            this.charset = charset;
            this.bytes = bytes;
            this.ms = ms;
            this.note = note;
            this.rule = rule;
            this.chunked = chunked;
        }

        public boolean ok() { return note == null && !chapters.isEmpty(); }
    }

    private static final int HEAD = 64 * 1024;
    private static final int BLOCK = 256 * 1024;
    /** 标题行最多这么多字节，超了不判章（正文行不会这么短又恰好以「第N章」开头） */
    private static final int LINE_MAX = 4096;
    private static final int TITLE_MAX = 40;

    private LocalText() {}

    /** 扫一遍文件建章表 */
    public static Index index(File f) {
        long t0 = System.currentTimeMillis();
        if (f == null || !f.isFile()) return new Index(new ArrayList<Chapter>(), null, 0, 0, "文件不存在", null);
        RandomAccessFile raf = null;
        try {
            int headLen = (int) Math.min(f.length(), (long) HEAD);
            byte[] head = new byte[headLen];
            raf = new RandomAccessFile(f, "r");
            int got = 0, r0;
            while (got < headLen && (r0 = raf.read(head, got, headLen - got)) > 0) got += r0;

            EncodingDetect.Result er = EncodingDetect.detect(head, got);
            if (er == null) return new Index(new ArrayList<Chapter>(), null, 0, 0, "编码认不出", null);
            String name = er.charset.name();          // 存规范名，readChapter 里 forName 才认
            if (name.startsWith("UTF-16")) {
                return new Index(new ArrayList<Chapter>(), name, 0, 0, "UTF-16 走按段切分", null);
            }
            Charset cs = er.charset;
            long start = er.offset;

            // 先用 TocRules 从采样文本里挑出这本书的目录规则，
            // 让章表与目录页用同一条规则，不再出现「章表 0 章、目录 3 章」这种两套结果
            Pattern rule = null;
            String ruleName = "（未选）";
            try {
                String sm = sample(raf, f.length(), start, cs);
                if (!sm.isEmpty()) {
                    rule = TocRules.pick(sm);
                    ruleName = TocRules.lastRuleName();
                }
            } catch (Throwable t) {
                ruleName = "（采样失败 " + t.getClass().getSimpleName() + "）";
            }

            List<Chapter> list = new ArrayList<Chapter>();
            raf.seek(start);
            byte[] chunk = new byte[BLOCK];
            byte[] line = new byte[LINE_MAX];
            int lineLen = 0;
            long lineStart = start;       // 当前行的行首绝对字节偏移
            long base = start;            // 当前块起点
            long scanned = 0;
            int n;

            while ((n = raf.read(chunk)) > 0) {
                int s = 0;
                for (int i = 0; i < n; i++) {
                    if (chunk[i] != '\n') continue;
                    int take = i - s;
                    if (lineLen + take > LINE_MAX) take = LINE_MAX - lineLen;
                    if (take > 0) { System.arraycopy(chunk, s, line, lineLen, take); lineLen += take; }
                    if (lineLen > 0) consider(new String(line, 0, lineLen, cs), lineStart, list, rule);
                    lineLen = 0;
                    lineStart = base + i + 1;
                    s = i + 1;
                }
                int rest = n - s;
                if (rest > 0) {
                    int take = Math.min(rest, LINE_MAX - lineLen);
                    if (take > 0) System.arraycopy(chunk, s, line, lineLen, take);
                    lineLen += take;
                }
                base += n;
                scanned += n;
            }
            // 文件末尾没有换行的最后一行
            if (lineLen > 0) consider(new String(line, 0, lineLen, cs), lineStart, list, rule);

            return new Index(list, name, scanned, System.currentTimeMillis() - t0, null, ruleName);
        } catch (Throwable t) {
            return new Index(new ArrayList<Chapter>(), null, 0, System.currentTimeMillis() - t0,
                    t.getClass().getSimpleName() + " " + t.getMessage(), null);
        } finally {
            if (raf != null) { try { raf.close(); } catch (Exception ignored) {} }
        }
    }

    /** 读某一章：从本章标题行首读到下一章标题行首（或文件尾） */
    public static String readChapter(File f, Index idx, int chapterNo) {
        if (f == null || idx == null || chapterNo < 0 || chapterNo >= idx.chapters.size()) return null;
        RandomAccessFile raf = null;
        try {
            long from = idx.chapters.get(chapterNo).offset;
            long to = chapterNo + 1 < idx.chapters.size()
                    ? idx.chapters.get(chapterNo + 1).offset : f.length();
            if (to <= from) return null;
            int len = (int) Math.min(to - from, 8L * 1024 * 1024);      // 单章上限，防一手脏数据
            raf = new RandomAccessFile(f, "r");
            raf.seek(from);
            byte[] buf = new byte[len];
            int got = 0, r0;
            while (got < len && (r0 = raf.read(buf, got, len - got)) > 0) got += r0;
            // 编码跟着索引走，避免同一本书两次判定不一致
            java.nio.charset.Charset cs = java.nio.charset.Charset.forName(idx.charset);
            return new String(buf, 0, got, cs);
        } catch (Throwable t) {
            return null;
        } finally {
            if (raf != null) { try { raf.close(); } catch (Exception ignored) {} }
        }
    }

    /** 每段多少字节：按 1MB 切，UTF-8 中文约 35 万字一段 */
    private static final int CHUNK_BYTES = 1024 * 1024;

    /**
     * 认不出章节的纯文本：按 1MB 切段，段起点对齐到行首，书多大都能读到结尾。
     * 只做 seek，不扫正文，建表几乎不花时间。
     */
    public static Index chunk(File f) {
        long t0 = System.currentTimeMillis();
        if (f == null || !f.isFile()) {
            return new Index(new ArrayList<Chapter>(), null, 0, 0, "文件不存在", null, true);
        }
        RandomAccessFile raf = null;
        try {
            int headLen = (int) Math.min(f.length(), (long) HEAD);
            byte[] head = new byte[headLen];
            raf = new RandomAccessFile(f, "r");
            int got = 0, r0;
            while (got < headLen && (r0 = raf.read(head, got, headLen - got)) > 0) got += r0;

            EncodingDetect.Result er = EncodingDetect.detect(head, got);
            if (er == null) return new Index(new ArrayList<Chapter>(), null, 0, 0, "编码认不出", null, true);
            String name = er.charset.name();            // 存规范名，readChapter 里 forName 才认
            boolean wide = name.startsWith("UTF-16");   // UTF-16 要按 2 字节码元对齐
            Charset cs = er.charset;
            long start = er.offset;
            long len = f.length();

            List<Chapter> list = new ArrayList<Chapter>();
            long pos = start;
            int no = 0;
            while (pos < len) {
                list.add(new Chapter("第 " + (no + 1) + " 段", pos, no));
                no++;
                long want = pos + CHUNK_BYTES;
                if (wide && (want & 1) != 0) want++;
                if (want >= len) break;
                long next = wide ? lineStartAfterWide(raf, want) : lineStartAfter(raf, want);
                if (next <= pos) break;          // 兜底：真出不来前进就收手，别转死循环
                pos = next;
            }
            return new Index(list, name, len - start, System.currentTimeMillis() - t0, null,
                    "按 " + (CHUNK_BYTES / 1024) + "KB 分段", true);
        } catch (Throwable t) {
            return new Index(new ArrayList<Chapter>(), null, 0, System.currentTimeMillis() - t0,
                    t.getClass().getSimpleName() + " " + t.getMessage(), null, true);
        } finally {
            if (raf != null) { try { raf.close(); } catch (Exception ignored) {} }
        }
    }

    /** 从 pos 往后找第一个换行，返回它后面那个字节的位置；找不到就原样返回 */
    private static long lineStartAfter(RandomAccessFile raf, long pos) {
        try {
            raf.seek(pos);
            byte[] b = new byte[64 * 1024];
            int n = raf.read(b);
            for (int i = 0; i < n; i++) if (b[i] == '\n') return pos + i + 1;
        } catch (Throwable ignored) {}
        return pos;
    }

    /** UTF-16 版：按 2 字节码元找换行（0A 00 或 00 0A），返回换行后一码元的偏移 */
    private static long lineStartAfterWide(RandomAccessFile raf, long pos) {
        try {
            raf.seek(pos);
            byte[] b = new byte[64 * 1024];
            int n = raf.read(b);
            for (int i = 0; i + 1 < n; i += 2) {
                if ((b[i] == 0x0A && b[i + 1] == 0x00) || (b[i] == 0x00 && b[i + 1] == 0x0A)) {
                    return pos + i + 2;
                }
            }
        } catch (Throwable ignored) {}
        return pos;
    }

    private static void consider(String rawLine, long offset, List<Chapter> list, Pattern rule) {        if (rawLine == null) return;
        String t = rawLine.trim();
        if (t.isEmpty() || t.length() > TITLE_MAX) return;
        // 有选中的规则就只用它（与目录页同一套）；没选中才用兜底正则
        boolean hit = rule != null && rule.matcher(t).find();
        if (!hit && rule == null) hit = ChapterIndex.PATTERN.matcher(t).find();
        if (!hit) return;
        list.add(new Chapter(t, offset, list.size()));
    }

    private static final int SAMPLE = 32 * 1024;

    /** 头、中、尾各 32KB，按行边界对齐后用同一编码解码，供 TocRules 挑规则 */
    private static String sample(RandomAccessFile raf, long len, long start, Charset cs) {
        StringBuilder sb = new StringBuilder();
        long[] froms = {start, Math.max(start, len / 2 - SAMPLE / 2), Math.max(start, len - SAMPLE)};
        byte[] buf = new byte[SAMPLE];
        for (int k = 0; k < froms.length; k++) {
            long from = Math.max(start, froms[k]);
            try {
                raf.seek(from);
                int n = raf.read(buf);
                if (n <= 0) continue;
                String s = new String(buf, 0, n, cs);
                if (from != start) {            // 中段/尾段从头一个换行之后算起，别截断半行
                    int nl = s.indexOf('\n');
                    if (nl > 0 && nl + 1 < s.length()) s = s.substring(nl + 1);
                }
                sb.append(s).append('\n');
            } catch (Throwable ignored) {}
        }
        return sb.toString();
    }
}
