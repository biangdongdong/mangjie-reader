/*
 * 本文件的部分实现参照「开源阅读 Legado」（https://github.com/gedoor/legado）。
 * Legado：Copyright (C) 2019-2023 gedoor，GNU General Public License v3.0。
 * 本工程对其的使用方式与修改说明见仓库根目录 NOTICE，完整条款见 LICENSE。
 */
package com.mangjie.reader.reader;

import com.mangjie.reader.net.ChapterCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 网络书「搜全书」：只扫已经缓存到本地的章节，一章一章找关键词。
 *
 * 为什么不直接抓网络：一本两千章的书要发两千次请求，站点立刻封，手机也扛不住；
 * 对齐 Legado 的做法——本地有缓存就搜缓存，没缓存的部分提示用户先去缓存。
 * 纯计算，不碰 UI、不开线程，调用方自己丢到 Workers 里。
 */
public final class BookFinder {

    /** 一次搜索的结果，调用方拿它去拼屏 */
    public static final class Result {
        public String keyword = "";
        /** 命中行：{章序号, 章名, 关键词前后十几字的摘录} */
        public final List<String[]> hits = new ArrayList<String[]>();
        /** 已缓存的章节数 / 总章节数 */
        public int cached = 0;
        public int total = 0;
        /** 搜索期间书被换了（书源地址对不上），这一批结果作废 */
        public boolean stale = false;
        public String staleReason = "";
    }

    private BookFinder() {}

    /**
     * @param dataDir   数据目录（缓存就落在这里的 netcache 下）
     * @param bookUrl   这本书的网络地址，缓存是按它的 md5 分目录的
     * @param chapters  章节表快照，调用方主动 copy 一份，防止搜的过程中被换源替换
     * @param keyword   关键词
     */
    public static Result search(String dataDir, String bookUrl, List<String[]> chapters, String keyword) {
        Result r = new Result();
        r.keyword = keyword == null ? "" : keyword;
        if (chapters == null || chapters.isEmpty()) {
            r.stale = true;
            r.staleReason = "目录还没取到";
            return r;
        }
        if (r.keyword.trim().isEmpty()) {
            r.stale = true;
            r.staleReason = "没输入关键词";
            return r;
        }
        r.total = chapters.size();
        String low = r.keyword.toLowerCase(Locale.ROOT);
        for (int i = 0; i < chapters.size(); i++) {
            String[] ch = chapters.get(i);
            if (ch == null || ch.length == 0) continue;
            String body = ChapterCache.read(dataDir, bookUrl, i, ch[0]);
            if (body == null || body.isEmpty()) continue;
            r.cached++;
            int p = body.toLowerCase(Locale.ROOT).indexOf(low);
            if (p < 0) continue;
            int from = Math.max(0, p - 12);
            int to = Math.min(body.length(), p + r.keyword.length() + 18);
            String snip = body.substring(from, to).replace("\n", " ").replace("\r", " ");
            r.hits.add(new String[]{"" + i, ch[0], snip});
        }
        return r;
    }

    /** 结果屏顶上那句；空态时用后半句 */
    public static String summary(Result r) {
        return "关键词：" + r.keyword + "　命中 " + r.hits.size() + " 章　已缓存 " + r.cached + "/" + r.total + " 章";
    }

    /** 没命中时该说什么，分「一章没缓存」和「缓存里确实没有」 */
    public static String emptyHint(Result r) {
        return r.cached == 0
                ? "（这一本还没缓存任何章节，先去缓存整本再搜）"
                : "（已缓存的章节里没找到）";
    }
}
